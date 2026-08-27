package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.DailyApprovalService;
import mes.app.common.TenantContext;
import mes.app.common.TenantUserService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 업무일지 결재 Controller
 * URL prefix: /api/AS/daily_approval
 *
 * [결재라인 등록] /line/read, /line/detail, /line/save, /line/delete
 * [결재라인 현황] /line/list, /line/list_detail
 * [결재 할 내역]  /pending/read, /pending/read1, /pending/changeState, /pending/approvalInfo
 * [결재 목록]     /history/read, /history/detail
 */
@Slf4j
@RestController
@Transactional   // 결재 상태변경·결재라인 저장이 여러 UPDATE/INSERT 로 나뉘어 있어, 중간 실패 시
                 // 앞 구문만 커밋되는 것을 막는다 (VehicleManageController 와 동일 방식)
@RequestMapping("/api/AS/daily_approval")
public class DailyApprovalController {

    @Autowired
    DailyApprovalService dailyApprovalService;

    @Autowired
    TenantUserService tenantUserService;

    // ════════════════════════════════════════════════════════
    //  결재라인 등록
    // ════════════════════════════════════════════════════════

    /**
     * 예외를 catch 해서 AjaxResult 로 돌려주는 구조라, 예외가 메서드 밖으로 나가지 않아
     * @Transactional 만으로는 롤백되지 않는다. 쓰기 작업의 catch 에서 명시적으로 표시한다.
     */
    private void markRollback() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // 트랜잭션 밖에서 호출된 경우 (테스트 등) — 무시
        }
    }

    /** 결재라인 목록 그리드 조회 */
    @GetMapping("/line/read")
    public AjaxResult lineRead(@RequestParam(required = false) String comcd,
                               Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

        String perid    = (String) userInfo.get("perid");
        String spjangcd = TenantContext.get();

        result.data    = dailyApprovalService.getApprovalLineList(perid, spjangcd, comcd);
        result.success = true;
        return result;
    }

    /** 로그인 사원의 사원코드/성명 (화면 상단 표시용) */
    @GetMapping("/line/myinfo")
    public AjaxResult lineMyInfo(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

        Map<String, Object> my = new HashMap<>();
        my.put("perid", dailyApprovalService.stripP((String) userInfo.get("perid")));
        my.put("pernm", userInfo.get("pernm"));

        result.data    = my;
        result.success = true;
        return result;
    }

    /** 결재라인 상세 (더블클릭) */
    @GetMapping("/line/detail")
    public AjaxResult lineDetail(@RequestParam String no,
                                 @RequestParam String papercd,
                                 Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

        String perid    = (String) userInfo.get("perid");
        String spjangcd = TenantContext.get();

        Map<String, Object> detail = dailyApprovalService.getApprovalLineDetail(no, papercd, perid, spjangcd);
        result.success = true;
        result.data    = detail;
        return result;
    }

    /** 결재라인 저장 */
    @PostMapping("/line/save")
    public AjaxResult lineSave(@RequestParam Map<String, String> params,
                               Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid    = (String) userInfo.get("perid");
            String spjangcd = TenantContext.get();
            String custcd   = dailyApprovalService.getCustcd(spjangcd);
            String indate   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            String papercd  = params.get("papercd");
            String gubun    = params.get("gubun");
            String seq      = params.get("seq");
            String kcperid  = params.get("kcperid");   // 결재자 사번 (화면에서 select value로 전달)
            String no       = params.get("no");

            if (no == null || no.isBlank()) {
                no = dailyApprovalService.getNextNo(spjangcd, perid, papercd);
            }

            dailyApprovalService.saveApprovalLine(spjangcd, custcd, perid, papercd, kcperid, gubun, seq, no, indate);
            result.success = true;
            result.message = "저장을 성공했습니다.";
        } catch (Exception e) {
            log.error("[결재라인 저장 실패]", e);
            markRollback();
            result.success = false;
            result.message = "저장 실패: " + e.getMessage();
        }
        return result;
    }

    /** 결재라인 삭제 */
    @PostMapping("/line/delete")
    public AjaxResult lineDelete(@RequestParam Map<String, String> params,
                                 Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid    = (String) userInfo.get("perid");
            String spjangcd = TenantContext.get();

            dailyApprovalService.deleteApprovalLine(
                perid,
                params.get("papercd"),
                params.get("no"),
                params.get("kcperid"),
                spjangcd
            );
            result.success = true;
            result.message = "삭제하였습니다.";
        } catch (Exception e) {
            log.error("[결재라인 삭제 실패]", e);
            markRollback();
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }

    // ════════════════════════════════════════════════════════
    //  결재라인 현황
    // ════════════════════════════════════════════════════════

    /** 결재라인 현황 — 사원별 문서 목록 (왼쪽 그리드) */
    @GetMapping("/line/list")
    public AjaxResult lineList(@RequestParam(required = false) String comcd) {
        AjaxResult result = new AjaxResult();
        String spjangcd = TenantContext.get();
        result.data    = dailyApprovalService.getApprovalLinePersonList(spjangcd, comcd);
        result.success = true;
        return result;
    }

    /** 결재라인 현황 — 사원별 결재자 상세 (오른쪽 그리드, 더블클릭) */
    @GetMapping("/line/list_detail")
    public AjaxResult lineListDetail(@RequestParam String perid,
                                     @RequestParam String comcd) {
        AjaxResult result = new AjaxResult();
        String spjangcd = TenantContext.get();
        result.data    = dailyApprovalService.getApprovalLineDetail2(spjangcd, perid, comcd);
        result.success = true;
        return result;
    }

    // ════════════════════════════════════════════════════════
    //  결재 할 내역
    // ════════════════════════════════════════════════════════

    /** 결재 할 내역 메인 그리드 */
    @GetMapping("/pending/read")
    public AjaxResult pendingRead(@RequestParam String startDate,
                                  @RequestParam String endDate,
                                  @RequestParam(required = false) String SearchPayment,
                                  @RequestParam(required = false) String searchText,
                                  Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid    = (String) userInfo.get("perid");
            String spjangcd = TenantContext.get();

            String sd = LocalDate.parse(startDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String ed = LocalDate.parse(endDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            result.data    = dailyApprovalService.getPendingApprovalList(spjangcd, perid, sd, ed, SearchPayment, searchText);
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    /** 결재 현황 카운트 (상단 요약 그리드) */
    @GetMapping("/pending/read1")
    public AjaxResult pendingRead1(@RequestParam String startDate,
                                   @RequestParam String endDate,
                                   Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid    = (String) userInfo.get("perid");
            String pernm    = (String) userInfo.getOrDefault("pernm", "");
            String spjangcd = TenantContext.get();

            String sd = LocalDate.parse(startDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String ed = LocalDate.parse(endDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            List<Map<String, Object>> paymentList = dailyApprovalService.getApprovalCount(spjangcd, perid, sd, ed);
            result.success = true;
            result.data    = Map.of("userName", pernm, "paymentList", paymentList);
        } catch (Exception e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    /** 결재 상태 변경 (승인/반려/보류/취소) */
    @PostMapping("/pending/changeState")
    public AjaxResult pendingChangeState(@RequestBody Map<String, Object> request,
                                         Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid    = (String) userInfo.get("perid");
            String spjangcd = TenantContext.get();
            String appnum   = (String) request.get("appnum");
            String action   = (String) request.get("action");
            String remark   = (String) request.getOrDefault("remark", "");

            boolean updated = dailyApprovalService.changeApprovalState(appnum, spjangcd, perid, action, remark);
            if (updated) {
                result.success = true;
                result.message = "결재가 처리되었습니다.";
            } else {
                result.success = false;
                result.message = "상태 변경 실패";
            }
        } catch (Exception e) {
            log.error("[결재 상태 변경 실패]", e);
            markRollback();
            result.success = false;
            result.message = "오류: " + e.getMessage();
        }
        return result;
    }

    /** 결재 버튼 활성화 정보 (canApprove / isApproved / canCancel) */
    @PostMapping("/pending/approvalInfo")
    public AjaxResult pendingApprovalInfo(@RequestBody Map<String, Object> request,
                                           Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid  = (String) userInfo.get("perid");
            String appnum = (String) request.get("appnum");

            result.data    = dailyApprovalService.getApprovalInfo(appnum, perid);
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    // ════════════════════════════════════════════════════════
    //  결재 목록 (내가 상신한 것)
    // ════════════════════════════════════════════════════════

    /** 결재 목록 메인 그리드 */
    @GetMapping("/history/read")
    public AjaxResult historyRead(@RequestParam String startDate,
                                  @RequestParam String endDate,
                                  @RequestParam(required = false) String SearchPayment,
                                  @RequestParam(required = false) String searchUserNm,
                                  Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid    = (String) userInfo.get("perid");
            String spjangcd = TenantContext.get();

            String sd = LocalDate.parse(startDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String ed = LocalDate.parse(endDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            result.data    = dailyApprovalService.getApprovalHistoryList(spjangcd, perid, sd, ed, SearchPayment, searchUserNm);
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    /** 결재 목록 상단 카운트 */
    @GetMapping("/history/read1")
    public AjaxResult historyRead1(@RequestParam String startDate,
                                   @RequestParam String endDate,
                                   Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo == null) { result.success = false; result.message = "사원 정보 없음"; return result; }

            String perid    = (String) userInfo.get("perid");
            String pernm    = (String) userInfo.getOrDefault("pernm", "");
            String spjangcd = TenantContext.get();

            String sd = LocalDate.parse(startDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String ed = LocalDate.parse(endDate).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            List<Map<String, Object>> paymentList = dailyApprovalService.getApprovalCount(spjangcd, perid, sd, ed);
            result.success = true;
            result.data    = Map.of("userName", pernm, "paymentList", paymentList);
        } catch (Exception e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    /** 결재 할 내역 더블클릭 — 업무일지 상세 조회 */
    @GetMapping("/pending/dailyDetail")
    public AjaxResult pendingDailyDetail(@RequestParam String appnum) {
        AjaxResult result = new AjaxResult();
        try {
            String spjangcd = TenantContext.get();
            Map<String, Object> data = dailyApprovalService.getDailyDetailByAppnum(spjangcd, appnum);
            if (data == null) {
                result.success = false;
                result.message = "업무일지 정보를 찾을 수 없습니다.";
                return result;
            }
            result.data    = data;
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    /** 결재 목록 행 클릭 — 결재라인 상세 (하단 그리드) */
    @GetMapping("/history/detail")
    public AjaxResult historyDetail(@RequestParam String appnum) {
        AjaxResult result = new AjaxResult();
        try {
            String spjangcd = TenantContext.get();
            result.data    = dailyApprovalService.getApprovalLineByAppnum(spjangcd, appnum);
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }
}
