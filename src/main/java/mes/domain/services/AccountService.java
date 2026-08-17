package mes.domain.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import mes.domain.entity.User;
import mes.domain.repository.UserRepository;
import mes.domain.security.Pbkdf2Sha256;

import javax.sql.DataSource;

 /*인터페이스 분리는 추후에 고민하자*/
@Service
public class AccountService {

	@Autowired
	UserRepository userRepository;

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	@Qualifier("mainDataSource")
	DataSource mainDataSource;
		
	public User getUser(String username) {
		User user =null;
		if(username==null || username.length()==0 ) {
			return user;
		}			
		
		Optional<User> optUser = this.userRepository.findByUsername(username);
		
		if(optUser.isEmpty()==false) {
			user = optUser.get();
		}	
		
		return user;		
	}
	
	public boolean checkUserPassword(String plainText, User user) {
		
		String hashedPassword = user.getPassword();		
		boolean result = Pbkdf2Sha256.verification(plainText, hashedPassword);		
		return result;	
	}

	// 로그인&로그아웃시 login_log 테이블에 이력 저장
	public void saveLoginLog(String type, Authentication auth, HttpServletRequest request) {

		User user = (User) auth.getPrincipal();

		String clientIp = request.getHeader("X-Forwarded-For");
		if (clientIp == null || clientIp.isBlank()) {
			clientIp = request.getRemoteAddr();
		} else {
			clientIp = clientIp.split(",")[0].trim();
		}

        MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("type", type);
		paramMap.addValue("IPAddress", clientIp);
		paramMap.addValue("UserId", user.getId());
		paramMap.addValue("spjangcd", user.getDbKey());

		String sql = """
				insert into login_log("Type", "IPAddress", _created, "User_id", "spjangcd")
                VALUES (:type, :IPAddress ::inet, now(),:UserId, :spjangcd)
				""";

		// login_log는 메인 DB 테이블이므로 mainDataSource를 직접 사용
		new NamedParameterJdbcTemplate(mainDataSource).update(sql, paramMap);
	}


	 public List<Map<String, String>> findspjangcd() {

		 MapSqlParameterSource dicParam = new MapSqlParameterSource();

		 String sql = """
                SELECT spjangcd, spjangnm
                FROM tb_xa012
            """;
		 // SQL 실행
		 List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, dicParam);

		 List<Map<String, String>> result = rows.stream()
				 .map(row -> {
					 Map<String, String> map = new HashMap<>();
					 map.put("spjangcd", (String) row.get("spjangcd"));
					 map.put("spjangnm", (String) row.get("spjangnm"));
					 return map;
				 })
				 .toList();

		 return result;
	 }

	 public List<Map<String, Object>> getBillPlans() {

		 MapSqlParameterSource dicParam = new MapSqlParameterSource();

		 String sql = """
                SELECT * FROM bill_plans
            """;
		 // SQL 실행
		 List<Map<String, Object>> results = this.sqlRunner.getRows(sql, dicParam);

		 return results;
	 }

	 /**
	  * 구독 팝업용 상품 목록 조회 (product 테이블 기준).
	  * login.html 구독 정보 팝업에서 사용자가 상품을 선택할 때 사용.
	  */
	 public List<Map<String, Object>> getProducts() {
		 MapSqlParameterSource dicParam = new MapSqlParameterSource();
		 String sql = """
                SELECT product_cd,
                       product_nm,
                       price,
                       api_call_limit,
                       extra_unit_price,
                       remark,
                       sort_order
                FROM product
                WHERE useyn = '1'
                ORDER BY sort_order
            """;
		 return this.sqlRunner.getRows(sql, dicParam);
	 }

	 /**
	  * 등급(bill_plans) 목록 조회 — 확장성 보존용.
	  * 현재 UI에서는 숨김 처리되며 기본값 Standard(id=1)가 자동 적용됨.
	  */
	 public List<Map<String, Object>> getGrades() {
		 MapSqlParameterSource dicParam = new MapSqlParameterSource();
		 String sql = """
                SELECT id, name, price, api_call_limit, extra_api_unit_price, remark
                FROM bill_plans
                WHERE is_active = true
                ORDER BY id
            """;
		 return this.sqlRunner.getRows(sql, dicParam);
	 }



}
