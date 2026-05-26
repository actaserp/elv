package mes.app.system;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;

import mes.app.common.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.system.service.UserService;
import mes.domain.entity.RelationData;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.RelationDataRepository;
import mes.domain.repository.UserRepository;
import mes.domain.security.Pbkdf2Sha256;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;

@RestController
@RequestMapping("/api/system/user")
public class UserController {
	
	@Autowired
	private UserService userService;
	
	@Autowired
	UserRepository userRepository;
	
	@Autowired
	RelationDataRepository relationDataRepository;
	
	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner sqlRunner;

	@Autowired
	SqlRunner tenantSqlRunner;	// 테넌트 DB 전용

    @Autowired
    private JdbcTemplate jdbcTemplate;


	// 사용자 리스트 조회
	@GetMapping("/read")
	public AjaxResult getUserList(
			@RequestParam(value="group", required=false) Integer group,
			@RequestParam(value="keyword", required=false) String keyword,
			@RequestParam(value="depart_id", required=false) Integer departId,
			@RequestParam(value="username", required=false) String username,
			HttpServletRequest request,
			Authentication auth) {
		
		AjaxResult result = new AjaxResult();
		
		User user = (User)auth.getPrincipal();
		boolean superUser = user.getSuperUser();
		String spjangcd = TenantContext.getDbKey();
		
		if (!superUser) {
			superUser = user.getUserProfile().getUserGroup().getCode().equals("dev");
		}
		
		List<Map<String, Object>> items = this.userService.getUserList(superUser, group, keyword, username, departId,spjangcd);
		
		result.data = items;
		return result;
	}
	
	// 사용자 상세정보 조회
	@GetMapping("/detail")
	public AjaxResult getUserDetail(
			@RequestParam(value="id") Integer id,
			HttpServletRequest request) {
		
		Map<String, Object> item = this.userService.getUserDetail(id);
		AjaxResult result = new AjaxResult();
		result.data = item;
		return result;
	}
	
	// 사용자 그룹 조회
	@GetMapping("/user_grp_list")
	public AjaxResult getUserGrpList(
			@RequestParam(value="id") Integer id,
			HttpServletRequest request) {
		
		List<Map<String, Object>> items = this.userService.getUserGrpList(id);
		AjaxResult result = new AjaxResult();
		result.data = items;
		return result;
	}
	
	@GetMapping("/getXusersList")
	public AjaxResult getXusersList(
			@RequestParam(value="perid", required = false, defaultValue = "") String perid,
			@RequestParam(value="pernm", required = false, defaultValue = "") String pernm
	) {
		AjaxResult result = new AjaxResult();
		result.data = this.userService.getXusersList(perid, pernm);
		return result;
	}

