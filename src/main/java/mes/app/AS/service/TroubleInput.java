package mes.app.AS.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 갇힘사고 입력값(갇힘일자 · 시간 · 갇힌 인원) 정규화.
 *
 * 고장접수(TB_E401)와 고장처리(TB_E411) 가 같은 세 컬럼을 갖고 있고,
 * 웹·모바일 네 화면이 모두 같은 규칙으로 넣어야 해서 한곳에 모았다.
 *   troubledate varchar(8)  yyyyMMdd
 *   troubletime char(4)     HHmm
 *   troublesu   float       갇힌 인원
 *
 * 고장통계 종합현황이 이 값으로 '사람갇힘' 을 집계한다.
 */
public final class TroubleInput {

    private TroubleInput() { }

    /** 세 값을 :troubledate / :troubletime / :troublesu 로 넣는다 */
    public static void bind(MapSqlParameterSource param,
                            String troubledate, String troubletime, String troublesu) {
        param.addValue("troubledate", date(troubledate));
        param.addValue("troubletime", time(troubletime));
        param.addValue("troublesu",   count(troublesu));
    }

    /** '2026-08-10' / '20260810' → '20260810'. 형식이 아니면 빈 값 */
    public static String date(String v) {
        String s = clean(v);
        return s.length() == 8 ? s : "";
    }

    /** '10:00' / '1000' → '1000'. '9:5' 같은 값도 '0905' 로 맞춘다 */
    public static String time(String v) {
        String s = clean(v);
        if (s.isEmpty()) return "";
        if (s.length() == 4) return s;
        if (s.length() == 3) return "0" + s;                      // 930 → 0930
        if (s.length() <= 2) return pad(s) + "00";                // 9 → 0900
        return s.substring(0, 4);
    }

    /**
     * 갇힌 인원. 숫자가 아니거나 음수면 0.
     * 0 이면 '갇힘 없음' 이라 종합현황 집계에서 빠진다.
     */
    public static Double count(String v) {
        String s = clean(v);
        if (s.isEmpty()) return 0d;
        try {
            double d = Double.parseDouble(s);
            return d < 0 ? 0d : d;
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** 숫자만 남긴다 (하이픈 · 콜론 · 공백 제거) */
    private static String clean(String v) {
        if (v == null) return "";
        return v.replaceAll("[^0-9.]", "").trim();
    }

    private static String pad(String s) {
        return s.length() == 1 ? "0" + s : s;
    }
}
