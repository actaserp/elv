package mes.app.system;

import lombok.extern.slf4j.Slf4j;

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

@Slf4j
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
		@RequestParam(value="person_code", required = false) String person_code,
		@RequestParam(value="tel", required = false) String tel,
		@RequestParam(value="spjangcd", required = false) String selectedSpjangcd,
		@RequestParam(value="PersonGroup_id", required = false) Integer PersonGroup_id,
		HttpServletRequest request,
		Authentication auth
	) {

		AjaxResult result = new AjaxResult();
		String dbKey = TenantContext.getDbKey();
		String spjangcd = TenantContext.get();
		String effectiveSpjangcd = (selectedSpjangcd != null && !selectedSpjangcd.isEmpty()) ? selectedSpjangcd : spjangcd;

		// 근무조 기본값 주간(1)
		int personGroupId = (PersonGroup_id != null) ? PersonGroup_id : 1;

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

		// ── 1단계: 본사 auth_user + user_profile 저장 ──────────
		user.setSpjangcd(effectiveSpjangcd);
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

		// ── 2단계: person 처리 (신규 + person_code 있을 때) ────
		// personid가 없을 때만 person INSERT/조회 진행
		if (personid == null || personid.equals("")) {
			try {
				String personChkSql = "SELECT id FROM person WHERE Code = :Code AND spjangcd = :spjangcd";
				MapSqlParameterSource personChkParam = new MapSqlParameterSource();
				personChkParam.addValue("Code", person_code);
				personChkParam.addValue("spjangcd", effectiveSpjangcd);
				List<Map<String, Object>> existPersonList = this.tenantSqlRunner.getRows(personChkSql, personChkParam);

				Integer resolvedPersonId = null;

				if (!existPersonList.isEmpty()) {
					// 이미 person 존재 → id 재활용 + 근무조 갱신
					resolvedPersonId = ((Number) existPersonList.get(0).get("id")).intValue();
					user.setPersonid(resolvedPersonId);
					this.userRepository.save(user);

					MapSqlParameterSource pgUpdParam = new MapSqlParameterSource();
					pgUpdParam.addValue("PersonGroup_id", personGroupId);
					pgUpdParam.addValue("id", resolvedPersonId);
					pgUpdParam.addValue("spjangcd", effectiveSpjangcd);
					this.tenantSqlRunner.execute(
						"UPDATE person SET [PersonGroup_id] = :PersonGroup_id WHERE id = :id AND spjangcd = :spjangcd",
						pgUpdParam);

				} else if (person_code != null && !person_code.isEmpty()) {
					// person 없음 → INSERT
					String rtdate = null;
					MapSqlParameterSource ja001Param = new MapSqlParameterSource();
					ja001Param.addValue("perid", person_code);
					ja001Param.addValue("spjangcd", effectiveSpjangcd);
					List<Map<String, Object>> ja001Rows = this.tenantSqlRunner.getRows(
						"SELECT rtdate FROM TB_JA001 WHERE perid = :perid AND spjangcd = :spjangcd",
						ja001Param
					);
					if (!ja001Rows.isEmpty() && ja001Rows.get(0).get("rtdate") != null) {
						rtdate = ja001Rows.get(0).get("rtdate").toString();
					}
					if (rtdate == null || rtdate.isEmpty()) {
						rtdate = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
					}

					String personInsertSql = """
						INSERT INTO person
						([Name], [Code], [Depart_id], [Factory_id], spjangcd, rtflag, [PersonGroup_id], rtdate, _created, _creater_id)
						OUTPUT INSERTED.id
						VALUES (:Name, :Code, :Depart_id, :Factory_id, :spjangcd, '0', :PersonGroup_id, :rtdate, SYSDATETIMEOFFSET(), :creater_id)
					""";
					MapSqlParameterSource personParam = new MapSqlParameterSource();
					personParam.addValue("Name", Name);
					personParam.addValue("Code", person_code);
					personParam.addValue("Depart_id", 28);
					personParam.addValue("Factory_id", Factory_id);
					personParam.addValue("spjangcd", effectiveSpjangcd);
					personParam.addValue("rtdate", rtdate);
					personParam.addValue("PersonGroup_id", personGroupId);
					personParam.addValue("creater_id", loginUser.getId());

					Map<String, Object> insertedRow = this.tenantSqlRunner.getRow(personInsertSql, personParam);
					if (insertedRow != null) {
						resolvedPersonId = ((Number) insertedRow.get("id")).intValue();
						user.setPersonid(resolvedPersonId);
						this.userRepository.save(user);
					}
				}

				// ── 3단계: person 확정 후 사업체 auth_user 처리 ──
				// person INSERT/조회가 완료된 시점에 personid가 확정됨
				if (person_code != null && !person_code.isEmpty()) {
					String tenantUsername = person_code.startsWith("p")
							? person_code.substring(1)
							: person_code;

					MapSqlParameterSource tenantChkParam = new MapSqlParameterSource();
					tenantChkParam.addValue("username",  tenantUsername);
					tenantChkParam.addValue("spjangcd",  effectiveSpjangcd);
					List<Map<String, Object>> existTenantAuth = this.tenantSqlRunner.getRows(
						"SELECT id, personid FROM auth_user WHERE username = :username AND spjangcd = :spjangcd",
						tenantChkParam
					);

					if (existTenantAuth.isEmpty()) {
						// 사업체 auth_user 없음 → INSERT (resolvedPersonId 확정 후)
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
						tenantAuthParam.addValue("last_name",    Name);
						tenantAuthParam.addValue("email",        email != null ? email : "");
						tenantAuthParam.addValue("is_staff",     false);
						tenantAuthParam.addValue("is_active",    is_active);
						tenantAuthParam.addValue("spjangcd",     effectiveSpjangcd);
						tenantAuthParam.addValue("tel",          tel);
						tenantAuthParam.addValue("personid",     resolvedPersonId);
						this.tenantSqlRunner.execute(tenantAuthSql, tenantAuthParam);

					} else {
						// 사업체 auth_user 이미 존재 → 빈 값만 보완 UPDATE
						String updateSql = """
							UPDATE auth_user SET
							    first_name = CASE WHEN first_name IS NULL OR first_name = '' THEN :first_name ELSE first_name END,
							    last_name  = CASE WHEN last_name  IS NULL OR last_name  = '' THEN :last_name  ELSE last_name  END,
							    email      = CASE WHEN email      IS NULL OR email      = '' THEN :email      ELSE email      END,
							    tel        = CASE WHEN tel        IS NULL OR tel        = '' THEN :tel        ELSE tel        END,
							    personid   = CASE WHEN personid   IS NULL                   THEN :personid   ELSE personid   END,
							    is_active  = :is_active
							WHERE username = :username AND spjangcd = :spjangcd
						""";
						MapSqlParameterSource updateParam = new MapSqlParameterSource();
						updateParam.addValue("first_name", Name);
						updateParam.addValue("last_name",  Name);
						updateParam.addValue("email",      email != null ? email : "");
						updateParam.addValue("tel",        tel);
						updateParam.addValue("personid",   resolvedPersonId);
						updateParam.addValue("is_active",  is_active);
						updateParam.addValue("username",   tenantUsername);
						updateParam.addValue("spjangcd",   effectiveSpjangcd);
						this.tenantSqlRunner.execute(updateSql, updateParam);
					}
				}

			} catch (Exception e) {
				result.success = true;
				result.message = "저장되었으나 Person 연동에 실패했습니다: " + e.getMessage();
				result.data = user;
				return result;
			}

		} else {
			// ── 수정 시 보완 로직 ──────────────────────────────
			// personid가 있는 수정 케이스에서도 사업체 DB 누락 데이터 보완
			if (person_code != null && !person_code.isEmpty()) {
				try {
					String tenantUsername = person_code.startsWith("p")
							? person_code.substring(1)
							: person_code;

					// 사업체 person 누락 확인 및 보완
					MapSqlParameterSource personChkParam = new MapSqlParameterSource();
					personChkParam.addValue("id", Integer.valueOf(personid));
					personChkParam.addValue("spjangcd", effectiveSpjangcd);
					List<Map<String, Object>> existPersonList = this.tenantSqlRunner.getRows(
						"SELECT id FROM person WHERE id = :id AND spjangcd = :spjangcd",
						personChkParam
					);

					if (existPersonList.isEmpty()) {
						// person이 없으면 INSERT
						String rtdate = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
						String personInsertSql = """
							INSERT INTO person
							([Name], [Code], [Depart_id], [Factory_id], spjangcd, rtflag, [PersonGroup_id], rtdate, _created, _creater_id)
							VALUES (:Name, :Code, :Depart_id, :Factory_id, :spjangcd, '0', :PersonGroup_id, :rtdate, SYSDATETIMEOFFSET(), :creater_id)
						""";
						MapSqlParameterSource personParam = new MapSqlParameterSource();
						personParam.addValue("Name",      Name);
						personParam.addValue("Code",      person_code);
						personParam.addValue("Depart_id", 28);
						personParam.addValue("Factory_id", Factory_id);
						personParam.addValue("spjangcd",  effectiveSpjangcd);
						personParam.addValue("rtdate",    rtdate);
						personParam.addValue("PersonGroup_id", personGroupId);
						personParam.addValue("creater_id", loginUser.getId());
						this.tenantSqlRunner.execute(personInsertSql, personParam);
					} else {
						// person 존재 → 근무조(PersonGroup_id) 갱신
						MapSqlParameterSource pgUpdParam = new MapSqlParameterSource();
						pgUpdParam.addValue("PersonGroup_id", personGroupId);
						pgUpdParam.addValue("id", Integer.valueOf(personid));
						pgUpdParam.addValue("spjangcd", effectiveSpjangcd);
						this.tenantSqlRunner.execute(
							"UPDATE person SET [PersonGroup_id] = :PersonGroup_id WHERE id = :id AND spjangcd = :spjangcd",
							pgUpdParam);
					}

					// 사업체 auth_user personid 누락 확인 및 보완
					MapSqlParameterSource tenantChkParam = new MapSqlParameterSource();
					tenantChkParam.addValue("username", tenantUsername);
					tenantChkParam.addValue("spjangcd", effectiveSpjangcd);
					List<Map<String, Object>> existTenantAuth = this.tenantSqlRunner.getRows(
						"SELECT id, personid FROM auth_user WHERE username = :username AND spjangcd = :spjangcd",
						tenantChkParam
					);

					if (!existTenantAuth.isEmpty()) {
						Object currentPersonId = existTenantAuth.get(0).get("personid");
						if (currentPersonId == null) {
							// personid 누락 → UPDATE로 보완
							String fixSql = """
								UPDATE auth_user SET
								    personid  = :personid,
								    first_name = CASE WHEN first_name IS NULL OR first_name = '' THEN :first_name ELSE first_name END,
								    last_name  = CASE WHEN last_name  IS NULL OR last_name  = '' THEN :last_name  ELSE last_name  END,
								    tel        = CASE WHEN tel        IS NULL OR tel        = '' THEN :tel        ELSE tel        END
								WHERE username = :username AND spjangcd = :spjangcd
							""";
							MapSqlParameterSource fixParam = new MapSqlParameterSource();
							fixParam.addValue("personid",   Integer.valueOf(personid));
							fixParam.addValue("first_name", Name);
							fixParam.addValue("last_name",  Name);
							fixParam.addValue("tel",        tel);
							fixParam.addValue("username",   tenantUsername);
							fixParam.addValue("spjangcd",   effectiveSpjangcd);
							this.tenantSqlRunner.execute(fixSql, fixParam);
						}
					}

				} catch (Exception e) {
					// 보완 실패는 경고만 남기고 저장은 유지
					result.success = true;
					result.message = "저장되었으나 사업체DB 보완에 실패했습니다: " + e.getMessage();
					result.data = user;
					return result;
				}
			}
		}

		// ── 활성화여부 동기화 (분기와 무관하게 항상) ────────────────────────
		// 본사 auth_user 는 위에서 무조건 갱신되는데(user.setActive), 사업체 auth_user 는
		// 신규 등록 분기에서만 갱신돼서 '기존 사용자의 활성화여부 변경' 이 사업체에 반영되지 않았다.
		// 그 결과 사용자관리 화면(본사 조회)과 사원검색 팝업(사업체 조회)의 값이 어긋났다.
		// 여기서 한 번 더 맞춰준다. 신규 분기에서 이미 같은 값을 넣었어도 결과는 동일하다.
		try {
			String syncUsername = (person_code != null && !person_code.isEmpty())
					? (person_code.startsWith("p") ? person_code.substring(1) : person_code)
					: login_id;   // person_code 가 안 넘어오는 경로 대비 (사업체 username = 사번 = login_id)

			if (syncUsername != null && !syncUsername.isEmpty()) {
				MapSqlParameterSource syncParam = new MapSqlParameterSource();
				syncParam.addValue("is_active", is_active);
				syncParam.addValue("username",  syncUsername);
				syncParam.addValue("spjangcd",  effectiveSpjangcd);
				this.tenantSqlRunner.execute(
					"UPDATE auth_user SET is_active = :is_active WHERE username = :username AND spjangcd = :spjangcd",
					syncParam);
			}
		} catch (Exception e) {
			// 동기화 실패로 저장 자체를 되돌리지는 않는다. 로그만 남긴다.
			log.warn("사업체 auth_user.is_active 동기화 실패 - login_id={}, {}", login_id, e.getMessage());
		}

		result.data = user;
		return result;
	}

	// user 삭제
	@PostMapping("/delete")
	@Transactional
	public AjaxResult deleteUser(@RequestParam("id") int id) {
		AjaxResult result = new AjaxResult();

		try {
			// 삭제할 유저 정보 조회
			User user = this.userRepository.getUserById(id);
			if (user == null) {
				result.success = false;
				result.message = "사용자를 찾을 수 없습니다.";
				return result;
			}

			Integer personid = user.getPersonid();

			// 1. 본사DB auth_user + user_profile 삭제 (JPA cascade로 user_profile 함께 삭제)
			this.userRepository.deleteById(id);

			// 2. 사업체DB auth_user 삭제
			if (personid != null) {
				MapSqlParameterSource tenantAuthParam = new MapSqlParameterSource();
				tenantAuthParam.addValue("personid", personid);
				this.tenantSqlRunner.execute(
					"DELETE FROM auth_user WHERE personid = :personid",
					tenantAuthParam
				);

				// 3. 사업체DB person 삭제
				MapSqlParameterSource personParam = new MapSqlParameterSource();
				personParam.addValue("id", personid);
				this.tenantSqlRunner.execute(
					"DELETE FROM person WHERE id = :id",
					personParam
				);
			}

			result.success = true;
			result.message = "삭제되었습니다.";

		} catch (Exception e) {
			result.success = false;
			result.message = "삭제 중 오류가 발생하였습니다: " + e.getMessage();
		}

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

	// 사원 검색 팝업.
	// activeOnly=true 로 부르면 재직여부(TB_JA001.rtclafi / TB_XUSERS.useyn) 대신
	// 사용자관리의 활성화여부(auth_user.is_active)만 보고 퇴사자도 함께 내려준다.
	// 기본값 false 는 기존 동작(재직자만) 그대로다.
	@GetMapping("/getPerson")
	public AjaxResult getAccSearchList(
			@RequestParam(value="searchCode", required=false) String code,
			@RequestParam(value="searchName", required=false) String name,
			@RequestParam(value="activeOnly", required=false, defaultValue="false") boolean activeOnly
	) {

		AjaxResult result = new AjaxResult();
		String spjangcd = TenantContext.get();
		List<Map<String, Object>> items = this.userService.getPSearchitem(code,name,spjangcd,activeOnly);
		result.data = items;
		return result;
	}






}
