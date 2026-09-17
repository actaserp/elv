package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 신용카드 등록 (사업체 DB tb_iz010)
 *
 * 파워빌더가 쓰는 결제계좌 컬럼은 세 개로 나뉘어 있다.
 *   stlacc    = 결제 계좌번호 (TB_AA040.accnum 과 같은 값)
 *   stlbanknm = 결제 은행명
 *   stlbank   = 은행코드 6자리 (경기 실데이터: bank '03' → '030000')
 * 예전 코드는 stlbanknm 에 계좌번호를 넣고 그 값으로 TB_AA040 을 조인해서,
 * 파워빌더가 넣은 카드는 목록에 한 건도 나오지 않았다.
 */
@Slf4j
@Service
public class ManageCreditCardService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getList(String txtcardnm, String txtcardnum) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("spjangcd", TenantContext.get());

		// 결제계좌·카드사가 비어 있거나 코드표에 없는 카드도 목록에서 빠지면 안 되므로 전부 LEFT 조인.
		// 성명은 파워빌더가 cardperid 쪽에 이름을 넣어둔 데이터가 있어 cardperson 이 비면 그 값을 쓴다.
		String sql = """
			SELECT
				a.cardnum,
				a.cardnm,
				a.cardco,
				a.cardclafi,
				a.isudate,
				a.expedate,
				a.stldate,
				a.useyn,
				a.cdflag   AS baroflag,
				a.baroid,
				a.remark,
				a.stlbank  AS bankid,
				a.stlacc   AS accnum,
				ISNULL(NULLIF(a.stlbanknm, ''), acc.banknm) AS banknm,
				ISNULL(NULLIF(a.cardperson, ''), a.cardperid) AS cdpernm,
				a.cardperid AS cdperid,
				a.cardid   AS cardwebid,
				a.cardpw   AS cardwebpw,
				ISNULL(a.usestdate, '')  AS usestdate,
				ISNULL(a.connection, '') AS connection,
				d.cdcode   AS barocd
			  FROM tb_iz010 a
			  OUTER APPLY (SELECT TOP 1 b.banknm
			                 FROM tb_aa040 b
			                WHERE b.custcd = a.custcd AND b.accnum = a.stlacc) acc
			  LEFT JOIN tb_xcard d ON d.cd = a.cardco
			 WHERE a.spjangcd = :spjangcd
			""";

		if (txtcardnm != null && !txtcardnm.isEmpty()) {
			sql += " AND a.cardnm LIKE :txtcardnm ";
			param.addValue("txtcardnm", "%" + txtcardnm + "%");
		}

		if (txtcardnum != null && !txtcardnum.isEmpty()) {
			sql += " AND REPLACE(REPLACE(a.cardnum, '-', ''), ' ', '') LIKE :txtcardnum ";
			param.addValue("txtcardnum", "%" + txtcardnum.replace("-", "").replace(" ", "") + "%");
		}

		sql += " ORDER BY a.useyn DESC, a.cardnum ";

		return sqlRunner.getRows(sql, param);
	}

	/** 결제계좌 선택 목록 (사업체 계좌 TB_AA040) */
	public List<Map<String, Object>> getAccountList() {
		String custcd = getBizInfoBySpjangcd(TenantContext.get()).get("custcd");

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);

		return sqlRunner.getRows("""
			SELECT a.accnum,
			       ISNULL(a.bank, '')   AS bank,
			       ISNULL(a.banknm, '') AS banknm,
			       RTRIM(ISNULL(a.banknm, '')) + ' ' + a.accnum AS label
			  FROM tb_aa040 a
			 WHERE a.custcd = :custcd
			   AND ISNULL(a.accnum, '') <> ''
			   AND ISNULL(a.useyn, '1') = '1'
			 ORDER BY a.bank, a.bankcd
			""", param);
	}

	@Transactional
	public void save(Map<String, Object> param) {

		String spjangcd = TenantContext.get();
		String custcd = getBizInfoBySpjangcd(spjangcd).get("custcd");

		String cardnum = str(param.get("cardnum")).replace("-", "").replace(" ", "");
		if (cardnum.isEmpty()) {
			throw new IllegalStateException("카드번호를 입력해주세요.");
		}

		String accnum = str(param.get("ACCNUM"));

		// 은행명·은행코드는 화면 값을 믿지 않고 선택한 계좌에서 가져온다.
		Map<String, Object> acc = null;
		if (!accnum.isEmpty()) {
			MapSqlParameterSource accParam = new MapSqlParameterSource();
			accParam.addValue("custcd", custcd);
			accParam.addValue("accnum", accnum);
			acc = sqlRunner.getRow("""
				SELECT TOP 1 ISNULL(bank, '') AS bank, ISNULL(banknm, '') AS banknm
				  FROM tb_aa040
				 WHERE custcd = :custcd AND accnum = :accnum
				""", accParam);
		}

		String bank   = acc == null ? "" : str(acc.get("bank")).trim();
		String banknm = acc == null ? str(param.get("BANKNM")) : str(acc.get("banknm")).trim();
		// 파워빌더 실데이터가 bank '03' → '030000' 한 가지뿐이라 같은 형태로 만든다.
		// 계좌를 바꾸지 않은 수정에서는 기존 값을 그대로 둔다(UPDATE 문 참고).
		String stlbank = bank.isEmpty() ? "" : bank + "0000";

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("custcd", custcd);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("cardnum", cardnum);
		dicParam.addValue("cardnm", param.get("cardnm"));
		dicParam.addValue("cardco", param.get("cardco"));
		dicParam.addValue("cardclafi", param.get("cardType"));
		dicParam.addValue("isudate", param.get("regAsName"));
		dicParam.addValue("expedate", param.get("expdate"));
		dicParam.addValue("stldate", param.get("stldate"));
		dicParam.addValue("useyn", param.get("useYn"));
		dicParam.addValue("cdflag", param.get("baroflag"));
		dicParam.addValue("stlacc", accnum);
		dicParam.addValue("stlbanknm", banknm);
		dicParam.addValue("stlbank", stlbank);
		dicParam.addValue("remark", param.get("remark"));
		dicParam.addValue("cardid", param.get("cardwebid"));
		dicParam.addValue("cardpw", param.get("cardwebpw"));
		dicParam.addValue("baroid", param.get("baroid"));
		// 파워빌더 화면에 있는데 예전 웹 화면에 없던 칸 (성명·정지일자·제출자와의 관계)
		dicParam.addValue("cardperson", str(param.get("cardperson")));
		dicParam.addValue("usestdate", str(param.get("usestdate")).replace("-", ""));
		dicParam.addValue("connection", str(param.get("connection")));

		// 기존 레코드 존재 여부 (PK: custcd + spjangcd + cardnum)
		// 예전 코드는 COUNT(*) 에 별칭이 없어 get("cnt") 가 null → 저장이 항상 실패했다.
		Map<String, Object> exists = sqlRunner.getRow("""
			SELECT COUNT(*) AS cnt
			  FROM tb_iz010
			 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cardnum = :cardnum
			""", dicParam);
		int count = exists == null ? 0 : ((Number) exists.get("cnt")).intValue();

		// SqlRunner.execute 는 SQL 오류를 삼키고 0 을 돌려준다. 0 이면 실패로 본다.
		int affected;
		if (count > 0) {
			// 성명은 cardperson 에만 쓴다. 경기 자료는 이름이 cardperid 에 들어 있어 그 값은 건드리지 않는다
			// (화면은 cardperson 이 비면 cardperid 를 보여주므로 비워도 이름이 사라지지 않는다).
			affected = this.sqlRunner.execute("""
				UPDATE tb_iz010
				   SET cardnm    = :cardnm,
				       cardco    = :cardco,
				       cardclafi = :cardclafi,
				       isudate   = :isudate,
				       expedate  = :expedate,
				       stldate   = :stldate,
				       useyn     = :useyn,
				       cdflag    = :cdflag,
				       stlbank   = CASE WHEN ISNULL(stlacc, '') = :stlacc AND ISNULL(stlbank, '') <> ''
				                        THEN stlbank ELSE :stlbank END,
				       stlacc    = :stlacc,
				       stlbanknm = :stlbanknm,
				       remark    = :remark,
				       cardid    = :cardid,
				       cardpw    = :cardpw,
				       baroid    = :baroid,
				       cardperson = :cardperson,
				       usestdate = :usestdate,
				       connection = :connection
				 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cardnum = :cardnum
				""", dicParam);
		} else {
			affected = this.sqlRunner.execute("""
				INSERT INTO tb_iz010 (
				    custcd, spjangcd, cardnum,
				    cardnm, cardco, cardclafi,
				    isudate, expedate, stldate,
				    useyn, cdflag,
				    stlbank, stlacc, stlbanknm,
				    remark, cardid, cardpw, baroid,
				    cardperson, usestdate, connection
				) VALUES (
				    :custcd, :spjangcd, :cardnum,
				    :cardnm, :cardco, :cardclafi,
				    :isudate, :expedate, :stldate,
				    :useyn, :cdflag,
				    :stlbank, :stlacc, :stlbanknm,
				    :remark, :cardid, :cardpw, :baroid,
				    :cardperson, :usestdate, :connection
				)
				""", dicParam);
		}
		if (affected == 0) {
			throw new IllegalStateException("저장 중 오류가 발생했습니다. 입력값(날짜·결제일 자릿수 등)을 확인해주세요.");
		}
	}

	/**
	 * 카드 삭제. 카드 사용내역(TB_bank_cdsave)이 남아 있으면 막는다.
	 * 경기는 사용 중인 카드마다 110~130건씩 내역이 연결돼 있어, 지우면 내역만 남고 카드 정보가 사라진다.
	 */
	@Transactional
	public void delete(String cardnum) {
		String spjangcd = TenantContext.get();
		String custcd = getBizInfoBySpjangcd(spjangcd).get("custcd");

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd);
		p.addValue("spjangcd", spjangcd);
		p.addValue("cardnum", str(cardnum).replace("-", "").replace(" ", ""));

		Map<String, Object> hist = sqlRunner.getRow("""
			SELECT COUNT(*) AS cnt
			  FROM TB_bank_cdsave
			 WHERE custcd = :custcd
			   AND REPLACE(REPLACE(card_no, '-', ''), ' ', '') = :cardnum
			""", p);
		// 조회 실패(null)를 0건으로 보면 내역이 있는 카드가 지워진다. 실패면 삭제하지 않는다.
		if (hist == null) {
			throw new IllegalStateException("카드 사용내역을 확인하지 못해 삭제하지 않았습니다.");
		}
		int histCnt = ((Number) hist.get("cnt")).intValue();
		if (histCnt > 0) {
			throw new IllegalStateException(
					"카드 사용내역 " + histCnt + "건이 있어 삭제할 수 없습니다. 사용여부를 '미사용'으로 바꿔주세요.");
		}

		int deleted = sqlRunner.execute("""
			DELETE FROM tb_iz010
			 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cardnum = :cardnum
			""", p);
		if (deleted == 0) {
			throw new IllegalStateException("삭제할 카드를 찾을 수 없습니다.");
		}
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
        select saupnum, custcd
        from tb_xa012
        where spjangcd = :spjangcd
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		Map<String, String> result = new HashMap<>();
		result.put("saupnum", "");
		result.put("custcd", "");

		if (row == null || row.isEmpty()) {
			return result;
		}

		Object saupnum = row.get("saupnum");
		Object custcd = row.get("custcd");

		result.put("saupnum", saupnum == null ? "" : String.valueOf(saupnum).trim());
		result.put("custcd", custcd == null ? "" : String.valueOf(custcd).trim());

		return result;
	}
}
