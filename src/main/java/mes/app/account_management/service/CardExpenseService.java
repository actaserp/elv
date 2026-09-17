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
import java.util.List;
import java.util.Map;

/**
 * 카드내역 — 파워빌더 두 화면을 한 화면으로 합친 것
 *   · 카드내역가져오기 w_input_da026_card_list : 바로빌 카드 사용내역 조회 (수집은 CardHistoryService)
 *   · 카드거래정보등록 w_input_da026_card_01   : 비용항목·계정·거래처 입력 후 비용처리 / 비용취소
 *
 * 비용처리는 카드 사용 한 건마다 매입(TB_CA640 헤더 + TB_CA641 상세 1줄)을 만들고
 * 카드내역(TB_bank_cdsave)에 flag='1' 과 매입번호(mijdate/mijnum)를 남긴다.
 * 지급처(mijcltcd)에는 거래처가 아니라 <b>카드사 코드</b>(tb_xcard.cd = TB_IZ010.cardco)를 넣는다.
 * 카드지급(CardPaymentService)이 이 카드사 코드로 미지급 매입을 모아 대금을 치른다.
 *
 * 1단계에서 뺀 것 (매입관리와 같은 기준)
 *   · 부가세 자료 생성 wf_addtax03 (TB_IA055)
 *   · 회계전표 생성 wf_spflag (TB_AA010 / TB_AA011)
 *   그래서 accyn 은 파워빌더의 '1'(전표발행)이 아니라 '0' 으로 넣는다.
 *
 * 파워빌더 쪽 버그라 따라가지 않은 것
 *   · 매입번호 채번에 custcd·spjangcd 조건과 잠금이 없다 → 매입관리 채번을 쓴다
 *   · 비용취소가 카드내역 연결을 승인번호(bnkcode)만으로 풀어 같은 승인번호의 다른 행까지 풀린다 → PK 전체로
 *   · 비용취소가 지급(TB_CA642) 여부를 보지 않고 매입을 지운다 → 지급됐으면 막는다
 *   · 조회 때 연결 확인을 수금(tb_da026)으로 해서 늘 오류가 나고 처리 표시가 지워진다 → 옮기지 않았다
 *
 * 유효 여부(ovrs_use_yn): 파워빌더는 '1' 만 보여준다. 옛 수집기 자료는 'A', 예전 웹 수집분은 NULL 이라
 * 무효('0')만 빼고 보여준다.
 */