	@PostMapping("/save")
	@Transactional
	public AjaxResult saveUser(
		@RequestParam(value="id", required = false) Integer id,
		@RequestParam(value="Name") String Name,
		@RequestParam(value="login_id") String login_id,
		@RequestParam(value="email", required = false, defaultValue = "") String email,
		@RequestParam(value="Factory_id", required = false) Integer Factory_id,
		@RequestParam(value="Depart_id", required = false) String Depart_id,
		@RequestParam(value="UserGroup_id", required = false) Integer UserGroup_id,
		@RequestParam(value="lang_code", required = false) String lang_code,
		@RequestParam(value="is_active", required = false) Boolean is_active,
		@RequestParam(value="personid", required = false) String personid,
		@RequestParam(value="person_code", required = false) String person_code,  // ← 추가
		@RequestParam(value="tel", required = false) String tel,
		@RequestParam(value="spjangcd", required = false) String selectedSpjangcd,
		HttpServletRequest request,
		Authentication auth
	) {

		AjaxResult result = new AjaxResult();
		String dbKey = TenantContext.getDbKey();
		String spjangcd = TenantContext.get();

		String sql = null;
		User user = null;
		User loginUser = (User)auth.getPrincipal();
		Timestamp today = new Timestamp(System.currentTimeMillis());
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		boolean username_chk = this.userRepository.findByUsername(login_id).isEmpty();

		if (is_active == null) is_active = false;
		if (lang_code == null || lang_code.isEmpty()) lang_code = "kr";

		// ── 신규 저장 ──────────────────────────────────────────
		if (id == null) {

			if (!username_chk) {
				result.success = false;
				result.message = "중복된 사번이 존재합니다.";
				return result;
			}

			user = new User();
			user.setPassword(Pbkdf2Sha256.encode(login_id.length() >= 4 ? login_id.substring(login_id.length() - 4) : login_id));
			user.setSuperUser(false);
			user.setLast_name(Name);
			user.setIs_staff(false);
			user.setDbKey(dbKey);

			dicParam.addValue("loginUser", loginUser.getId());
			sql = """
					INSERT INTO user_profile
					("_created", "_creater_id", "User_id", "lang_code", "Name", "Factory_id", "UserGroup_id", "Depart_id", "spjangcd") 
					VALUES (now(), :loginUser, :User_id, :lang_code, :name, :Factory_id, :UserGroup_id, :Depart_id, :spjangcd)
			""";

			// ── 기존 수정 ──────────────────────────────────────────
		} else {
			user = this.userRepository.getUserById(id);

			if (!login_id.equals(user.getUsername()) && !username_chk) {
				result.success = false;
				result.message = "중복된 사번이 존재합니다.";
				return result;
			}

			MapSqlParameterSource countParam = new MapSqlParameterSource();
			countParam.addValue("User_id", id);
			Map<String, Object> countRow = this.sqlRunner.getRow(
				"SELECT COUNT(*) AS cnt FROM user_profile WHERE \"User_id\" = :User_id",
				countParam
			);
			int count = countRow != null ? ((Number) countRow.get("cnt")).intValue() : 0;

			if (count == 0) {
				sql = """
						INSERT INTO user_profile 
						("_created", "_creater_id", "User_id", "lang_code", "Name", "Factory_id", "UserGroup_id", "Depart_id", "spjangcd") 
						VALUES (now(), :loginUser, :User_id, :lang_code, :name, :Factory_id, :UserGroup_id, :Depart_id, :spjangcd)
					""";
				dicParam.addValue("loginUser", loginUser.getId());
			} else {
				sql = """
						UPDATE user_profile SET
						"lang_code" = :lang_code,
						"Name" = :name,
						"Factory_id" = :Factory_id,
						"UserGroup_id" = :UserGroup_id,
						"Depart_id" = :Depart_id
						WHERE "User_id" = :User_id
						AND "spjangcd" = :spjangcd
				""";
			}
		}

		// ── 1단계: auth_user + user_profile 저장 ───────────────
		// selectedSpjangcd: 폼에서 선택한 사업장 코드. 없으면 현재 테넌트 기본값 사용
		user.setSpjangcd(selectedSpjangcd != null && !selectedSpjangcd.isEmpty() ? selectedSpjangcd : spjangcd);
		user.setUsername(login_id);
		user.setFirst_name(Name);
		user.setEmail(email);
		user.setTel(tel);
		user.setLast_name(Name);
		if (personid != null && !personid.equals("")) {
			user.setPersonid(Integer.valueOf(personid));
		}
		user.setDate_joined(today);
		user.setActive(is_active);

		user = this.userRepository.save(user);

		dicParam.addValue("name", Name);
		dicParam.addValue("UserGroup_id", UserGroup_id);
		dicParam.addValue("Factory_id", Factory_id);
		dicParam.addValue("Depart_id", Depart_id);
		dicParam.addValue("lang_code", lang_code);
		dicParam.addValue("User_id", user.getId());
		dicParam.addValue("spjangcd", dbKey);

		this.sqlRunner.execute(sql, dicParam);

		// ── 1.5단계: 신규 등록 + person_code 있고 + personid 없을 때만 사업체DB auth_user INSERT ──
		if (id == null && person_code != null && !person_code.isEmpty()
				&& (personid == null || personid.isEmpty())) {
			try {
				// person_code = "p001" 형태 → "p" 제거 → "001" 이 사업체 DB username
				String tenantUsername = person_code.startsWith("p")
						? person_code.substring(1)
						: person_code;

				String tenantAuthSql = """
            INSERT INTO auth_user
            (password, last_login, is_superuser, username, first_name, last_name,
             email, is_staff, is_active, date_joined, spjangcd, tel, personid)
            VALUES
            (:password, NULL, :is_superuser, :username, :first_name, :last_name,
             :email, :is_staff, :is_active, GETDATE(), :spjangcd, :tel, :personid)
        """;
				MapSqlParameterSource tenantAuthParam = new MapSqlParameterSource();
				tenantAuthParam.addValue("password",     user.getPassword());
				tenantAuthParam.addValue("is_superuser", false);
				tenantAuthParam.addValue("username",     tenantUsername);
				tenantAuthParam.addValue("first_name",   Name);
				tenantAuthParam.addValue("last_name",    "");
				tenantAuthParam.addValue("email",        email != null ? email : "");
				tenantAuthParam.addValue("is_staff",     false);
				tenantAuthParam.addValue("is_active",    is_active);
				tenantAuthParam.addValue("spjangcd",     user.getSpjangcd());
				tenantAuthParam.addValue("tel",          tel);
				tenantAuthParam.addValue("personid",     user.getPersonid());

				this.tenantSqlRunner.execute(tenantAuthSql, tenantAuthParam);
			} catch (Exception e) {
				result.success = false;
				result.message = "사업체DB auth_user 등록에 실패했습니다: " + e.getMessage();
				return result;
			}
		}

		// ── 2단계: personid 없을 때 MS DB에 person INSERT ──────
		// personid가 비어으면동작(기존등록 사용자가 아닌 신규등록)
		if (personid == null || personid.equals("")) {
			try {
				// person Code 중복 체크
				String personChkSql = "SELECT id FROM person WHERE Code = :Code AND spjangcd = :spjangcd";
				MapSqlParameterSource personChkParam = new MapSqlParameterSource();
				personChkParam.addValue("Code", person_code);
				personChkParam.addValue("spjangcd", selectedSpjangcd != null && !selectedSpjangcd.isEmpty() ? selectedSpjangcd : spjangcd);
				List<Map<String, Object>> existPersonList = this.tenantSqlRunner.getRows(personChkSql, personChkParam);

				if (!existPersonList.isEmpty()) {
					// 이미 존재하면 해당 id를 personid로 사용
					Integer existPersonId = ((Number) existPersonList.get(0).get("id")).intValue();
					user.setPersonid(existPersonId);
					this.userRepository.save(user);
				} else {
					// person INSERT
					String personInsertSql = """
                    INSERT INTO person
                    ([Name], [Code], [Depart_id], [Factory_id], spjangcd, rtflag, _created, _creater_id)
                    OUTPUT INSERTED.id
                    VALUES (:Name, :Code, :Depart_id, :Factory_id, :spjangcd, '0', SYSDATETIMEOFFSET(), :creater_id)
                """;

					MapSqlParameterSource personParam = new MapSqlParameterSource();
					personParam.addValue("Name", Name);
					personParam.addValue("Code", person_code);
					personParam.addValue("Depart_id", 28);
					personParam.addValue("Factory_id", Factory_id);
					personParam.addValue("spjangcd", selectedSpjangcd != null && !selectedSpjangcd.isEmpty() ? selectedSpjangcd : spjangcd);
					personParam.addValue("creater_id", loginUser.getId());

					// ── 3단계: INSERT 후 생성된 id → auth_user.personid 저장 ──
					Map<String, Object> insertedRow = this.tenantSqlRunner.getRow(personInsertSql, personParam);
					if (insertedRow != null) {
						Integer newPersonId = ((Number) insertedRow.get("id")).intValue();
						user.setPersonid(newPersonId);
						this.userRepository.save(user);
					}
				}

			} catch (Exception e) {
				// person 생성 실패 시 롤백 여부는 정책에 따라 결정
				// 현재는 경고만 남기고 user 저장은 유지
				result.success = true;
				result.message = "저장되었으나 Person 연동에 실패했습니다: " + e.getMessage();
				result.data = user;
				return result;
			}
		}

		result.data = user;
		return result;
	}
	
