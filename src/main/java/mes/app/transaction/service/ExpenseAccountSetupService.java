package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 비용항목등록 — 파워빌더 '비용항목등록'(w_tb_ca647)
 *
 * 왼쪽 그리드가 비용항목 대분류(TB_CA647), 오른쪽 그리드가 그 아래 세부항목(TB_CA648) 이다.
 * 둘 다 사업체 DB 에 있다.
 *
 * 예전 코드는 세 곳으로 흩어져 있었다.
 *   대분류 목록 → 본사 sys_code(CodeType='gartcd'), 전 사업체 공용
 *   세부 조회   → 사업체 TB_CA648
 *   세부 저장   → JPA(tb_ca648Repository) → 본사 tb_ca648   ※ 읽는 곳과 쓰는 곳이 달랐다
 * 코드 체계도 달라서(본사 세부코드 3자리 '204' / 사업체 4자리 '0204') 그룹을 고르면 엉뚱한 항목이 나왔다.
 *
 * 코드값
 *   jflag  고정비 구분 : 1 고정비 / 2 변동비
 *   gflag  손익항목분류 : TB_CA510 com_cls='802' (1 원재료비 / 2 인건비 / 3 활동비 / 4 유지비 / 5 기타경비)
 *   acccd 비용계정 · acccd2 원가계정 · antacccd 상대계정 — 이름은 계정과목(tb_ac001)에서 가져온다
 */
@Slf4j
@Service
public class ExpenseAccountSetupService {

  @Autowired
  SqlRunner sqlRunner;