@Slf4j
@Service
public class CardExpenseService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	PurchaseInvoiceService purchaseInvoiceService;

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

	/** 등록 카드 목록 (파워빌더 위쪽 카드 그리드) */
	public List<Map<String, Object>> getCards(String spjangcd) {
		String custcd = purchaseInvoiceService.getCustcd(spjangcd);
		if (custcd == null) return List.of();

		return sqlRunner.getRows("""
				SELECT a.cardnum,
				       ISNULL(a.cardnm, '')  AS cardnm,
				       ISNULL(a.cardco, '')  AS cardco,
				       ISNULL(x.nm, '')      AS cardconm,
				       ISNULL(NULLIF(a.cardperson, ''), ISNULL(a.cardperid, '')) AS cardperson,
				       ISNULL(a.useyn, '')   AS useyn
				  FROM TB_IZ010 a WITH(NOLOCK)
				  LEFT JOIN tb_xcard x WITH(NOLOCK) ON x.cd = a.cardco
				 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
				 ORDER BY CASE WHEN ISNULL(a.useyn, '') = '1' THEN 0 ELSE 1 END, a.cardnum
				""", base(spjangcd, custcd));
	}

	/**
	 * 카드 사용내역 (파워빌더 d_tb_da026_cd03).
	 * 파워빌더는 카드 한 장씩 조회하지만 웹은 카드를 비우면 전체를 보여준다.
	 *
	 * @param flag '' 전체 / '0' 미처리 / '1' 처리
	 */
	public List<Map<String, Object>> getList(String spjangcd, String frdate, String todate,
	                                         String cardnum, String flag) {
		String custcd = purchaseInvoiceService.getCustcd(spjangcd);
		if (custcd == null) return List.of();

		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("frdate", digits(frdate));
		p.addValue("todate", digits(todate));
		p.addValue("cardnum", digits(cardnum));
		p.addValue("flag", flag == null ? "" : flag.trim());

		return sqlRunner.getRows("""
				SELECT a.bnkcode,
				       a.biz_no,
				       a.card_no,
				       ISNULL(ic.cardnm, '')  AS cardnm,
				       a.apv_dt,
				       STUFF(STUFF(a.apv_dt, 5, 0, '-'), 8, 0, '-') AS apv_dt_fmt,
				       ISNULL(a.apv_tm, '')   AS apv_tm,
				       ISNULL(a.apv_no, '')   AS apv_no,
				       ISNULL(a.card_tpbz_nm, '') AS card_tpbz_nm,
				       ISNULL(a.buy_sum, 0)   AS buy_sum,
				       ISNULL(a.sply_amt, 0)  AS sply_amt,
				       ISNULL(a.vat_amt, 0)   AS vat_amt,
				       ISNULL(a.curr_amt, 0)  AS curr_amt,
				       ISNULL(a.srv_fee, 0)   AS srv_fee,
				       ISNULL(a.comm, 0)      AS comm,
				       ISNULL(a.mest_nm, '')      AS mest_nm,
				       ISNULL(a.mest_biz_no, '')  AS mest_biz_no,
				       ISNULL(a.mest_repr_nm, '') AS mest_repr_nm,
				       ISNULL(a.mest_tel_no, '')  AS mest_tel_no,
				       ISNULL(a.flag, '0')    AS flag,
				       ISNULL(a.mijdate, '')  AS mijdate,
				       ISNULL(a.mijnum, '')   AS mijnum,
				       ISNULL(a.spdate, '')   AS spdate,
				       ISNULL(a.spnum, '')    AS spnum,
				       ISNULL(a.taxreclafi, '') AS taxreclafi,
				       ISNULL(a.taxrenm, '')  AS taxrenm,
				       ISNULL(a.artcd, '')    AS artcd,
				       ISNULL(b.artnm, '')    AS artnm,
				       ISNULL(a.acccd, '')    AS acccd,
				       ISNULL(c.accnm, '')    AS accnm,
				       ISNULL(a.cltcd, '')    AS cltcd,
				       ISNULL(d.cltnm, '')    AS cltnm,
				       ISNULL(a.spitemnm, '') AS spitemnm,
				       ISNULL(a.subject, '')  AS subject,
				       ISNULL(a.summy, '')    AS summy,
				       ISNULL(a.divicd, '')   AS divicd,
				       ISNULL(e.divinm, '')   AS divinm,
				       ISNULL(f.tax_spdate, '') AS tax_spdate,
				       ISNULL(f.acc_spdate, '') AS acc_spdate,
				       ISNULL(z.rcpt_img_url, '') AS rcpt_img_url
				  FROM TB_bank_cdsave a WITH(NOLOCK)
				  LEFT JOIN tb_bank_cdimg z WITH(NOLOCK)
				    ON z.custcd = a.custcd AND z.spjangcd = a.spjangcd AND z.card_no = a.card_no
				   AND z.seq = a.seq AND z.apv_dt = a.apv_dt
				  LEFT JOIN TB_CA648 b WITH(NOLOCK) ON b.custcd = a.custcd AND b.spjangcd = a.spjangcd AND b.artcd = a.artcd
				  LEFT JOIN TB_AC001 c WITH(NOLOCK) ON c.custcd = a.custcd AND c.acccd = a.acccd
				  LEFT JOIN TB_XCLIENT d WITH(NOLOCK) ON d.custcd = a.custcd AND d.cltcd = a.cltcd
				  LEFT JOIN TB_JC002 e WITH(NOLOCK) ON e.custcd = a.custcd AND e.spjangcd = a.spjangcd AND e.divicd = a.divicd
				  LEFT JOIN TB_CA640 f WITH(NOLOCK)
				    ON f.custcd = a.custcd AND f.spjangcd = a.spjangcd AND f.mijdate = a.mijdate AND f.mijnum = a.mijnum
				  OUTER APPLY (SELECT TOP 1 cardnm FROM TB_IZ010 WITH(NOLOCK)
				                WHERE custcd = a.custcd AND spjangcd = a.spjangcd AND cardnum = a.card_no) ic
				 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
				   AND a.apv_dt BETWEEN :frdate AND :todate
				   AND (:cardnum = '' OR a.card_no = :cardnum)
				   AND ISNULL(a.ovrs_use_yn, '') <> '0'
				   AND (:flag = '' OR ISNULL(a.flag, '0') = :flag)
				 ORDER BY a.card_no, a.apv_dt, a.apv_tm
				""", p);
	}

	// ────────────────────────────────────────────────────────────
	//  거래정보 저장 (비용항목·계정·거래처 등)
	// ────────────────────────────────────────────────────────────

	/** 미처리 건의 입력칸만 저장한다. 처리된 건은 매입에 반영된 뒤라 바꾸지 않는다 */
	@Transactional
	public int saveInfo(String spjangcd, List<Map<String, Object>> rows) {
		String custcd = requireCustcd(spjangcd);
		if (rows == null || rows.isEmpty()) throw new IllegalStateException("저장할 내역이 없습니다.");

		int saved = 0;
		for (Map<String, Object> r : rows) {
			MapSqlParameterSource p = keyParam(spjangcd, custcd, r);
			p.addValue("taxreclafi", str(r.get("taxreclafi")));
			p.addValue("taxrenm", str(r.get("taxrenm")));
			p.addValue("artcd", str(r.get("artcd")));
			p.addValue("acccd", str(r.get("acccd")));
			p.addValue("cltcd", str(r.get("cltcd")));
			p.addValue("spitemnm", str(r.get("spitemnm")));
			p.addValue("subject", str(r.get("subject")));
			p.addValue("summy", str(r.get("summy")));
			p.addValue("divicd", str(r.get("divicd")));

			int n = sqlRunner.execute("""
					UPDATE TB_bank_cdsave
					   SET taxreclafi = :taxreclafi, taxrenm = :taxrenm, artcd = :artcd, acccd = :acccd,
					       cltcd = :cltcd, spitemnm = :spitemnm, subject = :subject, summy = :summy,
					       divicd = :divicd
					 WHERE custcd = :custcd AND spjangcd = :spjangcd AND bnkcode = :bnkcode AND biz_no = :biz_no
					   AND ISNULL(flag, '0') <> '1'
					""", p);
			if (n == 0) {
				throw new IllegalStateException("승인번호 " + str(r.get("apv_no"))
						+ " 은(는) 이미 비용처리됐거나 찾을 수 없어 저장하지 않았습니다.");
			}
			saved += n;
		}
		return saved;
	}

	// ────────────────────────────────────────────────────────────
	//  비용처리 (wf_save)
	// ────────────────────────────────────────────────────────────

	@Transactional
	public int process(String spjangcd, List<Map<String, Object>> keys, String userId) {
		String custcd = requireCustcd(spjangcd);
		if (keys == null || keys.isEmpty()) throw new IllegalStateException("비용처리할 내역을 선택해주세요.");

		String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
		int done = 0;

		for (Map<String, Object> k : keys) {
			MapSqlParameterSource kp = keyParam(spjangcd, custcd, k);

			// 화면 값이 아니라 DB 에 저장된 내역으로 처리한다 (입력칸은 먼저 저장해야 반영된다)
			Map<String, Object> r = sqlRunner.getRow("""
					SELECT a.card_no, a.apv_dt, ISNULL(a.apv_no, '') AS apv_no,
					       ISNULL(a.buy_sum, 0) AS buy_sum, ISNULL(a.sply_amt, 0) AS sply_amt,
					       ISNULL(a.vat_amt, 0) AS vat_amt, ISNULL(a.curr_amt, 0) AS curr_amt,
					       ISNULL(a.flag, '0') AS flag, ISNULL(a.ovrs_use_yn, '') AS ovrs_use_yn,
					       ISNULL(a.cltcd, '') AS cltcd, ISNULL(a.spitemnm, '') AS spitemnm,
					       ISNULL(a.acccd, '') AS acccd, ISNULL(a.artcd, '') AS artcd,
					       ISNULL(a.divicd, '') AS divicd, ISNULL(a.taxreclafi, '') AS taxreclafi,
					       ISNULL(a.subject, '') AS subject, ISNULL(a.summy, '') AS summy
					  FROM TB_bank_cdsave a WITH(UPDLOCK)
					 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND a.bnkcode = :bnkcode AND a.biz_no = :biz_no
					""", kp);
			if (r == null) throw new IllegalStateException("카드 사용내역을 찾을 수 없습니다.");

			String apvNo = str(r.get("apv_no"));
			if ("1".equals(str(r.get("flag")))) {
				throw new IllegalStateException("승인번호 " + apvNo + " 은(는) 이미 비용처리됐습니다.");
			}
			if ("0".equals(str(r.get("ovrs_use_yn")))) {
				throw new IllegalStateException("승인번호 " + apvNo + " 은(는) 취소·거절된 결제라 비용처리할 수 없습니다.");
			}

			String cardNo = str(r.get("card_no"));
			String apvDt = str(r.get("apv_dt"));
			if (!apvDt.matches("\\d{8}")) throw new IllegalStateException("승인번호 " + apvNo + " 의 승인일자가 올바르지 않습니다.");

			// 카드사 (파워빌더 dw_condi.itemchanged — tb_xcard 와 TB_IZ010 조인)
			MapSqlParameterSource cp = base(spjangcd, custcd);
			cp.addValue("cardnum", cardNo);
			Map<String, Object> card = sqlRunner.getRow("""
					SELECT TOP 1 A.cd AS cardco, ISNULL(B.cardnm, '') AS cardnm
					  FROM TB_IZ010 B WITH(NOLOCK)
					  JOIN tb_xcard A WITH(NOLOCK) ON A.cd = B.cardco
					 WHERE B.custcd = :custcd AND B.spjangcd = :spjangcd AND B.cardnum = :cardnum
					""", cp);
			if (card == null) {
				throw new IllegalStateException("카드 " + cardNo + " 가 신용카드 등록에 없거나 카드사가 비어 있습니다.");
			}
			String cardco = str(card.get("cardco"));
			String cardnm = str(card.get("cardnm"));

			// 금액: 결제금액 = 공급 + 부가세 − 할인(curr_amt). 공급·부가세가 둘 다 0 이면 승인금액을 공급으로 본다
			double buySum = num(r.get("buy_sum"));
			double vat = num(r.get("vat_amt"));
			double sply = num(r.get("sply_amt"));
			double curr = num(r.get("curr_amt"));
			if (vat == 0 && sply == 0) sply = buySum;
			double cardAmt = vat + sply - curr;

			String mijnum = purchaseInvoiceService.nextMijnum(spjangcd, custcd, apvDt);

			MapSqlParameterSource hp = base(spjangcd, custcd);
			hp.addValue("mijdate", apvDt);
			hp.addValue("mijnum", mijnum);
			hp.addValue("remark", str(r.get("subject")));
			hp.addValue("bigo", str(r.get("summy")));
			hp.addValue("artcd", str(r.get("artcd")));
			hp.addValue("cltcd", str(r.get("cltcd")));
			hp.addValue("cltnm", str(r.get("spitemnm")));
			hp.addValue("taxreclafi", str(r.get("taxreclafi")));
			hp.addValue("divicd", str(r.get("divicd")));
			hp.addValue("acccd", str(r.get("acccd")));
			hp.addValue("yyyymm", apvDt.substring(0, 6));
			hp.addValue("cardco", cardco);
			hp.addValue("cardnm", cardnm);
			hp.addValue("cardnum", cardNo);
			hp.addValue("mijamt", cardAmt);
			hp.addValue("today", today);
			hp.addValue("inperid", userId == null ? "" : userId);

			// 고정값은 파워빌더 wf_save 그대로: 매입카드 '03' / 자산 'IG' / 부가세포함 '1' / 전자 'OD' / 영세율비해당 '211' / 내수 '0'
			int h = sqlRunner.execute("""
					INSERT INTO TB_CA640 (custcd, spjangcd, mijgubun, mijdate, mijnum, remark, gubun, artcd,
					                      cltcd, cltnm, jsflag, billkind, bhflag, taxreclafi, osflag, cdflag,
					                      divicd, yyyymm, acccd, mijcltcd, mijcltnm, accyn, cardco, cardnm, cardnum,
					                      bigo, inperid, indate, schdate, mijamt, taxcls)
					VALUES (:custcd, :spjangcd, '0', :mijdate, :mijnum, :remark, '03', :artcd,
					        :cltcd, :cltnm, 'IG', '1', 'OD', :taxreclafi, '211', '0',
					        :divicd, :yyyymm, :acccd, :cardco, :cardnm, '0', :cardco, :cardnm, :cardnum,
					        :bigo, :inperid, :today, :mijdate, :mijamt, '01')
					""", hp);
			if (h == 0) throw new IllegalStateException("승인번호 " + apvNo + " 의 매입 생성에 실패했습니다.");

			MapSqlParameterSource dp = base(spjangcd, custcd);
			dp.addValue("mijdate", apvDt);
			dp.addValue("mijnum", mijnum);
			dp.addValue("cltcd", str(r.get("cltcd")));
			dp.addValue("remark", str(r.get("summy")));
			dp.addValue("uamt", cardAmt);
			dp.addValue("samt", sply);
			dp.addValue("tamt", vat);
			dp.addValue("mijamt", cardAmt);
			dp.addValue("today", today);
			dp.addValue("inperid", userId == null ? "" : userId);

			int d = sqlRunner.execute("""
					INSERT INTO TB_CA641 (custcd, spjangcd, cltcd, mijdate, mijnum, seq, accdate, remark,
					                      qty, uamt, samt, tamt, mijamt, cardindate, indate, inperid)
					VALUES (:custcd, :spjangcd, :cltcd, :mijdate, :mijnum, '001', :mijdate, :remark,
					        1, :uamt, :samt, :tamt, :mijamt, :today, :today, :inperid)
					""", dp);
			if (d == 0) throw new IllegalStateException("승인번호 " + apvNo + " 의 매입 상세 생성에 실패했습니다.");

			kp.addValue("mijdate", apvDt);
			kp.addValue("mijnum", mijnum);
			int u = sqlRunner.execute("""
					UPDATE TB_bank_cdsave
					   SET flag = '1', mijdate = :mijdate, mijnum = :mijnum, spdate = '', spnum = ''
					 WHERE custcd = :custcd AND spjangcd = :spjangcd AND bnkcode = :bnkcode AND biz_no = :biz_no
					   AND ISNULL(flag, '0') <> '1'
					""", kp);
			if (u == 0) throw new IllegalStateException("승인번호 " + apvNo + " 의 처리 표시에 실패했습니다.");

			done++;
		}
		log.info("[카드 비용처리] {}건 (spjangcd={})", done, spjangcd);
		return done;
	}

	// ────────────────────────────────────────────────────────────
	//  비용취소 (wf_cancel)
	// ────────────────────────────────────────────────────────────

	@Transactional
	public int cancel(String spjangcd, List<Map<String, Object>> keys) {
		String custcd = requireCustcd(spjangcd);
		if (keys == null || keys.isEmpty()) throw new IllegalStateException("비용취소할 내역을 선택해주세요.");

		int done = 0;
		for (Map<String, Object> k : keys) {
			MapSqlParameterSource kp = keyParam(spjangcd, custcd, k);

			Map<String, Object> r = sqlRunner.getRow("""
					SELECT ISNULL(a.flag, '0') AS flag, ISNULL(a.mijdate, '') AS mijdate, ISNULL(a.mijnum, '') AS mijnum,
					       ISNULL(a.spdate, '') AS spdate, ISNULL(a.apv_no, '') AS apv_no
					  FROM TB_bank_cdsave a WITH(UPDLOCK)
					 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND a.bnkcode = :bnkcode AND a.biz_no = :biz_no
					""", kp);
			if (r == null) throw new IllegalStateException("카드 사용내역을 찾을 수 없습니다.");

			String apvNo = str(r.get("apv_no"));
			if (!"1".equals(str(r.get("flag")))) {
				throw new IllegalStateException("승인번호 " + apvNo + " 은(는) 비용처리된 내역이 아닙니다.");
			}

			String mijdate = str(r.get("mijdate"));
			String mijnum = str(r.get("mijnum"));

			if (!mijdate.isBlank() && !mijnum.isBlank()) {
				MapSqlParameterSource mp = base(spjangcd, custcd);
				mp.addValue("mijdate", mijdate);
				mp.addValue("mijnum", mijnum);

				Map<String, Object> chk = sqlRunner.getRow("""
						SELECT (SELECT COUNT(*) FROM TB_CA642 WITH(NOLOCK)
						         WHERE custcd = :custcd AND spjangcd = :spjangcd
						           AND mijdate = :mijdate AND mijnum = :mijnum) AS paid,
						       (SELECT TOP 1 ISNULL(tax_spdate, '') + '|' + ISNULL(acc_spdate, '') FROM TB_CA640 WITH(NOLOCK)
						         WHERE custcd = :custcd AND spjangcd = :spjangcd
						           AND mijdate = :mijdate AND mijnum = :mijnum) AS slip
						""", mp);
				if (chk == null) throw new IllegalStateException("매입 상태를 확인하지 못했습니다.");

				if (((Number) chk.get("paid")).intValue() > 0) {
					throw new IllegalStateException("승인번호 " + apvNo + " 의 매입은 카드지급이 끝나 취소할 수 없습니다. 카드지급을 먼저 취소하세요.");
				}
				// 파워빌더가 부가세 자료·회계전표까지 만든 건은 웹이 그 자료를 지우지 않으므로 여기서 막는다
				String slip = str(chk.get("slip"));
				if (!str(r.get("spdate")).isBlank() || (!slip.isBlank() && !"|".equals(slip))) {
					throw new IllegalStateException("승인번호 " + apvNo + " 은(는) 부가세 자료나 회계전표가 있어 웹에서 취소할 수 없습니다.");
				}

				sqlRunner.execute("""
						DELETE FROM TB_CA641_PCODE WHERE custcd = :custcd AND spjangcd = :spjangcd
						   AND mijdate = :mijdate AND mijnum = :mijnum
						""", mp);
				sqlRunner.execute("""
						DELETE FROM TB_CA641 WHERE custcd = :custcd AND spjangcd = :spjangcd
						   AND mijdate = :mijdate AND mijnum = :mijnum
						""", mp);
				sqlRunner.execute("""
						DELETE FROM TB_CA640 WHERE custcd = :custcd AND spjangcd = :spjangcd
						   AND mijdate = :mijdate AND mijnum = :mijnum
						""", mp);
			}

			int u = sqlRunner.execute("""
					UPDATE TB_bank_cdsave
					   SET flag = '0', mijdate = '', mijnum = '', spdate = '', spnum = ''
					 WHERE custcd = :custcd AND spjangcd = :spjangcd AND bnkcode = :bnkcode AND biz_no = :biz_no
					""", kp);
			if (u == 0) throw new IllegalStateException("승인번호 " + apvNo + " 의 처리 표시 해제에 실패했습니다.");
			done++;
		}
		log.info("[카드 비용취소] {}건 (spjangcd={})", done, spjangcd);
		return done;
	}

	private MapSqlParameterSource keyParam(String spjangcd, String custcd, Map<String, Object> r) {
		String bnkcode = str(r.get("bnkcode"));
		String bizNo = str(r.get("biz_no"));
		if (bnkcode.isBlank() || bizNo.isBlank()) throw new IllegalStateException("카드 사용내역 키가 없습니다.");
		MapSqlParameterSource p = base(spjangcd, custcd);
		p.addValue("bnkcode", bnkcode);
		p.addValue("biz_no", bizNo);
		return p;
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
