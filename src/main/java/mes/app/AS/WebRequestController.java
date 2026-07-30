package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.WebRequestService;
import mes.app.common.TenantUserService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/AS/web_request")
public class WebRequestController {

    @Autowired
    WebRequestService webRequestService;

    @Autowired
    TenantUserService tenantUserService;

    // ── 사용자 정보 조회 (custcd 포함) ───────────────────────
    @GetMapping("/user_info")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        result.data = userInfo;
        return result;
    }

    // ── 카운트 (금일수신/고장접수/콜백예약/당일처리) ──────────
    @GetMapping("/count")
    public AjaxResult getCount(
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.getCount(spjangcd);
        return result;
    }

    // ── 고장접수현황 카드 리스트 ──────────────────────────────
    @GetMapping("/list")
    public AjaxResult getList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.getList(spjangcd, fromDate, toDate, actnm);
        return result;
    }

    // ── 고장접수 저장 ─────────────────────────────────────────
    @PostMapping("/save")
    public AjaxResult save(
            @RequestParam(value = "spjangcd")              String spjangcd,
            @RequestParam(value = "custcd",   required = false) String custcd,
            @RequestParam(value = "recedate", required = false) String recedate,
            @RequestParam(value = "recenum",  required = false) String recenum,
            @RequestParam(value = "recetime", required = false) String recetime,
            @RequestParam(value = "hitchdate",required = false) String hitchdate,
            @RequestParam(value = "hitchhour",required = false) String hitchhour,
            @RequestParam(value = "actcd",    required = false) String actcd,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "equpcd",   required = false) String equpcd,
            @RequestParam(value = "equpnm",   required = false) String equpnm,
            @RequestParam(value = "reperid",  required = false) String reperid,
            @RequestParam(value = "perid",    required = false) String perid,
            @RequestParam(value = "contcd",   required = false) String contcd,
            @RequestParam(value = "contents", required = false) String contents,
            @RequestParam(value = "remark",   required = false) String remark,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            // ★ custcd 보정: 프론트가 user_info 비동기 조회 전에 저장하면 빈 값으로 전송됨.
            //   빈 값 그대로 INSERT 되면 PB 조회(a.custcd = b.custcd)에서 처리내역이 안 붙음.
            if (custcd == null || custcd.isBlank()) {
                User user = (User) auth.getPrincipal();
                Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
                if (userInfo != null && userInfo.get("custcd") != null) {
                    custcd = userInfo.get("custcd").toString();
                }
                log.warn("[고장접수] custcd 미전달 → 서버 보정: {}", custcd);
            }

            webRequestService.save(spjangcd, custcd,
                    recedate, recenum, recetime,
                    hitchdate, hitchhour,
                    actcd, actnm, equpcd, equpnm,
                    reperid, perid,
                    contcd, contents, remark);
            result.success = true;
            result.message = (recenum == null || recenum.isBlank())
                    ? "고장접수가 등록되었습니다." : "고장접수가 수정되었습니다.";
        } catch (Exception e) {
            log.error("고장접수 저장 오류", e);
            result.success = false;
            result.message = "저장 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 고장접수 삭제 ─────────────────────────────────────────
    @PostMapping("/delete")
    public AjaxResult delete(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "recedate") String recedate,
            @RequestParam(value = "recenum")  String recenum,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        try {
            webRequestService.delete(spjangcd, recedate, recenum);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("고장접수 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 문자전송내역 조회 ─────────────────────────────────────
    @GetMapping("/sms_history")
    public AjaxResult getSmsHistory(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "recedate", required = false) String recedate,
            @RequestParam(value = "recenum",  required = false) String recenum,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.getSmsHistory(spjangcd, recedate, recenum);
        return result;
    }

    // ── 통화메모 목록 조회 ────────────────────────────────────
    @GetMapping("/memo_list")
    public AjaxResult getMemoList(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "srchDate", required = false) String srchDate,
            @RequestParam(value = "callnm",   required = false) String callnm,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.getMemoList(spjangcd, srchDate, callnm);
        return result;
    }

    // ── 통화메모 저장 ─────────────────────────────────────────
    @PostMapping("/save_memo")
    public AjaxResult saveMemo(
            @RequestParam(value = "spjangcd")                      String spjangcd,
            @RequestParam(value = "seq",          required = false) String seq,
            @RequestParam(value = "calldate",     required = false) String calldate,
            @RequestParam(value = "calltime",     required = false) String calltime,
            @RequestParam(value = "callnm",       required = false) String callnm,
            @RequestParam(value = "callnum",      required = false) String callnum,
            @RequestParam(value = "callbackflag", required = false) String callbackflag,
            @RequestParam(value = "callbacktime", required = false) String callbacktime,
            @RequestParam(value = "callbackmemo", required = false) String callbackmemo,
            @RequestParam(value = "callmemo",     required = false) String callmemo,
            @RequestParam(value = "callendmemo",  required = false) String callendmemo,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            User user = (User) auth.getPrincipal();
            String pernm = user.getUsername();

            webRequestService.saveMemo(spjangcd, seq,
                    calldate, calltime, callnm, callnum,
                    callbackflag, callbacktime, callbackmemo,
                    callmemo, callendmemo, pernm);
            result.success = true;
            result.message = "저장되었습니다.";
        } catch (Exception e) {
            log.error("통화메모 저장 오류", e);
            result.success = false;
            result.message = "저장 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 통화메모 삭제 ─────────────────────────────────────────
    @PostMapping("/delete_memo")
    public AjaxResult deleteMemo(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "seq")      String seq,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        try {
            webRequestService.deleteMemo(spjangcd, seq);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("통화메모 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 팝업: 현장 검색 ───────────────────────────────────────
    @GetMapping("/popup/actnm")
    public AjaxResult popupActnm(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "actnm",    required = false) String actnm,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.popupActnm(spjangcd, actnm);
        return result;
    }

    // ── 팝업: 호기 검색 ───────────────────────────────────────
    @GetMapping("/popup/equpnm")
    public AjaxResult popupEqupnm(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "actcd",    required = false) String actcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.popupEqupnm(spjangcd, actcd);
        return result;
    }

    // ── 팝업: 사원 검색 (접수자/통보자 공통) ─────────────────
    @GetMapping("/popup/pernm")
    public AjaxResult popupPernm(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "pernm",    required = false) String pernm,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.popupPernm(spjangcd, pernm);
        return result;
    }

    // ── 팝업: 고장내용 검색 (TB_E010) ────────────────────────
    @GetMapping("/popup/contnm")
    public AjaxResult popupContnm(
            @RequestParam(value = "contnm", required = false) String contnm,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.popupContnm(contnm);
        return result;
    }

    // ── 승강기번호 조회 ───────────────────────────────────────
    @GetMapping("/elvinfo")
    public AjaxResult getElvInfo(
            @RequestParam(value = "elvnum") String elvnum,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        try {
            result.data    = webRequestService.getElvInfo(elvnum);
            result.success = true;
        } catch (Exception e) {
            log.error("승강기번호 조회 오류", e);
            result.success = false;
            result.message = "승강기 정보 조회 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── PushID 조회 ───────────────────────────────────────────
    @PostMapping("/pushid")
    public AjaxResult getPushId(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "pernm",    required = false) String pernm,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webRequestService.getPushId(spjangcd, pernm);
        return result;
    }
}