  /** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
  public String getCustcd(String spjangcd) {
    MapSqlParameterSource param = new MapSqlParameterSource();
    param.addValue("spjangcd", spjangcd);
    Map<String, Object> row = sqlRunner.getRow(
            "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
    if (row == null || row.get("custcd") == null) return null;
    return String.valueOf(row.get("custcd")).trim();
  }

  private MapSqlParameterSource param(String spjangcd, String custcd) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("custcd", custcd);
    p.addValue("spjangcd", spjangcd);
    return p;
  }

  /** 왼쪽 그리드 — 비용항목 대분류 */
  public List<Map<String, Object>> getExpenseAccountList(String spjangcd, String keyword) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) return List.of();

    MapSqlParameterSource p = param(spjangcd, custcd);
    String sql = """
        SELECT gartcd AS code,
               gartnm AS group_name,
               ISNULL(remark, '') AS remark,
               ISNULL(useyn, '1') AS useyn,
               indate, inperid
          FROM TB_CA647 WITH(NOLOCK)
         WHERE custcd = :custcd AND spjangcd = :spjangcd
        """;

    if (keyword != null && !keyword.isEmpty()) {
      sql += " AND gartnm LIKE '%' + :keyword + '%' ";
      p.addValue("keyword", keyword);
    }
    sql += " ORDER BY gartcd ";

    return sqlRunner.getRows(sql, p);
  }

  /** 오른쪽 그리드 — 대분류 아래 세부항목 */
  public List<Map<String, Object>> getExpenseAccountDetail(String groupCode, String spjangcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) return List.of();

    MapSqlParameterSource p = param(spjangcd, custcd);
    p.addValue("gartcd", groupCode == null ? "" : groupCode.trim());

    // 계정 이름 세 가지는 계정과목(tb_ac001)에서, 비용분류 이름은 TB_CA510(802)에서 붙인다.
    String sql = """
        SELECT d.gartcd,
               d.artcd,
               ISNULL(d.artnm, '')    AS artnm,
               ISNULL(d.jflag, '')    AS jflag,
               ISNULL(d.gflag, '')    AS gflag,
               ISNULL(g.com_cnam, '') AS gflagnm,
               ISNULL(d.gamt, 0)      AS gamt,
               ISNULL(d.acccd, '')    AS acccd,
               ISNULL(a1.accnm, '')   AS accnm,
               ISNULL(d.acccd2, '')   AS acccd2,
               ISNULL(a2.accnm, '')   AS accnm2,
               ISNULL(d.antacccd, '') AS antacccd,
               ISNULL(a3.accnm, '')   AS antaccnm,
               ISNULL(d.useyn, '1')   AS useyn,
               d.indate, d.inperid
          FROM TB_CA648 d WITH(NOLOCK)
          LEFT JOIN tb_ac001 a1 WITH(NOLOCK) ON a1.custcd = d.custcd AND a1.acccd = d.acccd
          LEFT JOIN tb_ac001 a2 WITH(NOLOCK) ON a2.custcd = d.custcd AND a2.acccd = d.acccd2
          LEFT JOIN tb_ac001 a3 WITH(NOLOCK) ON a3.custcd = d.custcd AND a3.acccd = d.antacccd
          LEFT JOIN TB_CA510 g WITH(NOLOCK) ON g.com_cls = '802' AND g.com_code = d.gflag
         WHERE d.custcd = :custcd AND d.spjangcd = :spjangcd AND d.gartcd = :gartcd
         ORDER BY d.artcd
        """;

    return sqlRunner.getRows(sql, p);
  }

  /** 대분류 저장 (없으면 INSERT, 있으면 UPDATE) */
  public void saveGroup(String spjangcd, String gartcd, String gartnm, String remark, String useyn) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");
    if (gartcd == null || gartcd.isBlank()) throw new IllegalStateException("그룹코드를 입력해주세요.");

    MapSqlParameterSource p = param(spjangcd, custcd);
    p.addValue("gartcd", gartcd.trim());
    p.addValue("gartnm", gartnm == null ? "" : gartnm);
    p.addValue("remark", remark == null ? "" : remark);
    p.addValue("useyn", (useyn == null || useyn.isBlank()) ? "1" : useyn);

    Map<String, Object> exists = sqlRunner.getRow("""
        SELECT COUNT(*) AS cnt FROM TB_CA647
         WHERE custcd = :custcd AND spjangcd = :spjangcd AND gartcd = :gartcd
        """, p);
    int cnt = exists == null ? 0 : ((Number) exists.get("cnt")).intValue();

    int affected;
    if (cnt > 0) {
      affected = sqlRunner.execute("""
          UPDATE TB_CA647
             SET gartnm = :gartnm, remark = :remark, useyn = :useyn
           WHERE custcd = :custcd AND spjangcd = :spjangcd AND gartcd = :gartcd
          """, p);
    } else {
      affected = sqlRunner.execute("""
          INSERT INTO TB_CA647 (custcd, spjangcd, gartcd, gartnm, remark, useyn, indate, inperid)
          VALUES (:custcd, :spjangcd, :gartcd, :gartnm, :remark, :useyn,
                  CONVERT(varchar(8), GETDATE(), 112), :inperid)
          """, withUser(p));
    }
    // SqlRunner 는 SQL 오류를 삼키고 0 을 돌려준다. 0 이면 실패로 본다.
    if (affected == 0) throw new IllegalStateException("그룹 저장에 실패했습니다.");
  }

  /** 세부항목 저장. artcd 가 비어 있으면 대분류코드 + 두 자리 순번으로 채번한다. */
  public void saveItem(String spjangcd, String gartcd, Map<String, Object> row, String userId) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

    String artcd = str(row.get("artcd")).trim();
    boolean isNew = artcd.isEmpty();
    if (isNew) artcd = nextArtcd(spjangcd, custcd, gartcd);

    MapSqlParameterSource p = param(spjangcd, custcd);
    p.addValue("gartcd", gartcd);
    p.addValue("artcd", artcd);
    p.addValue("artnm", str(row.get("artnm")));
    p.addValue("jflag", str(row.get("jflag")));
    p.addValue("gflag", str(row.get("gflag")));
    p.addValue("acccd", str(row.get("acccd")));
    p.addValue("acccd2", str(row.get("acccd2")));
    p.addValue("antacccd", str(row.get("antacccd")));
    p.addValue("inperid", userId == null ? "" : userId);

    Object useynObj = row.get("useyn");
    String useyn = (useynObj instanceof Boolean) ? (((Boolean) useynObj) ? "1" : "0") : str(useynObj);
    p.addValue("useyn", useyn.isEmpty() ? "1" : useyn);

    int affected;
    if (!isNew) {
      affected = sqlRunner.execute("""
          UPDATE TB_CA648
             SET artnm = :artnm, jflag = :jflag, gflag = :gflag,
                 acccd = :acccd, acccd2 = :acccd2, antacccd = :antacccd, useyn = :useyn
           WHERE custcd = :custcd AND spjangcd = :spjangcd AND gartcd = :gartcd AND artcd = :artcd
          """, p);
    } else {
      affected = sqlRunner.execute("""
          INSERT INTO TB_CA648 (custcd, spjangcd, gartcd, artcd, artnm, jflag, gflag,
                                acccd, acccd2, antacccd, useyn, indate, inperid)
          VALUES (:custcd, :spjangcd, :gartcd, :artcd, :artnm, :jflag, :gflag,
                  :acccd, :acccd2, :antacccd, :useyn, CONVERT(varchar(8), GETDATE(), 112), :inperid)
          """, p);
    }
    if (affected == 0) throw new IllegalStateException("항목 저장에 실패했습니다. (코드 " + artcd + ")");
  }

  /** 세부코드 채번 — 대분류코드(2자리) + 순번(2자리). 경기 기존 데이터도 같은 형태다. */
  private String nextArtcd(String spjangcd, String custcd, String gartcd) {
    MapSqlParameterSource p = param(spjangcd, custcd);
    p.addValue("gartcd", gartcd);
    Map<String, Object> row = sqlRunner.getRow("""
        SELECT ISNULL(MAX(CAST(RIGHT(artcd, 2) AS int)), 0) AS maxseq
          FROM TB_CA648 WITH(UPDLOCK, HOLDLOCK)
         WHERE custcd = :custcd AND spjangcd = :spjangcd AND gartcd = :gartcd
           AND LEN(artcd) = 4
        """, p);
    int next = (row == null || row.get("maxseq") == null) ? 1 : ((Number) row.get("maxseq")).intValue() + 1;
    return gartcd + String.format("%02d", next);
  }

  /** 세부항목 삭제. 매입에서 쓰인 항목은 막는다. */
  public void deleteItem(String spjangcd, String gartcd, String artcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

    MapSqlParameterSource p = param(spjangcd, custcd);
    p.addValue("gartcd", gartcd);
    p.addValue("artcd", artcd);

    Map<String, Object> used = sqlRunner.getRow("""
        SELECT COUNT(*) AS cnt FROM TB_CA640 WITH(NOLOCK)
         WHERE custcd = :custcd AND spjangcd = :spjangcd AND artcd = :artcd
        """, p);
    if (used == null) throw new IllegalStateException("사용 여부를 확인하지 못해 삭제하지 않았습니다.");
    int cnt = ((Number) used.get("cnt")).intValue();
    if (cnt > 0) {
      throw new IllegalStateException(
              "매입 " + cnt + "건에 쓰인 항목이라 삭제할 수 없습니다. 사용여부를 '미사용'으로 바꿔주세요.");
    }

    int deleted = sqlRunner.execute("""
        DELETE FROM TB_CA648
         WHERE custcd = :custcd AND spjangcd = :spjangcd AND gartcd = :gartcd AND artcd = :artcd
        """, p);
    if (deleted == 0) throw new IllegalStateException("삭제할 항목을 찾을 수 없습니다.");
  }

  /** 대분류 삭제. 아래 항목이 남아 있으면 막는다. */
  public void deleteGroup(String spjangcd, String gartcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

    MapSqlParameterSource p = param(spjangcd, custcd);
    p.addValue("gartcd", gartcd);

    Map<String, Object> items = sqlRunner.getRow("""
        SELECT COUNT(*) AS cnt FROM TB_CA648 WITH(NOLOCK)
         WHERE custcd = :custcd AND spjangcd = :spjangcd AND gartcd = :gartcd
        """, p);
    if (items == null) throw new IllegalStateException("하위 항목을 확인하지 못해 삭제하지 않았습니다.");
    int cnt = ((Number) items.get("cnt")).intValue();
    if (cnt > 0) {
      throw new IllegalStateException("하위 항목 " + cnt + "건을 먼저 정리해주세요.");
    }

    int deleted = sqlRunner.execute("""
        DELETE FROM TB_CA647
         WHERE custcd = :custcd AND spjangcd = :spjangcd AND gartcd = :gartcd
        """, p);
    if (deleted == 0) throw new IllegalStateException("삭제할 그룹을 찾을 수 없습니다.");
  }

  private MapSqlParameterSource withUser(MapSqlParameterSource p) {
    if (p.getValue("inperid") == null) p.addValue("inperid", "");
    return p;
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }
}
