package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class AttendanceSubmitService {
    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // 사용자 정보 조회 (personid 기준 - username 변경에 무관)
    // TB_PB209(연차현황) JOIN 제거 - read_userInfo 진입 시점에는 불필요
    public Map<String, Object> getUserInfo(int personId) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personId", personId);

        String sql = """
                SELECT TOP 1
                          p.id,
                          p.name AS first_name,
                          t.sttime
                      FROM person p
                      LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                      WHERE p.id = :personId
                """;

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    // 결재구분별 결재라인 및 정보 조회(결재자 직원코드)
    public List<Map<String, Object>> getAppInfoList(Integer personid) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personid", personid);

        String sql = """
                SELECT
                      *
                  FROM tb_e064 e
                  WHERE e.papercd = '301'
                  AND e.perid = :personid
                  ORDER BY e.SEQ ASC
        		""";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    // 휴가항목 선택시 근태설정 고정값있는지 확인
    public Map<String, Object> getPeriod(String attKind) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("attKind", attKind);

        String sql = """
                SELECT
                      yearflag,
                      usenum
                  FROM tb_pb210
                  WHERE workcd = :attKind
        		""";

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    /**
     * [기존] TB_PB204Repository.save(tbPb204) - 1차 저장 (ID 채번용)
     * → tb_pb204 테이블에 휴가 신청 데이터를 INSERT하고, 생성된 PK(id)를 반환
     *
     * DDL 기준 컬럼 타입:
     *   appperid  varchar(10)  → String
     *   appuserid varchar(50)  → String
     *   daynum    decimal(5,2) → BigDecimal
     */
    public long insertTbPb204(String spjangcd, String reqdate, String perid,
                              String frdate, String sttime, String todate, String edtime,
                              BigDecimal daynum, String workcd, String remark,
                              String appdate, String appperid, String appuserid, String yearflag) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("reqdate", reqdate);
        param.addValue("perid", perid);
        param.addValue("frdate", frdate);
        param.addValue("sttime", sttime);
        param.addValue("todate", todate);
        param.addValue("edtime", edtime);
        param.addValue("daynum", daynum);
        param.addValue("workcd", workcd);
        param.addValue("remark", remark);
        param.addValue("appdate", appdate);
        param.addValue("appgubun", "001");
        param.addValue("appperid", appperid);
        param.addValue("appuserid", appuserid);
        param.addValue("yearflag", yearflag);

        String sql = """
                INSERT INTO tb_pb204
                    (spjangcd, reqdate, perid, frdate, sttime, todate, edtime,
                     daynum, workcd, remark, appdate, appgubun, appperid, appuserid, yearflag)
                VALUES
                    (:spjangcd, :reqdate, :perid, :frdate, :sttime, :todate, :edtime,
                     :daynum, :workcd, :remark, :appdate, :appgubun, :appperid, :appuserid, :yearflag)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update(sql, param, keyHolder);

        return keyHolder.getKey().longValue();
    }

    /**
     * [기존] TB_PB204Repository.save(tbPb204) - 2차 저장 (appnum 업데이트용)
     * → 채번된 ID로 생성한 appnum을 tb_pb204에 UPDATE
     */
    public void updateTbPb204Appnum(long id, String appnum) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("id", id);
        param.addValue("appnum", appnum);

        String sql = """
                UPDATE tb_pb204
                SET appnum = :appnum
                WHERE id = :id
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    /**
     * [기존] E080Repository.save(e080Info) - 결재라인 루프 저장
     * → tb_e080 테이블에 결재라인 1건씩 INSERT (루프에서 반복 호출)
     *
     * DDL 기준 컬럼 타입:
     *   perid     varchar(10) → kcperid(결재자 직원코드) 값을 perid 컬럼에 INSERT
     *   repoperid varchar(10) → String
     *   inperid   varchar(10) → String
     */
    public void insertTbE080(String spjangcd, String appnum, String kcperid, String seq,
                             String title, String flag, String repoperid, String appgubun,
                             String papercd, String inperid, String indate, String gubun) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("appnum", appnum);
        param.addValue("kcperid", kcperid);
        param.addValue("seq", seq);
        param.addValue("title", title);
        param.addValue("flag", flag);
        param.addValue("repoperid", repoperid);
        param.addValue("appgubun", appgubun);
        param.addValue("papercd", papercd);
        param.addValue("inperid", inperid);
        param.addValue("indate", indate);
        param.addValue("gubun", gubun);

        String sql = """
                INSERT INTO tb_e080
                    (spjangcd, appnum, perid, appperid, seq, title, flag, repoperid, appgubun,
                     papercd, inperid, indate, gubun)
                VALUES
                    (:spjangcd, :appnum, :kcperid, :kcperid, :seq, :title, :flag, :repoperid, :appgubun,
                     :papercd, :inperid, :indate, :gubun)
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }
}
