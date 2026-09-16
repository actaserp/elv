package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 계좌번호 관리 — 파워빌더 w_s004 / d_s004 (사업체 DB TB_AA040)
 *
 * 파워빌더 화면의 컬럼 순서 그대로다.
 *   금융기관 bank · 금융기관명 banknm · 관리코드 'B'+bankcd5 · 계좌번호 accnum ·
 *   계좌명칭 ACCNAME · 시작일 contdate · 만기일 expedate · 지급수수료 mijamt ·
 *   이율 intrate · 계정 acccd(+TB_AC001.accnm) · 구분 bankflag · 기본 basic ·
 *   지로계좌 jiroflag · 지로아이디 cmsid · PW cmspw · CMS cmsflag · 카드계좌 cardflag · 사용 useyn
 *
 * 예전 코드가 파워빌더와 어긋나 있던 곳
 *   · 계좌명칭을 banknm(은행명)에서 읽고 ACCNAME 에 저장했다 → 화면에 늘 '우리은행'이 떴다
 *   · 지급수수료를 이율 컬럼(intrate)에 저장했다. 파워빌더는 mijamt 가 지급수수료다
 *   · 계좌유형을 spacc 에서 읽고 acccd 에 저장했다. spacc 는 경기 14건 전부 비어 있다
 *   · 인터넷뱅킹ID·조회ID·조회PW·지급수수료를 저장은 하는데 목록 SELECT 에 없어 늘 빈칸이었다
 *   · 삭제 API 가 빈 껍데기였는데 AjaxResult.success 기본값이 true 라 '삭제되었습니다'만 떴다
 */
@Slf4j
@Service
public class BankManagementService {

	@Autowired
	SqlRunner sqlRunner;

	/** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
	public String getCustcd(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		Map<String, Object> row = sqlRunner.getRow(
				"SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", p);
		if (row == null || row.get("custcd") == null) return null;
		return String.valueOf(row.get("custcd")).trim();
	}

	/**
	 * 계좌 목록. 파워빌더는 은행명(banknm)만으로 찾지만 화면에 계좌번호 검색칸이 있어 같이 받는다.
	 * 파워빌더 원본은 TB_AC001 과 INNER JOIN 이라 계정이 비면 행이 빠진다. 여기서는 LEFT 로 둔다.
	 */
	public List<Map<String, Object>> getAccountList(String bankid, String accnum, String spjangcd) {
		String custcd = getCustcd(spjangcd);
		if (custcd == null) return List.of();

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("bankid", bankid == null ? "" : bankid.trim());
		param.addValue("accnum", accnum == null ? "" : accnum.trim().replace("-", ""));

		String sql = """
				SELECT a.bank,
				       ISNULL(x.banknm, ISNULL(a.banknm, '')) AS bankname,
				       a.bankcd,
				       ISNULL(a.bankcd5, '')   AS bankcd5,
				       'B' + ISNULL(a.bankcd5, '') AS mgmtcd,
				       ISNULL(a.accnum, '')    AS accountNumber,
				       ISNULL(a.ACCNAME, '')   AS accountName,
				       ISNULL(a.contdate, '')  AS contdate,
				       ISNULL(a.expedate, '')  AS expedate,
				       ISNULL(a.mijamt, 0)     AS mijamt,
				       ISNULL(a.intrate, 0)    AS intrate,
				       ISNULL(a.acccd, '')     AS acccd,
				       ISNULL(c.accnm, '')     AS accnm,
				       ISNULL(a.bankflag, '')  AS bankflag,
				       ISNULL(a.basic, '')     AS basic,
				       ISNULL(a.jiroflag, '')  AS jiroflag,
				       ISNULL(a.cmsflag, '')   AS cmsflag,
				       ISNULL(a.cardflag, '')  AS cardflag,
				       ISNULL(a.useyn, '')     AS useyn,
				       ISNULL(a.cmsid, '')     AS cmsid,
				       ISNULL(a.cmspw, '')     AS cmspw,
				       ISNULL(a.bnkid, '')     AS bnkid,
				       ISNULL(a.bnkpw, '')     AS bnkpw,
				       ISNULL(a.bnkpaypw, '')  AS accountPw
				  FROM TB_AA040 a WITH(NOLOCK)
				  LEFT JOIN TB_AC001 c WITH(NOLOCK) ON c.custcd = a.custcd AND c.acccd = a.acccd
				  LEFT JOIN tb_xbank x WITH(NOLOCK) ON x.bankcd = a.bank
				 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
				   AND (:bankid = '' OR a.bank = :bankid)
				   AND (:accnum = '' OR REPLACE(ISNULL(a.accnum, ''), '-', '') LIKE '%' + :accnum + '%')
				 ORDER BY a.bank, a.bankcd
				""";

		return sqlRunner.getRows(sql, param);
	}

