package mes.app.mobile.Service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 출퇴근 시도 기록 (본사 PG commute_log + 서버 로그 [Commute]).
 *
 * 예전에는 출근/퇴근 API 가 시도 자체를 어디에도 남기지 않아 "아침에 눌렀는데 안 됐다" 는 문의를 확인할 수 없었다.
 * 성우(nmyang)에서 오전 출근이 저장되지 않은 건이 반복돼 단계마다 남기게 했다.
 *
 * 버튼 한 번 = attempt_id 하나. 같은 ID 로
 *   · CLIENT 줄 — 앱 화면이 보낸 단계 기록 (클릭, 화면에서 막힘, 요청 전송, 응답, 통신 실패)  → recordClient()
 *   · SERVER 줄 — 서버 처리 결과 + 사업체 DB 를 다시 읽어 확인한 저장 상태                 → record()
 *
 * 저장 검증: 처리 로직이 성공을 돌려줘도 tb_pb201 을 다시 읽어 출근(또는 퇴근)시간이 실제로 있는지 본다.
 * 없으면 status = UNVERIFIED 로 남기고 ERROR 로그를 찍는다.
 *
 * 기록이 실패해도 출퇴근 처리에는 영향을 주지 않는다.
 */
@Slf4j
@Service
public class CommuteLogService {

	/** 처리 로직(MobileMainController)이 저장 대상 키를 넘겨주는 request 속성 이름 */
	public static final String ATTR_SPJANGCD = "commute.spjangcd";
	public static final String ATTR_PERID = "commute.perid";
	public static final String ATTR_IDX = "commute.idx";

	private static final int CLIENT_BATCH_MAX = 50;

	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner mainSqlRunner;

	/** 사업체 DB (세션 db_key 로 라우팅) — 저장 검증용 */
	@Autowired
	SqlRunner tenantSqlRunner;

	/** commute_log 테이블이 없다는 경고를 한 번만 찍기 위한 표시 */
	private volatile boolean tableMissingWarned = false;

	/**
	 * 본사 DB 기록은 출퇴근 응답을 붙잡지 않도록 백그라운드 한 줄로 쓴다.
	 * 대기열이 가득 차면(본사 DB 가 한동안 느린 경우) 넘치는 기록만 버리고 경고를 남긴다 — 출퇴근 처리는 영향 없음.
	 * 저장 검증 조회는 사업체 DB 라우팅이 요청 세션에 묶여 있어 요청 안에서 한다 (tb_pb201 PK 조회 한 번).
	 */
	private final java.util.concurrent.ThreadPoolExecutor writer = new java.util.concurrent.ThreadPoolExecutor(
			1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
			new java.util.concurrent.LinkedBlockingQueue<>(2000),
			r -> {
				Thread t = new Thread(r, "commute-log-writer");
				t.setDaemon(true);
				return t;
			},
			(r, ex) -> log.warn("[Commute] 기록 대기열이 가득 차 1건을 버림 (본사 DB 지연 의심)"));

	@javax.annotation.PreDestroy
	void shutdownWriter() {
		writer.shutdown();
	}

	// ────────────────────────────────────────────────────────────
	//  서버 처리 기록
	// ────────────────────────────────────────────────────────────