	// user 삭제
	@PostMapping("/delete")
	public AjaxResult deleteUser(@RequestParam("id") int id) {
		this.userRepository.deleteById(id);
		AjaxResult result = new AjaxResult();
		return result;
	}
	
	// user 패스워드 셋팅
	@PostMapping("/passSetting")
	@Transactional
	public AjaxResult userPassSetting(
			@RequestParam(value="id", required = false) Integer id,
			@RequestParam(value="pass1", required = false) String loginPwd,
    		@RequestParam(value="pass2", required = false) String loginPwd2,  		
    		Authentication auth
			) {
		
		User user = null;
        AjaxResult result = new AjaxResult();
        
        if (StringUtils.hasText(loginPwd)==false | StringUtils.hasText(loginPwd2)==false) {
        	result.success=false;
        	result.message="The verification password is incorrect.";
        	return result;
        }
        
        if(loginPwd.equals(loginPwd2)==false) {        	
        	result.success=false;
        	result.message="The verification password is incorrect.";
        	return result;
        }
        
        user = this.userRepository.getUserById(id);
        user.setPassword(Pbkdf2Sha256.encode(loginPwd));        
        this.userRepository.save(user);

		return result;
	}
	
	@PostMapping("/save_user_grp")
	@Transactional
	public AjaxResult saveUserGrp(
			@RequestParam(value="id") Integer id,
			@RequestBody MultiValueMap<String,Object> Q,   		
    		Authentication auth
			) {
		
		User user = (User)auth.getPrincipal();;
		
        AjaxResult result = new AjaxResult();
        
        List<Map<String, Object>> items = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());
        
