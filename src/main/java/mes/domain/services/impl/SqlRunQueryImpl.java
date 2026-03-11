package mes.domain.services.impl;


import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import org.hibernate.exception.DataException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import mes.domain.services.LogWriter;
import mes.domain.services.SqlRunner;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Repository
public class SqlRunQueryImpl implements SqlRunner {

	@Autowired
	@Qualifier("namedParameterJdbcTemplate")
    private NamedParameterJdbcTemplate  jdbcTemplate;
	
	@Autowired
	LogWriter logWriter;
	
	
    public List<Map<String, Object>> getRows(String sql, MapSqlParameterSource dicParam){

    	List<Map<String, Object>> rows = null;
		applyDbRouting(sql);

    	try {
    		rows = this.jdbcTemplate.queryForList(sql, dicParam);
		}
    	catch(DataAccessException de) {
    		System.out.println(de);
    	}
    	catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.getRows", e);
		} finally {
    		TenantContext.setForceMainDb(false);
    	}
    	return rows;
    }

    public Map<String, Object> getRow(String sql, MapSqlParameterSource dicParam){

    	Map<String, Object> row = null;
		applyDbRouting(sql);

    	try {
    		row = this.jdbcTemplate.queryForMap(sql, dicParam);
		}
    	catch(DataAccessException de) {


    	}
    	catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.getRow", e);
		} finally {
    		TenantContext.setForceMainDb(false);
    	}
    	return row;
    }

    public int execute(String sql, MapSqlParameterSource dicParam) {

    	int rowEffected = 0;
		applyDbRouting(sql);
    	try {
    		rowEffected = this.jdbcTemplate.update(sql, dicParam);
		} catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.excute", e);
		} finally {
    		TenantContext.setForceMainDb(false);
    	}

    	return rowEffected;
    }
    
    public int queryForCount(String sql,  MapSqlParameterSource dicParam) {
    	//select count(*) from xxx where ~
    	return this.jdbcTemplate.queryForObject(sql, dicParam, int.class);
    }
    
    public <T> T queryForObject(String sql,  MapSqlParameterSource dicParam, RowMapper<T> mapper) throws DataException {
    	T rr= this.jdbcTemplate.queryForObject(sql, dicParam, mapper); 
    	return rr;    	
    }

	public int[] batchUpdate(String sql, SqlParameterSource[] batchArgs) {
		int[] result = new int[0];
		applyDbRouting(sql);
		try {
			result = this.jdbcTemplate.batchUpdate(sql, batchArgs);
		} catch (DataAccessException de) {
			System.out.println(de);
		} catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.batchUpdate", e);
		} finally {
			TenantContext.setForceMainDb(false);
		}
		return result;
	}

	/** 메인 DB에 있는 시스템 테이블 목록 – 이 테이블이 SQL에 포함되면 mainDataSource로 라우팅 */
	private static final String[] MAIN_DB_TABLES = {
			"menu_folder", "menu_item", "user_group_menu", "bookmark",
			"label_code_lang", "sys_option", "sys_code", "sys_common_code",
			"auth_user", "tb_user", "user_profile", "user_group",
			"login_log", "menu_use_log", "notification",
			"bill_plans", "mat_comp_uprice", "seq_maker", "tb_xa012"
	};

	/**
	 * SQL에 포함된 테이블명을 기준으로 메인 DB / 테넌트 DB 라우팅 결정
	 * - 메인 DB 테이블이면 forceMainDb=true → RoutingDataSource가 mainDataSource 사용
	 * - 그 외에는 forceMainDb=false → TenantContext.dbKey 기준으로 라우팅
	 */
	private void applyDbRouting(String sql) {
		String lowSql = sql.toLowerCase();
		for (String table : MAIN_DB_TABLES) {
			if (lowSql.contains(table)) {
				TenantContext.setForceMainDb(true);
				return;
			}
		}
		TenantContext.setForceMainDb(false);
		checkTenantSafety(sql);
	}

	// 런타임 쿼리 검증 (테넌트 DB 쿼리 대상)
	private void checkTenantSafety(String sql) {
		String tenantId = TenantContext.get();

		// URL 화이트리스트
		String requestUri = null;
		try {
			ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
			if (attrs != null) {
				requestUri = attrs.getRequest().getRequestURI();
			}
		} catch (Exception e) {
			// 배치/스케줄러 등 request context 없는 경우 무시
		}

		String[] urlWhiteList = {"/combo"};
		if (requestUri != null) {
			for (String url : urlWhiteList) {
				if (requestUri.contains(url)) return;
			}
		}

		if (tenantId != null && !"SYSTEM".equals(tenantId)) {
			String lowSql = sql.toLowerCase();

			// 1. 화이트리스트 (로그인, 공통코드 등)
			String[] whiteList =
					{"menu_folder", "label_code_lang"
							, "bookmark", "menu_item"
							, "sys_option", "sys_code"
							, "bill_plans", "mat_comp_uprice"
							, "seq_maker"};

			for (String table : whiteList) {
				if (lowSql.contains(table)) return;
			}

			// 2. 보안 가드레일 (차단 대신 로깅)
			if (lowSql.contains("select") || lowSql.contains("update") || lowSql.contains("delete")) {
				// id 단건 delete/update는 skip
				if (lowSql.matches(".*delete from \\w+ where id=\\?.*") ||
						lowSql.matches(".*delete from \\w+ where id=:id.*") ||  // named parameter 방식도 추가
						lowSql.matches(".*update \\w+ set .* where id=\\?.*") ||
						lowSql.matches(".*update \\w+ set .* where id=:id.*")) return;

				if (!lowSql.contains("spjangcd") && !lowSql.contains("/* skip_tenant_check */")) {
					log.warn("⚠️ [멀티테넌트 보안 권고] spjangcd 누락 감지 (실행 허용됨): {}", sql);
				}
			}
		}
	}


}
