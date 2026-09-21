package mes.app.ai.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.ai.engine.FaultClassifier;
import mes.app.ai.engine.TfIdfIndex;
import mes.app.files.NcpObjectStorageService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 사업체(db_key)별 메모리 색인 3종을 만들고 보관한다.
 *
 * - 고장유형 분류: 고장접수 TB_E401 (상세내용 → 고장내용 코드)
 * - 유사 고장사례: 고장처리 TB_E411 + 접수 (고장내용·상세내용으로 찾고 처리내용을 보여준다)
 * - 기술자료: 자료실 tb_bbsinfo (제목·내용·첨부파일명)
 *
 * 원본은 사업체 DB 라 요청 스레드(세션 db_key 로 라우팅되는 SqlRunner)에서 만든다.
 * 만든 지 {@link #MAX_AGE_HOURS} 시간이 지나면 다음 요청 때 새로 만든다. 관리 화면의 재색인은 즉시 다시 만든다.
 * 실측: 경기 1만 건 0.1초, ZQ 9만 건 0.5초 (DB 조회 시간 별도).
 */
@Slf4j
@Service
public class AiIndexService {

    public static final int MAX_AGE_HOURS = 6;
    /** 사업체당 색인에 넣는 최대 건수 (최근 건 우선) */
    public static final int MAX_DOCS = 100_000;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    SqlRunner sqlRunner;

    public record Built<T>(T value, int size, LocalDateTime builtAt, long buildMs) {
        public Map<String, Object> status() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", size);
            m.put("builtAt", builtAt.format(TS));
            m.put("buildMs", buildMs);
            return m;
        }
    }

    /** 유사사례 색인 문서 — 상세는 검색 뒤에 DB 에서 다시 읽는다 */
    public record CaseMeta(String compdate, String compnum, String actcd, String equpcd, String contcd) {
        public String key() {
            return compdate + "-" + compnum;
        }
    }

    /** 기술자료 색인 문서 — 발췌를 만들려고 본문을 들고 있는다 (자료 수가 적다) */
    public record DocMeta(int bbsseq, String title, String content, String files, String bbsdate, String user) {
    }

    private final Map<String, Built<FaultClassifier>> classifiers = new ConcurrentHashMap<>();
    private final Map<String, Built<TfIdfIndex<CaseMeta>>> caseIndexes = new ConcurrentHashMap<>();
    private final Map<String, Built<TfIdfIndex<DocMeta>>> docIndexes = new ConcurrentHashMap<>();

    // ── 조회 (없거나 오래되면 만든다) ──────────────────────

    public Built<FaultClassifier> classifier(String dbKey, String spjangcd) {
        return getOrBuild(classifiers, dbKey, () -> buildClassifier(spjangcd));
    }

    public Built<TfIdfIndex<CaseMeta>> caseIndex(String dbKey, String spjangcd) {
        return getOrBuild(caseIndexes, dbKey, () -> buildCaseIndex(spjangcd));
    }

    public Built<TfIdfIndex<DocMeta>> docIndex(String dbKey) {
        return getOrBuild(docIndexes, dbKey, this::buildDocIndex);
    }

    public Built<FaultClassifier> rebuildClassifier(String dbKey, String spjangcd) {
        return put(classifiers, dbKey, () -> buildClassifier(spjangcd));
    }

    public Built<TfIdfIndex<CaseMeta>> rebuildCaseIndex(String dbKey, String spjangcd) {
        return put(caseIndexes, dbKey, () -> buildCaseIndex(spjangcd));
    }

    public Built<TfIdfIndex<DocMeta>> rebuildDocIndex(String dbKey) {
        return put(docIndexes, dbKey, this::buildDocIndex);
    }

    /** 자료 등록·수정·삭제 뒤 다음 검색 때 새로 만들도록 버린다 */
    public void invalidateDocIndex(String dbKey) {
        if (dbKey != null) docIndexes.remove(dbKey);
    }

    public Map<String, Object> peekStatus(String dbKey, String which) {
        Built<?> b = switch (which) {
            case "classify" -> classifiers.get(dbKey);
            case "case" -> caseIndexes.get(dbKey);
            default -> docIndexes.get(dbKey);
        };
        return b == null ? null : b.status();
    }

    private <T> Built<T> getOrBuild(Map<String, Built<T>> cache, String dbKey, Supplier<Built<T>> builder) {
        Built<T> b = cache.get(dbKey);
        if (b != null && b.builtAt().isAfter(LocalDateTime.now().minusHours(MAX_AGE_HOURS))) return b;
        return put(cache, dbKey, builder);
    }

    private <T> Built<T> put(Map<String, Built<T>> cache, String dbKey, Supplier<Built<T>> builder) {
        // 같은 사업체 색인을 동시에 여러 번 만들지 않도록 사업체 단위로 잠근다
        synchronized (lockFor(dbKey)) {
            Built<T> b = builder.get();
            cache.put(dbKey, b);
            return b;
        }
    }

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private Object lockFor(String dbKey) {
        return locks.computeIfAbsent(dbKey, k -> new Object());
    }

    // ── 만들기 ──────────────────────────────────────────────

    private Built<FaultClassifier> buildClassifier(String spjangcd) {
        long t0 = System.currentTimeMillis();
        MapSqlParameterSource p = new MapSqlParameterSource("spjangcd", spjangcd);
        List<Map<String, Object>> rows = sqlRunner.getRows("""
                SELECT TOP %d a.contcd, CAST(a.contents AS varchar(1000)) AS contents
                  FROM TB_E401 a WITH(NOLOCK)
                 WHERE a.spjangcd = :spjangcd
                   AND ISNULL(a.contcd, '') <> ''
                   AND LEN(LTRIM(CAST(a.contents AS varchar(1000)))) >= 2
                 ORDER BY a.recedate DESC, a.recenum DESC
                """.formatted(MAX_DOCS), p);
        requireRows(rows, "고장접수");
        List<String> codes = new ArrayList<>(rows.size());
        List<String> texts = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            codes.add(String.valueOf(r.get("contcd")).trim());
            texts.add(String.valueOf(r.get("contents")));
        }
        FaultClassifier clf = FaultClassifier.build(codes, texts);
        long ms = System.currentTimeMillis() - t0;
        log.info("[AI] 고장분류 색인 spjangcd={} 건수={} {}ms", spjangcd, codes.size(), ms);
        return new Built<>(clf, codes.size(), LocalDateTime.now(), ms);
    }

    private Built<TfIdfIndex<CaseMeta>> buildCaseIndex(String spjangcd) {
        long t0 = System.currentTimeMillis();
        MapSqlParameterSource p = new MapSqlParameterSource("spjangcd", spjangcd);
        // 처리내용(코드 또는 상세)이 있는 건만 — 참고할 조치가 없는 사례는 추천할 이유가 없다
        List<Map<String, Object>> rows = sqlRunner.getRows("""
                SELECT TOP %d e.compdate, e.compnum, e.actcd, ISNULL(e.equpcd, '') AS equpcd,
                       ISNULL(a.contcd, '') AS contcd, ISNULL(ct.contnm, '') AS contnm,
                       CAST(a.contents AS varchar(1000)) AS contents
                  FROM TB_E411 e WITH(NOLOCK)
                  JOIN TB_E401 a WITH(NOLOCK)
                    ON a.spjangcd = e.spjangcd AND a.recedate = e.recedate AND a.recenum = e.recenum AND a.actcd = e.actcd
                  LEFT JOIN TB_E010 ct WITH(NOLOCK) ON ct.spjangcd = a.spjangcd AND ct.contcd = a.contcd
                 WHERE e.spjangcd = :spjangcd
                   AND LEN(e.compdate) = 8
                   AND (ISNULL(e.resucd, '') <> '' OR DATALENGTH(e.resuremark) > 0)
                 ORDER BY e.compdate DESC, e.compnum DESC
                """.formatted(MAX_DOCS), p);
        requireRows(rows, "고장처리");
        List<CaseMeta> metas = new ArrayList<>(rows.size());
        List<String> texts = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            metas.add(new CaseMeta(str(r.get("compdate")), str(r.get("compnum")), str(r.get("actcd")),
                    str(r.get("equpcd")), str(r.get("contcd"))));
            texts.add(str(r.get("contnm")) + " " + str(r.get("contents")));
        }
        TfIdfIndex<CaseMeta> idx = TfIdfIndex.build(metas, texts, true);
        long ms = System.currentTimeMillis() - t0;
        log.info("[AI] 유사사례 색인 spjangcd={} 건수={} {}ms", spjangcd, metas.size(), ms);
        return new Built<>(idx, metas.size(), LocalDateTime.now(), ms);
    }

    private Built<TfIdfIndex<DocMeta>> buildDocIndex() {
        long t0 = System.currentTimeMillis();
        MapSqlParameterSource p = new MapSqlParameterSource();
        // 자료실은 사업체 DB 전체가 한 게시판이다 (spjangcd 컬럼 없음 — 기존 자료실 화면과 동일)
        List<Map<String, Object>> rows = sqlRunner.getRows("""
                SELECT b.BBSSEQ AS bbsseq, ISNULL(b.BBSSUBJECT, '') AS title,
                       CAST(b.BBSTEXT AS varchar(max)) AS content, ISNULL(b.BBSDATE, '') AS bbsdate,
                       ISNULL(b.BBSUSER, '') AS bbsuser
                  FROM tb_bbsinfo b WITH(NOLOCK)
                 ORDER BY b.BBSSEQ DESC
                """, p);
        requireRows(rows, "자료실");
        // 첨부는 자료실(NOTICE) 것만 — TB_FILEINFO 는 여러 기능이 CHECKSEQ 로 나눠 쓴다
        p.addValue("checkseq", NcpObjectStorageService.toCheckseq("NOTICE"));
        List<Map<String, Object>> files = sqlRunner.getRows("""
                SELECT f.bbsseq, ISNULL(f.FILEORNM, '') AS fileornm
                  FROM TB_FILEINFO f WITH(NOLOCK)
                 WHERE f.CHECKSEQ = :checkseq
                """, p);
        Map<String, StringBuilder> fileNames = new HashMap<>();
        if (files != null) {
            for (Map<String, Object> f : files) {
                fileNames.computeIfAbsent(str(f.get("bbsseq")), k -> new StringBuilder()).append(str(f.get("fileornm"))).append(' ');
            }
        }

        List<DocMeta> metas = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        {
            for (Map<String, Object> r : rows) {
                int seq;
                try {
                    seq = Integer.parseInt(str(r.get("bbsseq")));
                } catch (NumberFormatException e) {
                    continue;
                }
                String title = str(r.get("title"));
                String content = htmlToText(str(r.get("content")));
                String fn = fileNames.containsKey(String.valueOf(seq)) ? fileNames.get(String.valueOf(seq)).toString().trim() : "";
                metas.add(new DocMeta(seq, title, content.length() > 20000 ? content.substring(0, 20000) : content, fn,
                        str(r.get("bbsdate")), str(r.get("bbsuser"))));
                // 제목은 두 번 넣어 본문보다 무겁게 본다
                texts.add(title + " " + title + " " + content + " " + fn);
            }
        }
        TfIdfIndex<DocMeta> idx = TfIdfIndex.build(metas, texts, false);
        long ms = System.currentTimeMillis() - t0;
        log.info("[AI] 기술자료 색인 건수={} {}ms", metas.size(), ms);
        return new Built<>(idx, metas.size(), LocalDateTime.now(), ms);
    }

    /** 조회 실패를 빈 색인으로 착각해 6시간 보관하지 않도록 예외로 올린다 */
    private static void requireRows(List<?> rows, String what) {
        if (rows == null) throw new IllegalStateException(what + " 데이터를 읽지 못했습니다. 잠시 후 다시 시도해주세요.");
    }

    /** 자료실 본문은 에디터 HTML 일 수 있다 */
    static String htmlToText(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"");
        return t.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