	/**
	 * 저장. 키는 TB_AA040 의 PK 인 (custcd, bank, bankcd) 다.
	 * 신규면 그 은행의 다음 관리코드를 뽑아 'B' + 2자리로 만든다 (경기 실데이터가 B01~B14 형태).
	 */
	@Transactional
	public String save(Map<String, Object> param, String spjangcd) {
		String custcd = getCustcd(spjangcd);
		if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

		String bank = str(param.get("bankid")).trim();
		if (bank.isEmpty()) throw new IllegalStateException("금융기관을 선택해주세요.");

		String accountNumber = str(param.get("accountNumber")).trim();
		if (accountNumber.isEmpty()) throw new IllegalStateException("계좌번호를 입력해주세요.");

		String bankcd = str(param.get("bankcd")).trim();
		boolean isNew = bankcd.isEmpty();
		if (isNew) bankcd = nextBankcd(custcd, bank);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd);
		p.addValue("spjangcd", spjangcd);
		p.addValue("bank", bank);
		p.addValue("bankcd", bankcd);
		p.addValue("bankcd5", bankcd.startsWith("B") ? bankcd.substring(1) : bankcd);
		p.addValue("accnum", accountNumber);
		p.addValue("accname", str(param.get("accountName")));
		p.addValue("banknm", str(param.get("bankname")));
		p.addValue("contdate", str(param.get("contdate")).replace("-", ""));
		p.addValue("expedate", str(param.get("expedate")).replace("-", ""));
		p.addValue("acccd", str(param.get("acccd")));
		p.addValue("bankflag", str(param.get("bankflag")));
		p.addValue("basic", flag(param.get("basic")));
		p.addValue("jiroflag", flag(param.get("jiroflag")));
		p.addValue("cmsflag", flag(param.get("cmsflag")));
		p.addValue("cardflag", flag(param.get("cardflag")));
		p.addValue("useyn", flag(param.get("useyn")));
		p.addValue("cmsid", str(param.get("cmsid")));
		p.addValue("cmspw", str(param.get("cmspw")));
		p.addValue("bnkid", str(param.get("bnkid")));
		p.addValue("bnkpw", str(param.get("bnkpw")));
		p.addValue("bnkpaypw", str(param.get("accountPw")));
		// 파워빌더 기준 지급수수료는 mijamt, 이율은 intrate 다
		p.addValue("mijamt", num(param.get("mijamt"), "지급수수료"));
		p.addValue("intrate", num(param.get("intrate"), "이율"));

		Map<String, Object> exists = sqlRunner.getRow("""
				SELECT COUNT(*) AS cnt FROM TB_AA040 WITH(NOLOCK)
				 WHERE custcd = :custcd AND bank = :bank AND bankcd = :bankcd
				""", p);
		if (exists == null) throw new IllegalStateException("계좌 정보를 확인하지 못했습니다.");

