package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
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
@ApiProduct(ApiProduct.P02)
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

    // 결재상신/취소는 PC 업무일지 관리와 같은 로직을 쓴다. 두 화면이 갈라지면
    // 결재선 해석이 달라져 문서 상태가 어긋난다.
    @Autowired
    mes.app.AS.service.DailyManageService dailyManageService;

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

        Map<String, Object> userInfo = dailyReportService.getUserInfo(personId);
        if (userInfo == null) {
            result.success = false;
            result.message = "사원 정보를 찾을 수 없습니다.";
            return result;
        }
        String perid   = String.valueOf(userInfo.get("perid")).trim().replaceFirst("^p", "");
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

    // ── 파일 다운로드 ──────────────────────────────────────────
    @GetMapping("/download")
    public void download(
            @RequestParam(value = "filepath")  String filepath,
            @RequestParam(value = "filesvnm")  String filesvnm,
            HttpServletRequest request,
            javax.servlet.http.HttpServletResponse response) {

        String objectKey = filepath + "/" + filesvnm;
        try (software.amazon.awssdk.core.ResponseInputStream<software.amazon.awssdk.services.s3.model.GetObjectResponse> s3Stream
                     = storageService.download(objectKey);
             java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(response.getOutputStream())) {

            String encodedFilename = "attachment; filename*=UTF-8''" +
                    java.net.URLEncoder.encode(filesvnm, "UTF-8");
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", encodedFilename);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = s3Stream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();

        } catch (Exception e) {
            log.error("업무일지 파일 다운로드 오류 (key={}): {}", objectKey, e.getMessage(), e);
            try {
                response.sendError(javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "다운로드 오류");
            } catch (Exception ignored) {}
        }
    }

    // ── 업무일지 수정 ──────────────────────────────────────────
    @PostMapping("/update_status")
    public AjaxResult updateStatus(
            @RequestParam(value = "rptdate")                String rptdate,
            @RequestParam(value = "perid")                  String perid,
            @RequestParam(value = "rptnum")                 String rptnum,
            @RequestParam(value = "spjangcd")               String spjangcd,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "equpcd",    required = false) String equpcd,
            @RequestParam(value = "wkcd",      required = false) String wkcd,
            @RequestParam(value = "frtime",    required = false) String frtime,
            @RequestParam(value = "totime",    required = false) String totime,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "filesvnm",  required = false) String filesvnm,
            @RequestParam(value = "filepath",  required = false) String filepath,
            @RequestParam(value = "fileDeleted", required = false, defaultValue = "0") String fileDeleted,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String custcd = (String) tenantInfo.get("custcd");
        try {
            // 기존 파일 삭제 처리
            if ("1".equals(fileDeleted)) {
                Map<String, Object> fileInfo = dailyReportService.getStatusOne(custcd, spjangcd, rptdate, perid, rptnum);
                if (fileInfo != null) {
                    String oldFilesvnm = (String) fileInfo.get("filesvnm");
                    String oldFilepath  = (String) fileInfo.get("filepath");
                    if (oldFilesvnm != null && !oldFilesvnm.isBlank()
                            && oldFilepath != null && !oldFilepath.isBlank()) {
                        try {
                            storageService.delete(oldFilepath + "/" + oldFilesvnm);
                        } catch (Exception e) {
                            log.warn("NCP 기존 파일 삭제 실패 (무시): {}/{}", oldFilepath, oldFilesvnm);
                        }
                    }
                }
            }

            dailyReportService.updateStatus(custcd, spjangcd, rptdate, perid, rptnum,
                    actcd, actnm, equpcd, wkcd, frtime, totime, remark,
                    "1".equals(fileDeleted) ? filesvnm : null,
                    "1".equals(fileDeleted) ? filepath  : null,
                    fileDeleted);
            result.success = true;
            result.message = "수정되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 수정 오류", e);
            result.success = false;
            result.message = "수정 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 결재상신 / 상신취소 ────────────────────────────────────
    // 결재는 상세(TB_E038) 한 건이 아니라 그 날짜의 업무일지 전체(TB_E037 헤드) 단위다.

    /** 로그인 사용자의 custcd / spjangcd / perid(순수 사번). 못 구하면 null. */
    private Map<String, String> resolveOwner(Authentication auth) {
        User user = (User) auth.getPrincipal();
        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) return null;

        Map<String, Object> userInfo =
                dailyReportService.getUserInfo(((Number) tenantInfo.get("personid")).intValue());
        if (userInfo == null) return null;

        return Map.of(
                "custcd",   (String) tenantInfo.get("custcd"),
                "spjangcd", (String) tenantInfo.get("spjangcd"),
                // TB_E037/TB_E038/tb_e080 은 'p' 없는 순수 사번을 쓴다.
                "perid",    String.valueOf(userInfo.get("perid")).trim().replaceFirst("^p", ""));
    }

    @PostMapping("/submit_approval")
    public AjaxResult submitApproval(@RequestParam("rptdate") String rptdate, Authentication auth) {
        AjaxResult result = new AjaxResult();
        Map<String, String> me = resolveOwner(auth);
        if (me == null) { result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result; }

        rptdate = rptdate.replaceAll("-", "");
        try {
            // 웹에서 만든 업무일지는 결재문서번호가 없다. 상신 시점에 채번한다.
            String appnum = dailyManageService.ensureAppnum(
                    me.get("custcd"), me.get("spjangcd"), rptdate, me.get("perid"));
            if (appnum == null || appnum.isEmpty()) {
                result.success = false;
                result.message = "해당 일자의 업무일지를 찾을 수 없습니다.";
                return result;
            }

            String today = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            String errorMsg = dailyManageService.submitApproval(
                    me.get("custcd"), me.get("spjangcd"), appnum, rptdate, me.get("perid"), today);

            if (errorMsg != null) { result.success = false; result.message = errorMsg; return result; }
            result.success = true;
            result.message = "결재상신 되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 결재상신 오류 rptdate={}", rptdate, e);
            result.success = false;
            result.message = "결재상신 중 오류가 발생하였습니다.";
        }
        return result;
    }

    @PostMapping("/cancel_approval")
    public AjaxResult cancelApproval(@RequestParam("rptdate") String rptdate,
                                     @RequestParam("appnum")  String appnum,
                                     Authentication auth) {
        AjaxResult result = new AjaxResult();
        Map<String, String> me = resolveOwner(auth);
        if (me == null) { result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result; }

        rptdate = rptdate.replaceAll("-", "");
        try {
            // 상신취소는 tb_e080 행을 지운다. 결재가 시작된 뒤에 허용하면 처리된 이력까지 사라진다.
            if (dailyManageService.hasAnyApproval(me.get("custcd"), me.get("spjangcd"), appnum)) {
                result.success = false;
                result.message = "이미 결재가 진행된 문서는 상신취소할 수 없습니다.";
                return result;
            }
            dailyManageService.cancelApproval(
                    me.get("custcd"), me.get("spjangcd"), appnum, rptdate, me.get("perid"));
            result.success = true;
            result.message = "결재상신이 취소되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 상신취소 오류 rptdate={}, appnum={}", rptdate, appnum, e);
            result.success = false;
            result.message = "상신취소 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 업무일지 현황 조회 ─────────────────────────────────────
    @GetMapping("/read_status")
    public AjaxResult getStatusList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String custcd = (String) tenantInfo.get("custcd");

        // 본인 perid 도출 (save()와 동일 로직)
        int personId = ((Number) tenantInfo.get("personid")).intValue();
        Map<String, Object> userInfo = dailyReportService.getUserInfo(personId);
        if (userInfo == null) {
            result.success = false;
            result.message = "사원 정보를 찾을 수 없습니다.";
            return result;
        }
        String perid = String.valueOf(userInfo.get("perid")).trim().replaceFirst("^p", "");

        result.data = dailyReportService.getStatusList(custcd, spjangcd, fromDate, toDate, actnm, perid);
        return result;
    }

    // ── 업무일지 삭제 ──────────────────────────────────────────
    @PostMapping("/delete_status")
    public AjaxResult deleteStatus(
            @RequestParam(value = "rptdate")  String rptdate,
            @RequestParam(value = "perid")    String perid,
            @RequestParam(value = "rptnum")   String rptnum,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String custcd = (String) tenantInfo.get("custcd");
        try {
            // 파일 정보 조회
            Map<String, Object> fileInfo = dailyReportService.getStatusOne(custcd, spjangcd, rptdate, perid, rptnum);
            if (fileInfo != null) {
                String filesvnm = (String) fileInfo.get("filesvnm");
                String filepath  = (String) fileInfo.get("filepath");
                if (filesvnm != null && !filesvnm.isBlank() && filepath != null && !filepath.isBlank()) {
                    try {
                        storageService.delete(filepath + "/" + filesvnm);
                    } catch (Exception e) {
                        log.warn("NCP 파일 삭제 실패 (무시하고 DB 삭제 진행): {}/{}", filepath, filesvnm);
                    }
                }
            }
            dailyReportService.deleteStatus(custcd, spjangcd, rptdate, perid, rptnum);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }
}