	/**
	 * 출퇴근 처리를 감싸 결과·예외·소요시간·저장 상태를 기록한다.
	 * 처리 중 잡히지 않은 예외도 여기서 잡아 실패 응답으로 돌려준다 (예전에는 500 이었다).
	 */
	public AjaxResult record(String action, String attemptId, HttpServletRequest request, Authentication auth,
	                         String office, String workym, String workday,
	                         String latitude, String longitude, String gpsInfo,
	                         Supplier<AjaxResult> work) {
		long started = System.currentTimeMillis();
		AjaxResult result;
		String status;
		String error = null;

		try {
			result = work.get();
			status = (result != null && result.success) ? "SUCCESS" : "FAIL";
		} catch (Exception e) {
			log.error("[Commute] {} 처리 중 예외 attempt={}", action, attemptId, e);
			result = new AjaxResult();
			result.success = false;
			result.message = "오류가 발생하였습니다.";
			status = "ERROR";
			error = e.getClass().getSimpleName() + ": " + e.getMessage();
		}
		long elapsed = System.currentTimeMillis() - started;

		MapSqlParameterSource p = new MapSqlParameterSource();
		try {
			// 저장 검증 — 성공이라고 한 건만 다시 읽는다
			Verify v = verify(action, status, request, workym, workday);
			if ("SUCCESS".equals(status) && "MISSING".equals(v.result)) {
				status = "UNVERIFIED";
				log.error("[Commute] 성공 응답인데 저장이 확인되지 않음 attempt={} action={} db={} spjangcd={} perid={} work={}{} idx={}",
						attemptId, action, v.db, v.spjangcd, v.perid, workym, workday, v.idx);
			}

			String message = result != null ? result.message : null;
			// 처리 로직 안에서 잡힌 예외는 메시지에만 남아 있어 그 경우도 오류 내용으로 옮긴다
			if (error == null && "FAIL".equals(status) && message != null && message.startsWith("오류가 발생하였습니다")) {
				error = message;
			}

			String serverYmd = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
			String clientYmd = str(workym) + str(workday);
			Boolean mismatch = clientYmd.length() == 8 ? !clientYmd.equals(serverYmd) : null;

			fillCommon(p, request, auth, "SERVER", attemptId, action);
			p.addValue("stage", "DONE");
			p.addValue("status", status);
			p.addValue("message", cut(message, 500));
			p.addValue("error_detail", cut(error, 2000));
			p.addValue("office", cut(office, 30));
			p.addValue("workym", cut(workym, 6));
			p.addValue("workday", cut(workday, 2));
			p.addValue("server_ymd", serverYmd);
			p.addValue("date_mismatch", mismatch);
			p.addValue("latitude", cut(latitude, 30));
			p.addValue("longitude", cut(longitude, 30));
			p.addValue("gps_info", cut(gpsInfo, 300));
			p.addValue("tenant_db", cut(v.db, 50));
			p.addValue("tenant_spjangcd", cut(v.spjangcd, 10));
			p.addValue("perid", cut(v.perid, 20));
			p.addValue("saved_idx", v.idx);
			p.addValue("saved_starttime", cut(v.starttime, 10));
			p.addValue("saved_endtime", cut(v.endtime, 10));
			p.addValue("verify", v.result);
			p.addValue("client_time", null);
			p.addValue("client_detail", null);
			p.addValue("elapsed_ms", elapsed);

			log.info("[Commute] SERVER attempt={} action={} status={} db_key={} user={}({}) office={} work={}{} server={} "
							+ "tenant={} perid={} idx={} saved={}~{} verify={} elapsed={}ms msg={} err={}",
					attemptId, action, status, p.getValue("db_key"), p.getValue("user_name"), p.getValue("username"),
					office, workym, workday, serverYmd, v.db, v.perid, v.idx, v.starttime, v.endtime, v.result,
					elapsed, message, error);

			insertAsync(p);
		} catch (Exception e) {
			log.warn("[Commute] 서버 기록 저장 실패 attempt={}: {}", attemptId, e.getMessage());
		}
		return result;
	}

	/** 저장 직후 사업체 DB 를 다시 읽어 실제로 들어갔는지 본다 */
	private Verify verify(String action, String status, HttpServletRequest request, String workym, String workday) {
		Verify v = new Verify();
		v.spjangcd = attr(request, ATTR_SPJANGCD);
		v.perid = attr(request, ATTR_PERID);
		String idx = attr(request, ATTR_IDX);
		v.idx = idx == null ? null : Integer.valueOf(idx);

		if (!"SUCCESS".equals(status) || v.spjangcd == null || v.perid == null || v.idx == null) {
			v.result = "SKIPPED";
			Map<String, Object> db = tenantSqlRunner.getRow("SELECT DB_NAME() AS db", new MapSqlParameterSource());
			v.db = db == null ? null : str(db.get("db"));
			return v;
		}

		MapSqlParameterSource q = new MapSqlParameterSource();
		q.addValue("spjangcd", v.spjangcd);
		q.addValue("perid", v.perid);
		q.addValue("workym", workym);
		q.addValue("workday", workday);
		q.addValue("idx", v.idx);
		Map<String, Object> row = tenantSqlRunner.getRow("""
				SELECT DB_NAME() AS db,
				       (SELECT TOP 1 ISNULL(starttime, '') FROM tb_pb201
				         WHERE spjangcd = :spjangcd AND perid = :perid AND workym = :workym AND workday = :workday AND idx = :idx) AS starttime,
				       (SELECT TOP 1 ISNULL(endtime, '') FROM tb_pb201
				         WHERE spjangcd = :spjangcd AND perid = :perid AND workym = :workym AND workday = :workday AND idx = :idx) AS endtime
				""", q);
		if (row == null) {
			v.result = "CHECK_FAIL";
			return v;
		}
		v.db = str(row.get("db"));
		v.starttime = row.get("starttime") == null ? null : str(row.get("starttime"));
		v.endtime = row.get("endtime") == null ? null : str(row.get("endtime"));

		String expected = "OUT".equals(action) ? v.endtime : v.starttime;
		v.result = (expected == null || expected.isBlank()) ? "MISSING" : "OK";
		return v;
	}

	// ────────────────────────────────────────────────────────────
	//  앱 화면 단계 기록
	// ────────────────────────────────────────────────────────────

