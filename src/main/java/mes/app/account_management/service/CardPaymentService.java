package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.PurchaseInvoiceService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 카드지급 — 파워빌더 w_tb_ca642_01_card
 *
 * 카드내역 비용처리로 만든 매입(지급처 mijcltcd = 카드사 코드)을 카드사별로 모아
 * 현금·예금·어음·수표·카드·기타로 대금을 치른다. 지급 한 건 = TB_CA642 여러 행(매입마다 한 행, sndseq).
 *
 * 누계 규약: 매입 헤더 TB_CA640 의 hamt·bamt·eamt·samt·damt·gamt 는 그 매입의 TB_CA642 합계와 같아야 한다
 * (경기 5,782건 전부 일치 확인). 파워빌더는 지급/취소 때 += / −= 로 맞추지만,
 * 여기서는 지급·취소 뒤 TB_CA642 합계로 <b>다시 계산</b>해 늘 규약이 지켜지게 한다.
 *
 * 파워빌더 쪽 버그라 따라가지 않은 것
 *   · 매입 상세(TB_CA641)에 더한 배분액을 지급취소 때 빼지 않아 계속 쌓인다 → 상세도 합계로 다시 나눈다
 *   · 상세 배분이 금액 종류마다 따로 상세 금액 한도를 채워 합이 상세 금액을 넘는다 → 종류를 합쳐 한도 안에서
 *   · 행마다 중간 커밋 → 한 트랜잭션
 *   · 수표 배분이 어음 금액과 비교 / 자동계산 카드 배분이 잔액을 줄이지 않음 → 배분은 서버가 다시 계산
 *   · 지급금액과 배분 합계 검사가 꺼져 있음 → 선택한 미지급잔액을 넘으면 거부
 *   · 지급은행 기록이 mijcltcd 가 아닌 cltcd + 마지막 행 값으로 걸려 사실상 안 바뀜 → 매입 키로 기록
 *   · 지급번호 채번에 spjangcd 조건·잠금 없음
 *
 * 1단계에서 뺀 것: 회계전표(wf_newpub · wf_tb_aa010/aa011_insert), 계좌거래내역(tb_bank_accsave) 연결.
 * 회계전표가 붙은 지급(acc_spdate)은 웹에서 취소하지 못하게 막는다.
 */
