package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.DailyManageService;
import mes.app.annotation.ApiProduct;
import mes.app.common.TenantUserService;
import mes.app.files.NcpObjectStorageService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@ApiProduct(ApiProduct.P02)
@RestController
@Transactional
@RequestMapping("/api/AS/daily_manage")
public class DailyManageController {

    private static final List<String> BLOCKED_EXT = Arrays.asList(
            "py", "js", "aspx", "asp", "jsp", "php", "cs", "ini", "htaccess", "exe", "dll");

    @Autowired
    DailyManageService dailyManageService;

    @Autowired
    TenantUserService tenantUserService;

    @Autowired
    NcpObjectStorageService storageService;

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner mainSqlRunner;

    // 로그인 사용자가 '사용자(User)' 그룹에 속하는지 — 로그인 사업장(dbKey) 한정, rela_data(우선) + user_profile 확인
    private boolean isUserGroup(User user) {
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("userId", user.getId());
            p.addValue("dbKey",  user.getDbKey());
            String sql = """
                    SELECT COUNT(*) AS cnt FROM (
                        SELECT ug."Code" AS code
                        FROM rela_data rd
                        JOIN user_group ug ON ug.id = rd."DataPk2"
                                          AND ug.spjangcd = :dbKey
                        WHERE rd."RelationName" = 'auth_user-user_group'
                          AND rd."DataPk1" = :userId
                          AND rd."Char1"   = 'Y'
                        UNION ALL
                        SELECT ug."Code"
                        FROM user_profile up
                        JOIN user_group ug ON ug.id = up."UserGroup_id"
                                          AND ug.spjangcd = up.spjangcd
                        WHERE up."User_id" = :userId
                          AND up.spjangcd  = :dbKey
                    ) t WHERE LOWER(t.code) = 'user'
                    """;
            Map<String, Object> row = mainSqlRunner.getRow(sql, p);
            return row != null && row.get("cnt") != null && ((Number) row.get("cnt")).intValue() > 0;
        } catch (Exception e) {
            log.warn("[daily_manage] 사용자 그룹 판별 실패 username={}", user.getUsername(), e);
            return false;
        }
    }

    // User 그룹이면 본인 perid('p' 제거) 반환, 아니면 null
    private String getOwnPeridIfUserGroup(User user) {
        if (!isUserGroup(user)) return null;
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null || userInfo.get("perid") == null) {
            return null;
        }
        return ((String) userInfo.get("perid")).replaceFirst("^p", "");
    }

    // 현재 사용자가 User 그룹인지 여부 (화면 버튼/등록 제어용)
    @GetMapping("/user_group")
    public AjaxResult userGroup(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> data = new HashMap<>();
        data.put("isUserGroup", getOwnPeridIfUserGroup(user) != null);
        result.data = data;
        return result;
    }

    // ── 헤드 목록 조회 (TB_E037 기준) ────────────────────────
    @GetMapping("/read/head")
    public AjaxResult readHead(
            @RequestParam(value = "year")                       String year,
            @RequestParam(value = "month")                      String month,
            @RequestParam(value = "pernm",    required = false) String pernm,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사용자(User) 그룹이면 본인 작성건만 조회, 그 외(관리자 등)는 전체
        String ownPerid = getOwnPeridIfUserGroup(user);

        result.data = dailyManageService.getHeadList(year, month, pernm, spjangcd, ownPerid);
        return result;
    }

    // ── 상세 목록 조회 (TB_E038 기준) ────────────────────────
    @GetMapping("/read/detail")
    public AjaxResult readDetail(
            @RequestParam(value = "custcd")   String custcd,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "rptdate")  String rptdate,
            @RequestParam(value = "perid")    String perid,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = dailyManageService.getDetailList(custcd, spjangcd, rptdate, perid);
        return result;
    }

    // ── 부서 목록 조회 ───────────────────────────────────────
    @GetMapping("/read/dept_list")
    public AjaxResult readDeptList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = dailyManageService.getDeptList(spjangcd);
        return result;
    }

    // ── 부서별 업무보고 조회 ──────────────────────────────────
    @GetMapping("/read/dept_report")
    public AjaxResult readDeptReport(
            @RequestParam(value = "rptdate")                    String rptdate,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "divicd",   required = false) String divicd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = dailyManageService.getDeptReport(rptdate, spjangcd, divicd);
        return result;
    }

    // ── 업무일지 삭제 ─────────────────────────────────────────
    @PostMapping("/delete")
    public AjaxResult delete(
            @RequestParam(value = "custcd")   String custcd,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "rptdate")  String rptdate,
            @RequestParam(value = "perid")    String perid,
            @RequestParam(value = "rptnum")   String rptnum,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사용자(User) 그룹은 본인이 작성한 업무일지만 삭제 가능
        String ownPerid = getOwnPeridIfUserGroup(user);
        if (ownPerid != null && !ownPerid.equals(perid)) {
            result.success = false;
            result.message = "본인이 작성한 업무일지만 삭제할 수 있습니다.";
            return result;
        }

        try {
            dailyManageService.deleteDailyReport(
                    custcd, spjangcd, rptdate, perid, rptnum,
                    user.getDbKey()
            );
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }

        return result;
    }

    // ════════════════════════════════════════════════════════
    //  업무일지 등록 (웹) — 본인 명의로만 저장
    // ════════════════════════════════════════════════════════

    // ── 구분 목록 (TB_E021) ──────────────────────────────────
    @GetMapping("/read_gubun")
    public AjaxResult readGubun(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> t = tenantUserService.getUserInfo(user.getUsername());
        if (t == null) { result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result; }
        result.data = dailyManageService.getGubunList((String) t.get("custcd"), (String) t.get("spjangcd"));
        return result;
    }

    // ── 행선지/현장 목록 (TB_E601) ───────────────────────────
    @GetMapping("/read_dest")
    public AjaxResult readDest(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> t = tenantUserService.getUserInfo(user.getUsername());
        if (t == null) { result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result; }
        result.data = dailyManageService.getDestList((String) t.get("custcd"), (String) t.get("spjangcd"));
        return result;
    }

    // ── 호기 목록 (TB_E611) ──────────────────────────────────
    @GetMapping("/read_equp")
    public AjaxResult readEqup(@RequestParam("actcd") String actcd, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> t = tenantUserService.getUserInfo(user.getUsername());
        if (t == null) { result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result; }
        result.data = dailyManageService.getEqupList((String) t.get("custcd"), (String) t.get("spjangcd"), actcd);
        return result;
    }

    // ── 첨부파일 업로드 (NCP) ────────────────────────────────
    @PostMapping("/upload_file")
    public AjaxResult uploadFile(@RequestParam("file") MultipartFile file, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String dbKey = user.getDbKey();
        try {
            long fileSize = file.getSize();
            if (fileSize > 20971520L) {
                result.success = false; result.message = "파일 크기가 20MB를 초과합니다."; return result;
            }
            String originalName = file.getOriginalFilename();
            if (originalName == null || !originalName.contains(".")) {
                result.success = false; result.message = "파일명이 올바르지 않습니다."; return result;
            }
            String ext = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
            if (BLOCKED_EXT.contains(ext)) {
                result.success = false; result.message = "허용되지 않는 파일 형식입니다."; return result;
            }
            String uuidFileName = UUID.randomUUID().toString() + "." + ext;
            String objectKey    = storageService.buildObjectKey(dbKey, "DAILY_REPORT", uuidFileName);
            String filePrefix   = storageService.getFilePrefix(dbKey, "DAILY_REPORT");

            java.io.File tempFile = java.io.File.createTempFile("daily_", "." + ext);
            try {
                file.transferTo(tempFile);
                try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
                    storageService.upload(objectKey, fis, fileSize,
                            file.getContentType() != null ? file.getContentType() : "application/octet-stream");
                }
            } finally {
                tempFile.delete();
            }
            result.success = true;
            result.message = "파일 업로드 성공";
            result.data = Map.of(
                    "filesvnm", uuidFileName,
                    "filepath", filePrefix,
                    "fileornm", originalName,
                    "fileext",  ext
            );
        } catch (Exception e) {
            log.error("[DailyManage] 파일 업로드 오류", e);
            result.success = false;
            result.message = "파일 업로드 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 업무일지 등록 (항상 본인 명의 저장) ───────────────────
    @PostMapping("/save")
    public AjaxResult save(
            @RequestParam(value = "writeDate")                  String writeDate,
            @RequestParam(value = "wkcd",     required = false) String wkcd,
            @RequestParam(value = "actcd",    required = false) String actcd,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "frtime",   required = false) String frtime,
            @RequestParam(value = "totime",   required = false) String totime,
            @RequestParam(value = "equpcd",   required = false) String equpcd,
            @RequestParam(value = "remark",   required = false) String remark,
            @RequestParam(value = "filesvnm", required = false) String filesvnm,
            @RequestParam(value = "filepath", required = false) String filepath,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> t = tenantUserService.getUserInfo(user.getUsername());
        if (t == null || t.get("perid") == null) {
            result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result;
        }
        String custcd   = (String) t.get("custcd");
        String spjangcd = (String) t.get("spjangcd");
        // 항상 로그인 본인 perid('p' 제거)로 저장
        String perid    = ((String) t.get("perid")).replaceFirst("^p", "");
        String rptdate  = writeDate.replaceAll("-", "");

        try {
            dailyManageService.saveDailyReport(
                    custcd, spjangcd, rptdate, perid,
                    wkcd, actcd, actnm, frtime, totime, equpcd, remark,
                    filesvnm, filepath
            );
            result.success = true;
            result.message = "업무일지가 등록되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 등록 오류", e);
            result.success = false;
            result.message = "업무일지 등록 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 업무일지 수정 (User면 본인 것만) ──────────────────────
    @PostMapping("/update_status")
    public AjaxResult updateStatus(
            @RequestParam("custcd")                             String custcd,
            @RequestParam("spjangcd")                           String spjangcd,
            @RequestParam("rptdate")                            String rptdate,
            @RequestParam("perid")                              String perid,
            @RequestParam("rptnum")                             String rptnum,
            @RequestParam(value = "wkcd",   required = false)   String wkcd,
            @RequestParam(value = "actcd",  required = false)   String actcd,
            @RequestParam(value = "actnm",  required = false)   String actnm,
            @RequestParam(value = "equpcd", required = false)   String equpcd,
            @RequestParam(value = "frtime", required = false)   String frtime,
            @RequestParam(value = "totime", required = false)   String totime,
            @RequestParam(value = "remark", required = false)   String remark,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사용자(User) 그룹은 본인이 작성한 업무일지만 수정 가능
        String ownPerid = getOwnPeridIfUserGroup(user);
        if (ownPerid != null && !ownPerid.equals(perid)) {
            result.success = false;
            result.message = "본인이 작성한 업무일지만 수정할 수 있습니다.";
            return result;
        }

        try {
            dailyManageService.updateDailyReport(
                    custcd, spjangcd, rptdate, perid, rptnum,
                    wkcd, actcd, actnm, equpcd, frtime, totime, remark
            );
            result.success = true;
            result.message = "수정되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 수정 오류", e);
            result.success = false;
            result.message = "수정 중 오류가 발생하였습니다.";
        }
        return result;
    }
}