        List<RelationData> rdList = this.relationDataRepository.findByDataPk1AndTableName1AndTableName2(id,"auth_user", "user_group");
        
        // 등록된 그룹 삭제
        for (int i = 0; i < rdList.size(); i++) {
        	this.relationDataRepository.deleteById(rdList.get(i).getId());
        }
        
        this.relationDataRepository.flush();
        for (int i = 0; i< items.size(); i++) {
        	
        	String check = "";
        	
        	if (items.get(i).get("grp_check") != null) {
        		check = items.get(i).get("grp_check").toString();
        	}
        	
        	if (check.equals("Y")) {
        		RelationData rd = new RelationData();
        		rd.setDataPk1(id);
        		rd.setTableName1("auth_user");
        		rd.setDataPk2(Integer.parseInt(items.get(i).get("grp_id").toString()));
        		rd.setTableName2("user_group");
        		rd.setRelationName("auth_user-user_group");
        		rd.setChar1("Y");
        		rd.set_audit(user);
        		
        		this.relationDataRepository.save(rd);
        	}
        }
        
        
        return result;
	}

	// 사업장 목록 조회 (테넌트 DB tb_xa012)
	@GetMapping("/spjangcd_list")
	public AjaxResult getSpjangcdList() {
		AjaxResult result = new AjaxResult();
		result.data = this.userService.getSpjangcdList();
		return result;
	}

	@GetMapping("/getPerson")
	public AjaxResult getAccSearchList(
			@RequestParam(value="searchCode", required=false) String code,
			@RequestParam(value="searchName", required=false) String name
	) {

		AjaxResult result = new AjaxResult();
		String spjangcd = TenantContext.get();
		List<Map<String, Object>> items = this.userService.getPSearchitem(code,name,spjangcd);
		result.data = items;
		return result;
	}






}