@Slf4j
@Service
public class CardPaymentService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	PurchaseInvoiceService purchaseInvoiceService;

	/** 지급 종류 — 파워빌더 배분 순서 그대로 (현금 → 예금 → 어음 → 수표 → 카드 → 기타) */
	private static final String[] TYPES = {"hamt", "bamt", "eamt", "samt", "damt", "gamt"};

	private MapSqlParameterSource base(String spjangcd, String custcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd);
		p.addValue("spjangcd", spjangcd);
		return p;
	}

	private String requireCustcd(String spjangcd) {
		String custcd = purchaseInvoiceService.getCustcd(spjangcd);
		if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");
		return custcd;
	}

	// ────────────────────────────────────────────────────────────
	//  조회
	// ────────────────────────────────────────────────────────────

	/** 카드사 목록 (지급처) */
	public List<Map<String, Object>> getCardCompanies() {
		return sqlRunner.getRows("""
				SELECT cd AS value, ISNULL(nm, '') AS text FROM tb_xcard WITH(NOLOCK) ORDER BY cd
				""", new MapSqlParameterSource());
	}

	/**
	 * 미지급 목록 (파워빌더 d_tb_ca642_card_h).
	 * 파워빌더는 잔액이 0 인 매입도 보여준다. onlyBalance='1' 이면 잔액 있는 것만.
	 */
	public List<Map<String, Object>> getUnpaid(String spjangcd, String cardco, String stdate, String enddate,
	                                           String onlyBalance) {
		String custcd = purchaseInvoiceService.getCustcd(spjangcd);
		if (custcd == null) return List.of();

		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("cardco", str(cardco));
		p.addValue("stdate", digits(stdate));
		p.addValue("enddate", digits(enddate));
		p.addValue("onlyBalance", "1".equals(onlyBalance) ? "1" : "0");

		return sqlRunner.getRows("""
				SELECT t.* FROM (
				  SELECT h.mijdate,
				         STUFF(STUFF(h.mijdate, 5, 0, '-'), 8, 0, '-') AS mijdate_fmt,
				         h.mijnum,
				         ISNULL(h.cltcd, '')    AS cltcd,
				         ISNULL(NULLIF(h.cltnm, ''), ISNULL(x.cltnm, '')) AS cltnm,
				         ISNULL(h.mijcltcd, '') AS mijcltcd,
				         ISNULL(h.mijcltnm, '') AS mijcltnm,
				         ISNULL(h.remark, '')   AS remark,
				         ISNULL(h.cardnum, '')  AS cardnum,
				         ISNULL(h.mijamt, 0)    AS mijamt,
				         ISNULL(h.chaamt, 0) + ISNULL(h.hamt, 0) + ISNULL(h.bamt, 0) + ISNULL(h.eamt, 0)
				           + ISNULL(h.samt, 0) + ISNULL(h.damt, 0) + ISNULL(h.gamt, 0) + ISNULL(h.camt, 0) AS inamt,
				         ISNULL(h.mijamt, 0) - ISNULL(h.chaamt, 0) - ISNULL(h.hamt, 0) - ISNULL(h.bamt, 0) - ISNULL(h.eamt, 0)
				           - ISNULL(h.samt, 0) - ISNULL(h.damt, 0) - ISNULL(h.gamt, 0) - ISNULL(h.camt, 0) AS janamt
				    FROM TB_CA640 h WITH(NOLOCK)
				    LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = h.custcd AND x.cltcd = h.cltcd
				   WHERE h.custcd = :custcd AND h.spjangcd = :spjangcd
				     AND h.mijcltcd = :cardco
				     AND h.mijdate BETWEEN :stdate AND :enddate
				) t
				 WHERE (:onlyBalance = '0' OR t.janamt <> 0)
				 ORDER BY t.mijdate, t.mijnum
				""", p);
	}

	/** 지급 목록 — 지급번호별 합계 */
	public List<Map<String, Object>> getPayments(String spjangcd, String cardco, String stdate, String enddate) {
		String custcd = purchaseInvoiceService.getCustcd(spjangcd);
		if (custcd == null) return List.of();

		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("cardco", str(cardco));
		p.addValue("stdate", digits(stdate));
		p.addValue("enddate", digits(enddate));

		return sqlRunner.getRows("""
				SELECT a.snddate,
				       STUFF(STUFF(a.snddate, 5, 0, '-'), 8, 0, '-') AS snddate_fmt,
				       a.sndnum,
				       a.cltcd,
				       COUNT(*) AS cnt,
				       SUM(ISNULL(a.hamt, 0)) AS hamt, SUM(ISNULL(a.bamt, 0)) AS bamt, SUM(ISNULL(a.eamt, 0)) AS eamt,
				       SUM(ISNULL(a.samt, 0)) AS samt, SUM(ISNULL(a.damt, 0)) AS damt, SUM(ISNULL(a.gamt, 0)) AS gamt,
				       SUM(ISNULL(a.hamt, 0) + ISNULL(a.bamt, 0) + ISNULL(a.eamt, 0)
				         + ISNULL(a.samt, 0) + ISNULL(a.damt, 0) + ISNULL(a.gamt, 0)) AS totamt,
				       SUM(ISNULL(a.bmar, 0)) AS bmar,
				       MAX(ISNULL(a.bankcd, '')) AS bankcd,
				       MAX(ISNULL(a.bankno, '')) AS bankno,
				       MAX(ISNULL(a.remark, '')) AS remark,
				       MAX(ISNULL(a.acc_spdate, '')) AS acc_spdate
				  FROM TB_CA642 a WITH(NOLOCK)
				 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
				   AND a.cltcd = :cardco
				   AND a.snddate BETWEEN :stdate AND :enddate
				 GROUP BY a.snddate, a.sndnum, a.cltcd
				 ORDER BY a.snddate DESC, a.sndnum DESC
				""", p);
	}

	/** 지급 상세 — 지급번호 한 건의 매입별 행 */
	public List<Map<String, Object>> getPaymentDetail(String spjangcd, String cardco, String snddate, String sndnum) {
		String custcd = purchaseInvoiceService.getCustcd(spjangcd);
		if (custcd == null) return List.of();

		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("cardco", str(cardco));
		p.addValue("snddate", digits(snddate));
		p.addValue("sndnum", str(sndnum));

		return sqlRunner.getRows("""
				SELECT a.sndseq,
				       a.mijdate,
				       STUFF(STUFF(a.mijdate, 5, 0, '-'), 8, 0, '-') AS mijdate_fmt,
				       a.mijnum,
				       ISNULL(NULLIF(h.cltnm, ''), '') AS cltnm,
				       ISNULL(h.remark, '')  AS mijremark,
				       ISNULL(h.mijamt, 0)   AS mijamt,
				       ISNULL(a.hamt, 0) AS hamt, ISNULL(a.bamt, 0) AS bamt, ISNULL(a.eamt, 0) AS eamt,
				       ISNULL(a.samt, 0) AS samt, ISNULL(a.damt, 0) AS damt, ISNULL(a.gamt, 0) AS gamt,
				       ISNULL(a.hamt, 0) + ISNULL(a.bamt, 0) + ISNULL(a.eamt, 0)
				         + ISNULL(a.samt, 0) + ISNULL(a.damt, 0) + ISNULL(a.gamt, 0) AS totamt
				  FROM TB_CA642 a WITH(NOLOCK)
				  LEFT JOIN TB_CA640 h WITH(NOLOCK)
				    ON h.custcd = a.custcd AND h.spjangcd = a.spjangcd AND h.mijdate = a.mijdate AND h.mijnum = a.mijnum
				 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
				   AND a.cltcd = :cardco AND a.snddate = :snddate AND a.sndnum = :sndnum
				 ORDER BY a.sndseq
				""", p);
	}

	// ────────────────────────────────────────────────────────────
	//  배분 계산 (cb_exec 자동계산 / cb_exec1 수동계산)
	// ────────────────────────────────────────────────────────────

	/**
	 * 지급 종류별 금액을 미지급 행에 위에서부터 나눈다. 행마다 미지급잔액까지만 채운다.
	 * 화면 미리보기와 저장이 같은 계산을 쓴다.
	 *
	 * @param header  hamt~gamt
	 * @param rows    mijdate·mijnum·janamt 를 가진 행 (배분 순서대로)
	 * @return 행마다 {mijdate, mijnum, hamt~gamt, total}. 금액이 남으면 remain 에 담는다
	 */
	public Map<String, Object> allocate(Map<String, Object> header, List<Map<String, Object>> rows) {
		double[] remain = new double[TYPES.length];
		for (int i = 0; i < TYPES.length; i++) remain[i] = num(header.get(TYPES[i]));

		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			double cap = num(r.get("janamt"));
			if (cap <= 0) continue;

			Map<String, Object> a = new LinkedHashMap<>();
			a.put("mijdate", str(r.get("mijdate")));
			a.put("mijnum", str(r.get("mijnum")));
			double total = 0;
			for (int i = 0; i < TYPES.length; i++) {
				double take = Math.min(remain[i], cap);
				if (take < 0) take = 0;
				a.put(TYPES[i], take);
				remain[i] -= take;
				cap -= take;
				total += take;
			}
			if (total > 0) {
				a.put("total", total);
				result.add(a);
			}
			if (Arrays.stream(remain).allMatch(v -> v <= 0)) break;
		}

		double left = Arrays.stream(remain).filter(v -> v > 0).sum();
		return Map.of("rows", result, "remain", left);
	}

	// ────────────────────────────────────────────────────────────
	//  지급 저장 (ue_save)
	// ────────────────────────────────────────────────────────────

	/**
	 * @param header   snddate, hamt~gamt, bmar, bankcd, bankno, enum, edate, eidate, ecltcd, snum,
	 *                 cardcd, persent, cardno, ggubun, remark, perid
	 * @param mode     'auto' = 잔액 있는 행 전체를 위에서부터, 'manual' = keys 로 고른 행만
	 * @param keys     manual 일 때 [{mijdate, mijnum}]
	 */
	@Transactional
	public Map<String, Object> save(String spjangcd, String cardco, Map<String, Object> header, String mode,
	                                List<Map<String, Object>> keys, String stdate, String enddate, String userId) {
		String custcd = requireCustcd(spjangcd);

		cardco = str(cardco);
		if (cardco.isEmpty()) throw new IllegalStateException("카드사를 선택해주세요.");

		String snddate = digits(str(header.get("snddate")));
		if (!snddate.matches("\\d{8}")) throw new IllegalStateException("지급일자를 입력해주세요.");

		double total = 0;
		for (String t : TYPES) {
			double v = num(header.get(t));
			if (v < 0) throw new IllegalStateException("지급금액은 음수로 입력할 수 없습니다.");
			total += v;
		}
		if (total == 0) throw new IllegalStateException("지급할 금액을 확인하십시요!");
		if (num(header.get("bamt")) > 0 && str(header.get("bankcd")).isEmpty()) {
			throw new IllegalStateException("예금 지급은 지급은행을 선택해주세요.");
		}

		// 대상 매입을 잠그고 잔액을 DB 에서 다시 읽는다 (화면 값은 믿지 않는다)
		MapSqlParameterSource lp = base(spjangcd, custcd);
		lp.addValue("cardco", cardco);
		lp.addValue("stdate", digits(stdate));
		lp.addValue("enddate", digits(enddate));
		List<Map<String, Object>> candidates = sqlRunner.getRows("""
				SELECT h.mijdate, h.mijnum,
				       ISNULL(h.mijamt, 0) - ISNULL(h.chaamt, 0) - ISNULL(h.hamt, 0) - ISNULL(h.bamt, 0) - ISNULL(h.eamt, 0)
				         - ISNULL(h.samt, 0) - ISNULL(h.damt, 0) - ISNULL(h.gamt, 0) - ISNULL(h.camt, 0) AS janamt,
				       ISNULL(h.billkind, '') AS billkind, ISNULL(h.divicd, '') AS divicd
				  FROM TB_CA640 h WITH(UPDLOCK, HOLDLOCK)
				 WHERE h.custcd = :custcd AND h.spjangcd = :spjangcd AND h.mijcltcd = :cardco
				   AND h.mijdate BETWEEN :stdate AND :enddate
				 ORDER BY h.mijdate, h.mijnum
				""", lp);
		if (candidates == null) throw new IllegalStateException("미지급 목록을 확인하지 못했습니다.");

		List<Map<String, Object>> targets;
		if ("manual".equals(mode)) {
			if (keys == null || keys.isEmpty()) throw new IllegalStateException("선택된 자료가 없습니다!");
			Set<String> picked = new HashSet<>();
			for (Map<String, Object> k : keys) picked.add(str(k.get("mijdate")) + "|" + str(k.get("mijnum")));
			targets = candidates.stream()
					.filter(c -> picked.contains(str(c.get("mijdate")) + "|" + str(c.get("mijnum"))))
					.toList();
			if (targets.size() != picked.size()) {
				throw new IllegalStateException("선택한 미지급 자료 중 조회기간·카드사에 맞지 않는 것이 있습니다. 다시 조회해주세요.");
			}
		} else {
			targets = candidates;
		}

		Map<String, Object> alloc = allocate(header, targets);
		double left = num(alloc.get("remain"));
		if (left > 0) {
			throw new IllegalStateException("선택된 총지급액이 미지급잔액보다 " + String.format("%,.0f", left) + "원 큽니다!");
		}
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) alloc.get("rows");
		if (rows.isEmpty()) throw new IllegalStateException("지급할 미지급잔액이 없습니다.");

		Map<String, Map<String, Object>> byKey = new HashMap<>();
		for (Map<String, Object> c : targets) byKey.put(str(c.get("mijdate")) + "|" + str(c.get("mijnum")), c);

		String sndnum = nextSndnum(spjangcd, custcd, snddate);
		String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

		int seq = 0;
		for (Map<String, Object> a : rows) {
			seq++;
			Map<String, Object> src = byKey.get(str(a.get("mijdate")) + "|" + str(a.get("mijnum")));

			MapSqlParameterSource ip = base(spjangcd, custcd);
			ip.addValue("cltcd", cardco);
			ip.addValue("mijdate", a.get("mijdate"));
			ip.addValue("mijnum", a.get("mijnum"));
			ip.addValue("snddate", snddate);
			ip.addValue("sndnum", sndnum);
			ip.addValue("sndseq", String.format("%03d", seq));
			ip.addValue("perid", str(header.get("perid")));
			ip.addValue("remark", str(header.get("remark")));
			ip.addValue("billkind", src == null ? "" : str(src.get("billkind")));
			ip.addValue("divicd", src == null ? "" : str(src.get("divicd")));
			ip.addValue("today", today);
			ip.addValue("inperid", userId == null ? "" : userId);
			for (String t : TYPES) ip.addValue(t, num(a.get(t)));

			// 금액이 있는 종류만 딸린 정보를 넣는다 (파워빌더 wf_dw_4_ins)
			boolean b = num(a.get("bamt")) != 0, e = num(a.get("eamt")) != 0, s = num(a.get("samt")) != 0,
					d = num(a.get("damt")) != 0, g = num(a.get("gamt")) != 0;
			ip.addValue("bankcd", b ? str(header.get("bankcd")) : "");
			ip.addValue("bankno", b ? str(header.get("bankno")) : "");
			ip.addValue("enum", e ? str(header.get("enum")) : "");
			ip.addValue("edate", e ? digits(str(header.get("edate"))) : "");
			ip.addValue("eidate", e ? digits(str(header.get("eidate"))) : "");
			ip.addValue("ecltcd", e ? str(header.get("ecltcd")) : "");
			ip.addValue("snum", s ? str(header.get("snum")) : "");
			ip.addValue("cardcd", d ? str(header.get("cardcd")) : "");
			ip.addValue("persent", d ? num(header.get("persent")) : 0);
			ip.addValue("cardno", d ? str(header.get("cardno")) : "");
			ip.addValue("ggubun", g ? str(header.get("ggubun")) : "");
			// 예금수수료는 첫 행에만 (파워빌더 dw_4.bmar[1])
			ip.addValue("bmar", seq == 1 ? num(header.get("bmar")) : 0);
			ip.addValue("bmarflag", seq == 1 ? str(header.get("bmarflag")) : "");

			int n = sqlRunner.execute("""
					INSERT INTO TB_CA642 (custcd, spjangcd, cltcd, mijdate, mijnum, snddate, sndnum, sndseq,
					                      accyn, perid, hamt, bamt, bankcd, bankno, eamt, enum, edate, eidate, ecltcd,
					                      samt, snum, damt, cardcd, persent, cardno, gamt, ggubun,
					                      remark, billkind, divicd, bmar, bmarflag, indate, inperid)
					VALUES (:custcd, :spjangcd, :cltcd, :mijdate, :mijnum, :snddate, :sndnum, :sndseq,
					        '0', :perid, :hamt, :bamt, :bankcd, :bankno, :eamt, :enum, :edate, :eidate, :ecltcd,
					        :samt, :snum, :damt, :cardcd, :persent, :cardno, :gamt, :ggubun,
					        :remark, :billkind, :divicd, :bmar, :bmarflag, :today, :inperid)
					""", ip);
			if (n == 0) throw new IllegalStateException("지급 저장에 실패했습니다. (" + a.get("mijdate") + "-" + a.get("mijnum") + ")");
		}

		for (Map<String, Object> a : rows) {
			syncPurchase(spjangcd, custcd, str(a.get("mijdate")), str(a.get("mijnum")), false);
		}

		log.info("[카드지급] 지급 {}-{} {}건, 총 {} (spjangcd={}, cardco={})", snddate, sndnum, rows.size(), total, spjangcd, cardco);
		return Map.of("snddate", snddate, "sndnum", sndnum, "count", rows.size());
	}

	// ────────────────────────────────────────────────────────────
	//  지급 취소 (ue_delete)
	// ────────────────────────────────────────────────────────────

	@Transactional
	public int delete(String spjangcd, String cardco, String snddate, String sndnum) {
		String custcd = requireCustcd(spjangcd);

		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("cardco", str(cardco));
		p.addValue("snddate", digits(snddate));
		p.addValue("sndnum", str(sndnum));

		List<Map<String, Object>> rows = sqlRunner.getRows("""
				SELECT mijdate, mijnum, ISNULL(acc_spdate, '') AS acc_spdate
				  FROM TB_CA642 WITH(UPDLOCK, HOLDLOCK)
				 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cardco
				   AND snddate = :snddate AND sndnum = :sndnum
				""", p);
		if (rows == null) throw new IllegalStateException("지급 내역을 확인하지 못했습니다.");
		if (rows.isEmpty()) throw new IllegalStateException("취소할 지급을 찾을 수 없습니다.");

		for (Map<String, Object> r : rows) {
			if (!str(r.get("acc_spdate")).isEmpty()) {
				throw new IllegalStateException("전표처리된 DATA입니다. 전표취소후 작업하십시요!");
			}
		}

		int deleted = sqlRunner.execute("""
				DELETE FROM TB_CA642
				 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cardco
				   AND snddate = :snddate AND sndnum = :sndnum
				""", p);
		if (deleted != rows.size()) throw new IllegalStateException("지급 취소 중 오류가 발생했습니다.");

		Set<String> done = new HashSet<>();
		for (Map<String, Object> r : rows) {
			String key = str(r.get("mijdate")) + "|" + str(r.get("mijnum"));
			if (done.add(key)) syncPurchase(spjangcd, custcd, str(r.get("mijdate")), str(r.get("mijnum")), true);
		}

		log.info("[카드지급] 취소 {}-{} {}건 (spjangcd={})", snddate, sndnum, deleted, spjangcd);
		return deleted;
	}

	// ────────────────────────────────────────────────────────────
	//  누계 맞추기
	// ────────────────────────────────────────────────────────────

	/**
	 * 매입 하나의 지급누계를 TB_CA642 합계로 다시 맞춘다.
	 *   · TB_CA640 (+ 마감이월 TB_CA640_END) 헤더의 hamt~gamt, 지급은행
	 *   · TB_CA641 상세의 hamt~gamt — 상세 순번대로 상세 금액 한도 안에서 다시 나눈다
	 */
	private void syncPurchase(String spjangcd, String custcd, String mijdate, String mijnum, boolean clearBankIfNone) {
		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("mijdate", mijdate);
		p.addValue("mijnum", mijnum);

		Map<String, Object> sum = sqlRunner.getRow("""
				SELECT ISNULL(SUM(ISNULL(hamt, 0)), 0) AS hamt, ISNULL(SUM(ISNULL(bamt, 0)), 0) AS bamt,
				       ISNULL(SUM(ISNULL(eamt, 0)), 0) AS eamt, ISNULL(SUM(ISNULL(samt, 0)), 0) AS samt,
				       ISNULL(SUM(ISNULL(damt, 0)), 0) AS damt, ISNULL(SUM(ISNULL(gamt, 0)), 0) AS gamt,
				       (SELECT TOP 1 ISNULL(bankcd, '') FROM TB_CA642 WITH(NOLOCK)
				         WHERE custcd = :custcd AND spjangcd = :spjangcd AND mijdate = :mijdate AND mijnum = :mijnum
				           AND ISNULL(bamt, 0) <> 0 ORDER BY snddate DESC, sndnum DESC, sndseq DESC) AS bankcd,
				       (SELECT TOP 1 ISNULL(bankno, '') FROM TB_CA642 WITH(NOLOCK)
				         WHERE custcd = :custcd AND spjangcd = :spjangcd AND mijdate = :mijdate AND mijnum = :mijnum
				           AND ISNULL(bamt, 0) <> 0 ORDER BY snddate DESC, sndnum DESC, sndseq DESC) AS bankno
				  FROM TB_CA642 WITH(NOLOCK)
				 WHERE custcd = :custcd AND spjangcd = :spjangcd AND mijdate = :mijdate AND mijnum = :mijnum
				""", p);
		if (sum == null) throw new IllegalStateException("지급 합계를 확인하지 못했습니다.");

		for (String t : TYPES) p.addValue(t, num(sum.get(t)));
		p.addValue("bankcd", str(sum.get("bankcd")));
		p.addValue("bankno", str(sum.get("bankno")));
		// 지급은행(bankcd/bankno)은 매입관리에서도 입력하는 칸이다.
		// 예금 지급이 있으면 가장 최근 예금 지급의 은행으로, 취소 뒤 예금 지급이 하나도 없으면 비운다 (파워빌더 ue_delete).
		// 예금이 아닌 지급을 저장할 때는 원래 값을 그대로 둔다.
		boolean hasBank = num(sum.get("bamt")) != 0;
		p.addValue("bankMode", hasBank ? "set" : (clearBankIfNone ? "clear" : "keep"));

		String setSql = """
				   SET hamt = :hamt, bamt = :bamt, eamt = :eamt, samt = :samt, damt = :damt, gamt = :gamt,
				       bankcd = CASE :bankMode WHEN 'set' THEN :bankcd WHEN 'clear' THEN '' ELSE bankcd END,
				       bankno = CASE :bankMode WHEN 'set' THEN :bankno WHEN 'clear' THEN '' ELSE bankno END
				 WHERE custcd = :custcd AND spjangcd = :spjangcd AND mijdate = :mijdate AND mijnum = :mijnum
				""";
		int h = sqlRunner.execute("UPDATE TB_CA640 " + setSql, p);
		if (h == 0) throw new IllegalStateException("매입 " + mijdate + "-" + mijnum + " 의 지급누계 갱신에 실패했습니다.");
		// 마감이월 자료는 있을 때만 갱신된다 (0건이어도 정상)
		sqlRunner.execute("UPDATE TB_CA640_END " + setSql, p);

		List<Map<String, Object>> lines = sqlRunner.getRows("""
				SELECT seq, ISNULL(mijamt, 0) AS mijamt FROM TB_CA641 WITH(NOLOCK)
				 WHERE custcd = :custcd AND spjangcd = :spjangcd AND mijdate = :mijdate AND mijnum = :mijnum
				 ORDER BY seq
				""", p);
		if (lines == null || lines.isEmpty()) return;

		double[] remain = new double[TYPES.length];
		for (int i = 0; i < TYPES.length; i++) remain[i] = num(sum.get(TYPES[i]));

		for (int li = 0; li < lines.size(); li++) {
			Map<String, Object> line = lines.get(li);
			boolean last = li == lines.size() - 1;
			double cap = num(line.get("mijamt"));

			MapSqlParameterSource lp = base(spjangcd, custcd);
			lp.addValue("mijdate", mijdate);
			lp.addValue("mijnum", mijnum);
			lp.addValue("seq", str(line.get("seq")));
			for (int i = 0; i < TYPES.length; i++) {
				// 마지막 줄에는 남은 금액을 모두 둔다 (지급이 상세 합계를 넘는 예외 자료에서도 금액이 사라지지 않게)
				double take = last ? remain[i] : Math.max(0, Math.min(remain[i], cap));
				lp.addValue(TYPES[i], take);
				remain[i] -= take;
				cap -= take;
			}
			int n = sqlRunner.execute("""
					UPDATE TB_CA641
					   SET hamt = :hamt, bamt = :bamt, eamt = :eamt, samt = :samt, damt = :damt, gamt = :gamt
					 WHERE custcd = :custcd AND spjangcd = :spjangcd AND mijdate = :mijdate AND mijnum = :mijnum
					   AND seq = :seq
					""", lp);
			if (n == 0) throw new IllegalStateException("매입 상세 " + mijdate + "-" + mijnum + " 의 지급누계 갱신에 실패했습니다.");
		}
	}

	/** 지급번호 채번 — 지급일자별 MAX+1 (파워빌더 wf_newnum). 9999 를 넘으면 거부 */
	private String nextSndnum(String spjangcd, String custcd, String snddate) {
		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("snddate", snddate);
		Map<String, Object> row = sqlRunner.getRow("""
				SELECT ISNULL(MAX(TRY_CAST(sndnum AS int)), 0) + 1 AS nextnum
				  FROM TB_CA642 WITH(UPDLOCK, HOLDLOCK)
				 WHERE custcd = :custcd AND spjangcd = :spjangcd AND snddate = :snddate
				""", p);
		if (row == null) throw new IllegalStateException("지급번호를 채번하지 못했습니다.");
		int next = ((Number) row.get("nextnum")).intValue();
		if (next > 9999) throw new IllegalStateException(snddate + " 지급번호가 9999에 도달했습니다. 내일 입력하세요...");
		return String.format("%04d", next);
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}

	private static String digits(String s) {
		return s == null ? "" : s.replaceAll("[^0-9]", "");
	}

	private static double num(Object o) {
		if (o == null) return 0;
		if (o instanceof Number n) return n.doubleValue();
		try { return Double.parseDouble(String.valueOf(o).replace(",", "").trim()); } catch (Exception e) { return 0; }
	}
}
