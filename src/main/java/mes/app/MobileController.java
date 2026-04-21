package mes.app;

import lombok.Getter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

// 모바일 메뉴 컨트롤러
@Controller
@RequestMapping("/mobile")
public class MobileController {

    // ========== 근무관리 (attendance) ==========

    @GetMapping("/attendance/attendance_submit")
    public String attendanceSubmit(Model model) {
        model.addAttribute("currentPage", "attendance_submit");
        return "mobile/attendance/attendance_submit";
    }

    @GetMapping("/attendance/commute_current")
    public String commuteCurrent(Model model) {
        model.addAttribute("currentPage", "commute_current");
        return "mobile/attendance/commute_current";
    }

    @GetMapping("/attendance/attendance_current")
    public String attendanceCurrent(Model model) {
        model.addAttribute("currentPage", "attendance_current");
        return "mobile/attendance/attendance_current";
    }

    @GetMapping("/attendance/attendance_statistics")
    public String attendanceStatistics(Model model) {
        model.addAttribute("currentPage", "attendance_statistics");
        return "mobile/attendance/attendance_statistics";
    }

    @GetMapping("/attendance/attendance_modify")
    public String attendanceModify(Model model) {
        model.addAttribute("currentPage", "attendance_modify");
        return "mobile/attendance/attendance_modify";
    }

    // ========== 업무일지/보수관리 (request) ==========

    @GetMapping("/request/vehicle_manage")
    public String vehicleManage(Model model) {
        model.addAttribute("currentPage", "vehicle_manage");
        return "mobile/request/vehicle_manage";
    }

    @GetMapping("/request/daily_report")
    public String dailyReport(Model model) {
        model.addAttribute("currentPage", "daily_report");
        return "mobile/request/daily_report";
    }

    @GetMapping("/request/maintenance_comp")
    public String maintenanceComp(Model model) {
        model.addAttribute("currentPage", "maintenance_comp");
        return "mobile/request/maintenance_comp";
    }

    @GetMapping("/request/request_repair")
    public String requestRepair(Model model) {
        model.addAttribute("currentPage", "request_repair");
        return "mobile/request/request_repair";
    }

    @GetMapping("/request/maintenance_repair")
    public String maintenanceRepair(Model model) {
        model.addAttribute("currentPage", "maintenance_repair");
        return "mobile/request/maintenance_repair";
    }

    // ========== 계정관리 ==========

    @GetMapping("/user_info")
    public String userInfo(Model model) {
        model.addAttribute("currentPage", "user_info");
        return "mobile/user_info";
    }

    @Getter
    public class TempDto {
        private String username;
        private String email;
        private String tel;
        private String address;
        private String nickname;
        private Integer age;

        public TempDto(String username, String email, String phone, String adr, String nick, Integer age) {
            this.username = username;
            this.email = email;
            this.tel = phone;
            this.address = adr;
            this.nickname = nick;
            this.age = age;
        }
    }
}