	/** 앱이 모아 보낸 단계 기록을 저장한다. 사용자·사업체는 세션에서 정한다 */
	public int recordClient(List<Map<String, Object>> traces, HttpServletRequest request, Authentication auth) {
		if (traces == null || traces.isEmpty()) return 0;

		int saved = 0;
		for (Map<String, Object> t : traces.subList(0, Math.min(traces.size(), CLIENT_BATCH_MAX))) {
			try {
				MapSqlParameterSource p = new MapSqlParameterSource();
				fillCommon(p, request, auth, "CLIENT", cut(str(t.get("attemptId")), 40), cut(str(t.get("action")), 20));
				p.addValue("stage", cut(str(t.get("stage")), 40));
				p.addValue("status", null);
				p.addValue("message", cut(str(t.get("message")), 500));
				p.addValue("error_detail", cut(str(t.get("error")), 2000));
				p.addValue("office", cut(str(t.get("office")), 30));
				p.addValue("workym", cut(str(t.get("workym")), 6));
				p.addValue("workday", cut(str(t.get("workday")), 2));
				p.addValue("server_ymd", null);
				p.addValue("date_mismatch", null);
				p.addValue("latitude", cut(str(t.get("latitude")), 30));
				p.addValue("longitude", cut(str(t.get("longitude")), 30));
				p.addValue("gps_info", cut(str(t.get("gpsInfo")), 300));
				p.addValue("tenant_db", null);
				p.addValue("tenant_spjangcd", null);
				p.addValue("perid", null);
				p.addValue("saved_idx", null);
				p.addValue("saved_starttime", null);
				p.addValue("saved_endtime", null);
				p.addValue("verify", null);
				p.addValue("client_time", cut(str(t.get("clientTime")), 40));
				p.addValue("client_detail", cut(str(t.get("detail")), 4000));
				p.addValue("elapsed_ms", null);

				log.info("[Commute] CLIENT attempt={} action={} stage={} db_key={} user={} clientTime={} msg={} detail={}",
						p.getValue("attempt_id"), p.getValue("action"), p.getValue("stage"), p.getValue("db_key"),
						p.getValue("user_name"), p.getValue("client_time"), p.getValue("message"), p.getValue("client_detail"));

				insertAsync(p);
				saved++;
			} catch (Exception e) {
				log.warn("[Commute] 앱 단계 기록 저장 실패: {}", e.getMessage());
			}
		}
		return saved;
	}

	// ────────────────────────────────────────────────────────────

	private void fillCommon(MapSqlParameterSource p, HttpServletRequest request, Authentication auth,
	                        String source, String attemptId, String action) {
		User user = (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
		String ip = request.getHeader("X-Forwarded-For");
		ip = (ip == null || ip.isBlank()) ? request.getRemoteAddr() : ip.split(",")[0].trim();

		p.addValue("source", source);
		p.addValue("attempt_id", cut(attemptId, 40));
		p.addValue("db_key", user != null ? user.getDbKey() : null);
		p.addValue("user_id", user != null ? user.getId() : null);
		p.addValue("username", user != null ? user.getUsername() : null);
		p.addValue("user_name", user != null ? cut(user.getFirst_name(), 30) : null);
		p.addValue("action", (action == null || action.isBlank()) ? "UNKNOWN" : action);
		p.addValue("ip", cut(ip, 60));
		p.addValue("user_agent", cut(request.getHeader("User-Agent"), 500));
	}

	/** 값은 요청 스레드에서 다 채운 뒤 넘긴다 (세션·request 는 백그라운드에서 읽지 않는다) */
	private void insertAsync(MapSqlParameterSource p) {
		writer.execute(() -> {
			try {
				insert(p);
			} catch (Exception e) {
				log.warn("[Commute] 기록 저장 실패: {}", e.getMessage());
			}
		});
	}

	private boolean insert(MapSqlParameterSource p) {
		// SqlRunner 는 오류를 삼키고 0 을 돌려준다. 0 이면 대개 테이블이 아직 없는 경우다
		int n = mainSqlRunner.execute("""
				INSERT INTO commute_log (source, attempt_id, db_key, user_id, username, user_name, action, stage, status,
				                         message, error_detail, office, workym, workday, server_ymd, date_mismatch,
				                         latitude, longitude, gps_info, tenant_db, tenant_spjangcd, perid,
				                         saved_idx, saved_starttime, saved_endtime, verify,
				                         client_time, client_detail, ip, user_agent, elapsed_ms)
				VALUES (:source, :attempt_id, :db_key, :user_id, :username, :user_name, :action, :stage, :status,
				        :message, :error_detail, :office, :workym, :workday, :server_ymd, :date_mismatch,
				        :latitude, :longitude, :gps_info, :tenant_db, :tenant_spjangcd, :perid,
				        :saved_idx, :saved_starttime, :saved_endtime, :verify,
				        :client_time, :client_detail, :ip, :user_agent, :elapsed_ms)
				""", p);
		if (n == 0 && !tableMissingWarned) {
			tableMissingWarned = true;
			log.warn("[Commute] commute_log 저장 0건 — 본사 DB 에 commute_log 테이블이 있는지 확인하세요 (서버 로그에는 계속 남습니다)");
		}
		return n > 0;
	}

	private static String attr(HttpServletRequest request, String name) {
		Object o = request.getAttribute(name);
		return o == null ? null : String.valueOf(o);
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static String cut(String s, int max) {
		if (s == null) return null;
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static class Verify {
		String db, spjangcd, perid, starttime, endtime, result;
		Integer idx;
	}
}