		int affected;
		if (((Number) exists.get("cnt")).intValue() > 0) {
			affected = sqlRunner.execute("""
					UPDATE TB_AA040
					   SET spjangcd = :spjangcd, bankcd5 = :bankcd5, accnum = :accnum,
					       ACCNAME = :accname, banknm = :banknm,
					       contdate = :contdate, expedate = :expedate,
					       mijamt = :mijamt, intrate = :intrate, acccd = :acccd,
					       bankflag = :bankflag, basic = :basic, jiroflag = :jiroflag,
					       cmsflag = :cmsflag, cardflag = :cardflag, useyn = :useyn,
					       cmsid = :cmsid, cmspw = :cmspw,
					       bnkid = :bnkid, bnkpw = :bnkpw, bnkpaypw = :bnkpaypw
					 WHERE custcd = :custcd AND bank = :bank AND bankcd = :bankcd
					""", p);
		} else {
			affected = sqlRunner.execute("""
					INSERT INTO TB_AA040
					      (custcd, spjangcd, bank, bankcd, bankcd5, accnum, ACCNAME, banknm,
					       contdate, expedate, mijamt, intrate, acccd,
					       bankflag, basic, jiroflag, cmsflag, cardflag, useyn,
					       cmsid, cmspw, bnkid, bnkpw, bnkpaypw)
					VALUES (:custcd, :spjangcd, :bank, :bankcd, :bankcd5, :accnum, :accname, :banknm,
					        :contdate, :expedate, :mijamt, :intrate, :acccd,
					        :bankflag, :basic, :jiroflag, :cmsflag, :cardflag, :useyn,
					        :cmsid, :cmspw, :bnkid, :bnkpw, :bnkpaypw)
					""", p);
		}
		// SqlRunner.execute 는 SQL 오류를 삼키고 0 을 돌려준다. 0 이면 실패로 본다
		if (affected == 0) throw new IllegalStateException("계좌 저장에 실패했습니다. 입력값을 확인해주세요.");

		return bankcd;
	}

	/** 관리코드 채번 — 그 은행의 B01, B02 … 다음 번호 */
	private String nextBankcd(String custcd, String bank) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd);
		p.addValue("bank", bank);

		Map<String, Object> row = sqlRunner.getRow("""
				SELECT ISNULL(MAX(TRY_CAST(REPLACE(bankcd, 'B', '') AS int)), 0) + 1 AS nextnum
				  FROM TB_AA040 WITH(UPDLOCK, HOLDLOCK)
				 WHERE custcd = :custcd AND bank = :bank
				""", p);
		if (row == null) throw new IllegalStateException("관리코드를 채번하지 못했습니다.");

		int next = ((Number) row.get("nextnum")).intValue();
		if (next > 99) throw new IllegalStateException("이 금융기관의 관리코드를 모두 사용했습니다.");
		return String.format("B%02d", next);
	}

	/**
	 * 삭제. 신용카드(tb_iz010)의 결제계좌로 쓰이고 있으면 막는다.
	 * 지우면 카드 쪽 결제계좌가 끊긴 채로 남는다.
	 */
	@Transactional
	public void delete(String bank, String bankcd, String accnum, String spjangcd) {
		String custcd = getCustcd(spjangcd);
		if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd);
		p.addValue("spjangcd", spjangcd);
		p.addValue("bank", str(bank).trim());
		p.addValue("bankcd", str(bankcd).trim());
		p.addValue("accnum", str(accnum).trim());

		if (str(bank).isBlank() || str(bankcd).isBlank()) {
			throw new IllegalStateException("삭제할 계좌를 먼저 선택해주세요.");
		}

		Map<String, Object> used = sqlRunner.getRow("""
				SELECT COUNT(*) AS cnt FROM tb_iz010 WITH(NOLOCK)
				 WHERE custcd = :custcd AND ISNULL(stlacc, '') = :accnum AND ISNULL(stlacc, '') <> ''
				""", p);
		// 조회 실패(null)를 0건으로 보면 쓰이고 있는 계좌가 지워진다
		if (used == null) throw new IllegalStateException("카드 결제계좌 사용여부를 확인하지 못해 삭제하지 않았습니다.");
		int cnt = ((Number) used.get("cnt")).intValue();
		if (cnt > 0) {
			throw new IllegalStateException(
					"신용카드 " + cnt + "건의 결제계좌로 쓰이고 있어 삭제할 수 없습니다. 사용여부를 끄고 쓰세요.");
		}

		int deleted = sqlRunner.execute("""
				DELETE FROM TB_AA040
				 WHERE custcd = :custcd AND bank = :bank AND bankcd = :bankcd
				""", p);
		if (deleted == 0) throw new IllegalStateException("삭제할 계좌를 찾을 수 없습니다.");
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	/** 체크박스 값을 '1'/'0' 으로 맞춘다 */
	private static String flag(Object o) {
		String s = str(o).trim();
		return ("1".equals(s) || "true".equalsIgnoreCase(s) || "Y".equalsIgnoreCase(s)) ? "1" : "0";
	}

	private static double num(Object o, String label) {
		String s = str(o).replace(",", "").trim();
		if (s.isEmpty()) return 0;
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			throw new IllegalStateException(label + " 형식이 올바르지 않습니다.");
		}
	}
}
