package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantUserService;
import mes.app.files.NcpObjectStorageService;
import mes.app.mobile.Service.DailyReportService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/daily_report")
public class DailyReportController {

    private static final List<String> BLOCKED_EXT = Arrays.asList(
            "py", "js", "aspx", "asp", "jsp", "php", "cs", "ini", "htaccess", "exe", "dll");

    @Autowired
    DailyReportService dailyReportService;

    @Autowired
    TenantUserService tenantUserService;

    @Autowired
    NcpObjectStorageService storageService;

    // ── 공통: tenantInfo + userInfo 합쳐서 반환 ───────────────
    private Map<String, Object> getTenantUserInfo(String username) {
        return tenantUserService.getUserInfo(username);
    }

    // ── 사용자 정보 조회 ───────────────────────────────────────
    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }
        int personId = ((Number) tenantInfo.get("personid")).intValue();

        Map<String, Object> userInfo = dailyReportService.getUserInfo(personId);
        if (userInfo == null) {
            result.message = "사원 정보를 찾을 수 없습니다.";
            return result;
        }

        // tenantInfo + userInfo 합치고 login_id 추가해서 반환
        userInfo.putAll(tenantInfo);
        userInfo.put("username", user.getUsername());

        result.data = userInfo;
        return result;
    }

    // ── 구분 목록 조회 (TB_E021) ───────────────────────────────
    @GetMapping("/read_gubun")
    public AjaxResult getGubunList(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }

        String custcd   = (String) tenantInfo.get("custcd");
        String spjangcd = (String) tenantInfo.get("spjangcd");

        result.data = dailyReportService.getGubunList(custcd, spjangcd);
        return result;
    }

    // ── 행선지 목록 조회 (TB_E601) ────────────────────────────
    @GetMapping("/read_dest")
    public AjaxResult getDestList(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }

        String custcd   = (String) tenantInfo.get("custcd");
        String spjangcd = (String) tenantInfo.get("spjangcd");

        result.data = dailyReportService.getDestList(custcd, spjangcd);
        return result;
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    @GetMapping("/read_equp")
    public AjaxResult getEqupList(
            @RequestParam(value = "actcd") String actcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }

        String custcd   = (String) tenantInfo.get("custcd");
        String spjangcd = (String) tenantInfo.get("spjangcd");

        result.data = dailyReportService.getEqupList(custcd, spjangcd, actcd);
        return result;
    }

    // ── 파일 업로드 ────────────────────────────────────────────
    @PostMapping("/upload_file")
    public AjaxResult uploadFile(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String dbKey = user.getDbKey();

        try {
            long fileSize = file.getSize();
            if (fileSize > 20971520L) {
                result.success = false;
                result.message = "파일 크기가 20MB를 초과합니다.";
                return result;
            }

            String originalName = file.getOriginalFilename();
            if (originalName == null || !originalName.contains(".")) {
                result.success = false;
                result.message = "파일명이 올바르지 않습니다.";
                return result;
            }

            String ext = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
            if (BLOCKED_EXT.contains(ext)) {
                result.success = false;
                result.message = "허용되지 않는 파일 형식입니다.";
                return result;
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
            log.error("[DailyReport] 파일 업로드 오류", e);
            result.success = false;
            result.message = "파일 업로드 중 오류가 발생하였습니다.";
        }

        return result;
    }

    // ── 업무일지 등록 ──────────────────────────────────────────
    @PostMapping("/save")
    public AjaxResult saveDailyReport(
            @RequestParam(value = "writeDate")                  String writeDate,
            @RequestParam(value = "wkcd",     required = false) String wkcd,
            @RequestParam(value = "actcd",    required = false) String actcd,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "frtime",   required = false) String frtime,
            @RequestParam(value = "totime",   required = false) String totime,
            @RequestParam(value = "equpcd",   required = false) String equpcd,
            @RequestParam(value = "equpnm",   required = false) String equpnm,
            @RequestParam(value = "remark",   required = false) String remark,
            @RequestParam(value = "filesvnm", required = false) String filesvnm,
            @RequestParam(value = "filepath", required = false) String filepath,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }

        int    personId = ((Number) tenantInfo.get("personid")).intValue();
        String custcd   = (String) tenantInfo.get("custcd");
        String spjangcd = (String) tenantInfo.get("spjangcd");

        // perid는 TB_JA001 기준 (p+사번 형식) — DailyReportService에서 조회
        Map<String, Object> userInfo = dailyReportService.getUserInfo(personId);
        if (userInfo == null) {
            result.success = false;
            result.message = "사원 정보를 찾을 수 없습니다.";
            return result;
        }
        String perid  = String.valueOf(userInfo.get("perid")).trim();
        String rptdate = writeDate.replaceAll("-", "");

        try {
            dailyReportService.saveDailyReport(
                    custcd, spjangcd, rptdate, perid,
                    wkcd, actcd, actnm,
                    frtime, totime,
                    equpcd, remark,
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
}
