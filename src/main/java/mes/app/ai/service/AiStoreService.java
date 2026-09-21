package mes.app.ai.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 기능이 본사 PostgreSQL `elv` 에 두는 데이터 — 키워드 사전, 설정, 유사사례 제외 목록, 사용 기록.
 * 테이블: _backup/20260917_elv_ai_tables_create.sql (본사 1회 실행)
 *
 * 테이블이 아직 없으면 mainSqlRunner 가 오류를 삼켜 null/0 을 돌려준다.
 * 읽기는 빈 값·기본값으로 동작하게 하고, 쓰기는 {@link #TABLE_MISSING} 메시지로 알린다.
 */
@Slf4j
@Service
public class AiStoreService {

    public static final String TABLE_MISSING = "AI 테이블이 없습니다. 본사 DB 에 20260917_elv_ai_tables_create.sql 실행이 필요합니다.";

    /** 설정 기본값 — 기능정의서 예시값 */
    public static final Map<String, String> DEFAULTS = Map.of(
            "classify_threshold", "60",   // 고장내용 추천 배지 노출 최소 신뢰도
            "case_threshold", "30",       // 유사사례 노출 최소 유사도 (TF-IDF 점수는 임베딩보다 낮게 나와 정의서 50 보다 낮춤)
            "doc_threshold", "40",        // 기술자료 검색 결과 노출 최소 관련도 (정의서 예시값)
            "mask_site", "N"              // 유사사례 현장명 마스킹
    );

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner mainSqlRunner;

    /** 사용 기록은 응답을 붙잡지 않도록 뒤에서 쓴다. 넘치면 버린다 */
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2000),
            r -> {
                Thread t = new Thread(r, "ai-log-writer");
                t.setDaemon(true);
                return t;
            },
            (r, ex) -> log.warn("[AI] 사용 기록 대기열이 가득 차 1건을 버림"));

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }

    // ── 사업체 식별 ─────────────────────────────────────────

    public String dbKey() {
        String k = TenantContext.getDbKey();
        if (k == null || k.isBlank()) throw new IllegalStateException("사업체 정보를 찾을 수 없습니다. 다시 로그인해주세요.");
        return k;
    }

    public String spjangcd() {
        String s = TenantContext.get();
        if (s == null || s.isBlank()) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다. 다시 로그인해주세요.");
        return s;
    }

    public static Integer userId(Authentication auth) {
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    // ── 설정 ────────────────────────────────────────────────

    public Map<String, String> settings() {
        Map<String, String> m = new LinkedHashMap<>(new TreeMap<>(DEFAULTS));
        List<Map<String, Object>> rows = mainSqlRunner.getRows(
                "SELECT setting_key, setting_value FROM ai_setting WHERE db_key = :db_key",
                new MapSqlParameterSource("db_key", dbKey()));
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                String key = String.valueOf(r.get("setting_key"));
                if (DEFAULTS.containsKey(key) && r.get("setting_value") != null) {
                    m.put(key, String.valueOf(r.get("setting_value")));
                }
            }
        }
        return m;
    }

    public int intSetting(String key) {
        try {
            return Integer.parseInt(settings().get(key).trim());
        } catch (Exception e) {
            return Integer.parseInt(DEFAULTS.get(key));
        }
    }

    public void saveSettings(Map<String, String> values, Integer userId) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!DEFAULTS.containsKey(e.getKey())) continue;
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("db_key", dbKey());
            p.addValue("k", e.getKey());
            p.addValue("v", e.getValue());
            p.addValue("uid", userId);
            int n = mainSqlRunner.execute("""
                    INSERT INTO ai_setting (db_key, setting_key, setting_value, _modified, _modifier_id)
                    VALUES (:db_key, :k, :v, now(), :uid)
                    ON CONFLICT (db_key, setting_key)
                    DO UPDATE SET setting_value = EXCLUDED.setting_value, _modified = now(), _modifier_id = EXCLUDED._modifier_id
                    """, p);
            if (n == 0) throw new IllegalStateException(TABLE_MISSING);
        }
    }

    // ── 키워드 사전 ─────────────────────────────────────────

    public List<Map<String, Object>> keywords(String contcd) {
        MapSqlParameterSource p = new MapSqlParameterSource("db_key", dbKey());
        String sql = "SELECT id, contcd, keyword, use_yn, _created, _modified FROM ai_fault_keyword WHERE db_key = :db_key";
        if (contcd != null && !contcd.isBlank()) {
            sql += " AND contcd = :contcd";
            p.addValue("contcd", contcd);
        }
        sql += " ORDER BY contcd, keyword";
        List<Map<String, Object>> rows = mainSqlRunner.getRows(sql, p);
        return rows == null ? new ArrayList<>() : rows;
    }

    /** 분류에 쓰는 사용중 키워드: 코드 → 키워드 */
    public Map<String, List<String>> activeKeywords() {
        Map<String, List<String>> m = new HashMap<>();
        for (Map<String, Object> r : keywords(null)) {
            if (!"Y".equals(String.valueOf(r.get("use_yn")))) continue;
            m.computeIfAbsent(String.valueOf(r.get("contcd")), k -> new ArrayList<>()).add(String.valueOf(r.get("keyword")));
        }
        return m;
    }

    public boolean tablesReady() {
        return mainSqlRunner.getRows("SELECT 1 FROM ai_setting LIMIT 1", new MapSqlParameterSource()) != null;
    }

    public void saveKeyword(Integer id, String contcd, String keyword, String useYn, Integer userId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("db_key", dbKey());
        p.addValue("id", id);
        p.addValue("contcd", contcd);
        p.addValue("keyword", keyword.trim());
        p.addValue("use_yn", "N".equals(useYn) ? "N" : "Y");
        p.addValue("uid", userId);

        Map<String, Object> dup = mainSqlRunner.getRow("""
                SELECT id FROM ai_fault_keyword
                 WHERE db_key = :db_key AND contcd = :contcd AND keyword = :keyword
                   AND (CAST(:id AS integer) IS NULL OR id <> :id)
                """, p);
        if (dup != null) throw new IllegalArgumentException("같은 고장내용에 이미 등록된 키워드입니다.");

        int n;
        if (id == null) {
            n = mainSqlRunner.execute("""
                    INSERT INTO ai_fault_keyword (db_key, contcd, keyword, use_yn, _creater_id)
                    VALUES (:db_key, :contcd, :keyword, :use_yn, :uid)
                    """, p);
        } else {
            n = mainSqlRunner.execute("""
                    UPDATE ai_fault_keyword
                       SET keyword = :keyword, use_yn = :use_yn, _modified = now(), _modifier_id = :uid
                     WHERE id = :id AND db_key = :db_key
                    """, p);
        }
        if (n == 0) throw new IllegalStateException(id == null ? TABLE_MISSING : "키워드를 찾을 수 없습니다.");
    }

    public void deleteKeyword(Integer id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("db_key", dbKey());
        p.addValue("id", id);
        int n = mainSqlRunner.execute("DELETE FROM ai_fault_keyword WHERE id = :id AND db_key = :db_key", p);
        if (n == 0) throw new IllegalStateException("키워드를 찾을 수 없습니다.");
    }

    // ── 유사사례 제외 ───────────────────────────────────────

    public Set<String> excludedCases() {
        List<Map<String, Object>> rows = mainSqlRunner.getRows(
                "SELECT compdate, compnum FROM ai_case_exclude WHERE db_key = :db_key",
                new MapSqlParameterSource("db_key", dbKey()));
        Set<String> s = new HashSet<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) s.add(r.get("compdate") + "-" + r.get("compnum"));
        }
        return s;
    }

    public List<Map<String, Object>> excludedCaseRows() {
        List<Map<String, Object>> rows = mainSqlRunner.getRows(
                "SELECT compdate, compnum, reason, _created FROM ai_case_exclude WHERE db_key = :db_key",
                new MapSqlParameterSource("db_key", dbKey()));
        return rows == null ? new ArrayList<>() : rows;
    }

    public void setCaseExcluded(String compdate, String compnum, boolean exclude, String reason, Integer userId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("db_key", dbKey());
        p.addValue("compdate", compdate);
        p.addValue("compnum", compnum);
        p.addValue("reason", reason == null ? null : reason.length() > 200 ? reason.substring(0, 200) : reason);
        p.addValue("uid", userId);
        if (exclude) {
            int n = mainSqlRunner.execute("""
                    INSERT INTO ai_case_exclude (db_key, compdate, compnum, reason, _creater_id)
                    VALUES (:db_key, :compdate, :compnum, :reason, :uid)
                    ON CONFLICT (db_key, compdate, compnum) DO UPDATE SET reason = EXCLUDED.reason
                    """, p);
            if (n == 0) throw new IllegalStateException(TABLE_MISSING);
        } else {
            mainSqlRunner.execute(
                    "DELETE FROM ai_case_exclude WHERE db_key = :db_key AND compdate = :compdate AND compnum = :compnum", p);
        }
    }

    // ── 사용 기록 ───────────────────────────────────────────

    /** 값은 호출한 요청 스레드에서 다 채워서 넘긴다 */
    public void logEvent(Map<String, Object> values, Authentication auth) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        User user = (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
        p.addValue("db_key", TenantContext.getDbKey());
        p.addValue("user_id", user != null ? user.getId() : null);
        p.addValue("user_name", user != null ? cut(user.getFirst_name(), 30) : null);
        p.addValue("source", cut(values.get("source"), 10));
        p.addValue("feature", cut(values.get("feature"), 20));
        p.addValue("event", cut(values.get("event"), 20));
        p.addValue("query_text", cut(values.get("query_text"), 500));
        p.addValue("ref_key", cut(values.get("ref_key"), 50));
        p.addValue("suggested", cut(values.get("suggested"), 50));
        p.addValue("confidence", toInt(values.get("confidence")));
        p.addValue("final_value", cut(values.get("final_value"), 50));
        p.addValue("adopted", toBool(values.get("adopted")));
        p.addValue("result_cnt", toInt(values.get("result_cnt")));
        p.addValue("top_score", toInt(values.get("top_score")));
        p.addValue("rank_no", toInt(values.get("rank_no")));
        p.addValue("detail", cut(values.get("detail"), 2000));
        if (p.getValue("feature") == null || p.getValue("event") == null) return;

        writer.execute(() -> {
            try {
                mainSqlRunner.execute("""
                        INSERT INTO ai_event_log (db_key, user_id, user_name, source, feature, event, query_text, ref_key,
                                                  suggested, confidence, final_value, adopted, result_cnt, top_score, rank_no, detail)
                        VALUES (:db_key, :user_id, :user_name, :source, :feature, :event, :query_text, :ref_key,
                                :suggested, :confidence, :final_value, :adopted, :result_cnt, :top_score, :rank_no, :detail)
                        """, p);
            } catch (Exception e) {
                log.warn("[AI] 사용 기록 저장 실패: {}", e.getMessage());
            }
        });
    }

    /** 사용 기록 조회 (관리 화면 통계용). 테이블이 없으면 빈 목록 */
    public List<Map<String, Object>> queryLog(String sql, MapSqlParameterSource p) {
        p.addValue("db_key", dbKey());
        List<Map<String, Object>> rows = mainSqlRunner.getRows(sql, p);
        return rows == null ? new ArrayList<>() : rows;
    }

    static String cut(Object o, int max) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(o).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Boolean toBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "Y".equalsIgnoreCase(s);
    }
}
