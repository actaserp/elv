package mes.app.ai.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 고장·자료 문장을 검색용 토큰으로 자른다. 외부 형태소 분석기 없이 동작한다.
 *
 * 고장 문장은 "7층 버튼 안됨", "20층 오픈정지", "E/S #31 정지" 처럼 짧고 띄어쓰기가 제각각이라
 * 단어 전체 + 한글 두 글자 조각(bigram)을 함께 쓴다. "오픈정지" 와 "정지" 가 "정지" 조각으로 이어진다.
 */
public final class FaultText {

    private FaultText() {
    }

    /**
     * @param dropDigits true 면 숫자를 버린다 (고장 분류: "7층", "#31" 같은 위치 숫자는 잡음)
     */
    public static List<String> tokens(String text, boolean dropDigits) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        String s = text.toLowerCase(Locale.ROOT);
        StringBuilder norm = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep = isHangul(c) || (c >= 'a' && c <= 'z') || (!dropDigits && c >= '0' && c <= '9');
            norm.append(keep ? c : ' ');
        }

        for (String w : norm.toString().split("\\s+")) {
            if (w.isEmpty()) continue;
            // 한글·영문이 붙어 있으면 나눠서 본다 ("door열림" → "door", "열림")
            for (String part : splitScript(w)) {
                if (part.isEmpty()) continue;
                char first = part.charAt(0);
                if (isHangul(first)) {
                    if (part.length() == 1) {
                        out.add("u:" + part);
                    } else {
                        out.add("w:" + part);
                        for (int i = 0; i + 2 <= part.length(); i++) {
                            out.add("b:" + part.substring(i, i + 2));
                        }
                    }
                } else if (part.length() >= 2) {
                    out.add("w:" + part);
                }
            }
        }
        return out;
    }

    /** 사람에게 보여줄 비교용 정규화 (공백·기호 제거, 소문자) — 키워드 포함 여부 판단에 쓴다 */
    public static String compact(String text) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isHangul(c) || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }

    private static List<String> splitScript(String w) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= w.length(); i++) {
            if (i == w.length() || script(w.charAt(i)) != script(w.charAt(i - 1))) {
                parts.add(w.substring(start, i));
                start = i;
            }
        }
        return parts;
    }

    private static int script(char c) {
        if (isHangul(c)) return 0;
        if (c >= '0' && c <= '9') return 2;
        return 1;
    }
}
