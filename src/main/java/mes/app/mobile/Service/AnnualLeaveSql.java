package mes.app.mobile.Service;

/**
 * 모바일 화면에 잔여연차를 띄울 때 쓰는 공통 조각.
 *
 * 예전에는 네 화면이 제각기 `TRY_CAST(tb_pb209.perid AS INT) = auth_user.personid` 로 붙였는데,
 * TB_PB209.perid 는 'p001' 형식이라 캐스팅이 늘 NULL 이 되어 **한 건도 안 붙었다**.
 * 그래서 모바일 잔여연차가 계속 비어 있었다. (2026-10-02 확인)
 *
 * 올바른 연결고리는 사번이다.
 *   auth_user.personid → person.id → person.Code('p001') → TB_PB209.perid
 *
 * 잔여 계산식은 연차관리 화면과 똑같이 맞춘다. 화면마다 숫자가 다르면 안 된다.
 *   잔여 = 이월(iwolnum) + 발생(holinum) + 조정(monthnum) − 사용(TB_PB204 중 연차차감 대상)
 *
 * TB_PB204.perid 는 PB 가 넣은 행이 'p001', 웹이 넣은 행이 person.id(숫자 문자열)라 둘 다 받는다.
 */
public final class AnnualLeaveSql {

    private AnnualLeaveSql() { }

    /**
     * 올해 발생행을 찾아 잔여를 계산하는 OUTER APPLY 블록.
     * 바깥 쿼리에 person 을 별칭 {@code p} 로 둔 상태에서 붙여 쓴다.
     * 결과 별칭은 {@code ann} 이며 iwolnum · holinum · monthnum · used · restnum 을 돌려준다.
     */
    public static final String OUTER_APPLY_BY_PERSON = """
            OUTER APPLY (
                SELECT ISNULL(g.iwolnum, 0)  AS iwolnum,
                       ISNULL(g.holinum, 0)  AS holinum,
                       ISNULL(g.monthnum, 0) AS monthnum,
                       ISNULL(u.daynum, 0)   AS used,
                       ISNULL(g.iwolnum,0) + ISNULL(g.holinum,0) + ISNULL(g.monthnum,0)
                           - ISNULL(u.daynum,0) AS restnum
                  FROM TB_PB209 g
                  OUTER APPLY (
                        SELECT SUM(ISNULL(v.daynum, 0)) AS daynum
                          FROM TB_PB204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.SPJANGCD
                                         AND w.workcd   = v.workcd
                                         AND w.yearflag = '1'
                         WHERE v.SPJANGCD = g.spjangcd
                           AND ISNULL(v.fixflag, '') = '1'
                           AND LEFT(v.reqdate, 4) = g.hyear
                           AND ( v.perid = g.perid
                              OR (p.id IS NOT NULL AND TRY_CAST(v.perid AS INT) = p.id) )
                  ) u
                 WHERE g.spjangcd = p.spjangcd
                   AND g.perid    = p.Code
                   AND g.hyear    = CONVERT(CHAR(4), YEAR(GETDATE()))
                   AND g.hactcd   = '08'
                   AND g.hseq     = '00'
            ) ann
            """;
}
