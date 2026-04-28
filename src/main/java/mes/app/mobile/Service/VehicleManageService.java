package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VehicleManageService {

    @Autowired
    SqlRunner sqlRunner;

    @Value("${ncp.ocr.invoke-url}")
    private String ocrInvokeUrl;

    @Value("${ncp.ocr.secret-key}")
    private String ocrSecretKey;

    // 사용자 정보 조회
    public Map<String, Object> getUserInfo(String username) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);

        String sql = """
                SELECT TOP 1
                          a.username,
                          a.first_name,
                          p.id,
                          an.restnum,
                          t.sttime,
                          e.carcd,
                          e.carnum,
                          e.gubun AS fuelcd,
                          e.samt
                      FROM auth_user a
                      LEFT JOIN tb_pb209 an ON an.perid = a.personid
                      LEFT JOIN person p ON p.id = a.personid
                      LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                      LEFT JOIN TB_E047 e ON e.perid = a.username
                      WHERE a.username = :username
                      ORDER BY an.todate DESC
        		""";

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    // 현장 목록 조회 (TB_E601)
    public List<Map<String, Object>> getSiteList(String spjangcd, String keyword) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm, address
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND actnm LIKE :keyword";
            dicParam.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY actnm";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    /**
     * 유류 단가 정보 조회 (TB_E037_1)
     * fuelcd 선택 시 해당 유류의 uamt(단가), kmliter(연비), unit(단위) 반환
     */
    public Map<String, Object> getFuelInfo(String spjangcd, String fuelcd) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("fuelcd", fuelcd);

        String sql = """
                SELECT TOP 1
                    fuelcd,
                    fuelnm,
                    uamt,
                    kmliter,
                    unit
                FROM TB_E037_1
                WHERE spjangcd = :spjangcd
                  AND fuelcd   = :fuelcd
                  AND useyn    = '1'
                """;

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    /**
     * 차량 목록 조회 (TB_E047)
     * 전체 차량 조회, 차량번호(carnum) 키워드 검색 지원
     */
    public List<Map<String, Object>> getVehicleList(String keyword) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                SELECT carcd, carnum, gubun AS fuelcd, samt
                FROM TB_E047
                WHERE 1=1
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND carnum LIKE :keyword";
            dicParam.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY carnum";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    // =====================================================
    // OCR: 계기판 사진 → km 숫자 추출
    // =====================================================

    /**
     * MultipartFile(계기판 사진)을 받아 NCP CLOVA OCR로 km 수치를 추출
     */
    public Map<String, Object> extractKmFromImage(MultipartFile imageFile) {
        Map<String, Object> result = new HashMap<>();
        File tempFile = null;
        try {
            // 1. MultipartFile → 임시 파일로 저장
            String ext = getExtension(imageFile.getOriginalFilename());
            tempFile = File.createTempFile("ocr_", "." + ext);
            imageFile.transferTo(tempFile);

            // 2. NCP CLOVA OCR API 호출 (factcheck 프로젝트 방식 그대로)
            StringBuffer response = callClovaOcr(tempFile, ext);

            // 3. 응답 JSON에서 텍스트 전체 추출
            JSONObject json = new JSONObject(response.toString());
            JSONArray images = json.getJSONArray("images");
            StringBuilder allText = new StringBuilder();
            for (int i = 0; i < images.length(); i++) {
                JSONObject imgObj = images.getJSONObject(i);
                if (imgObj.has("fields")) {
                    JSONArray fields = imgObj.getJSONArray("fields");
                    for (int j = 0; j < fields.length(); j++) {
                        allText.append(fields.getJSONObject(j).getString("inferText")).append(" ");
                    }
                }
            }

            // 4. km 숫자 파싱 (계기판: 4~6자리 숫자 중 가장 큰 값)
            Long km = parseKmFromText(allText.toString());

            result.put("success", true);
            result.put("km", km);
            result.put("rawText", allText.toString().trim());

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "OCR 처리 오류: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
        return result;
    }

    /**
     * NCP CLOVA OCR API 호출 (factcheck 프로젝트의 NaverClovaORCAPI 방식)
     */
    private StringBuffer callClovaOcr(File file, String ext) throws Exception {
        URL url = new URL(ocrInvokeUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setUseCaches(false);
        con.setDoInput(true);
        con.setDoOutput(true);
        con.setReadTimeout(30000);
        con.setRequestMethod("POST");

        String boundary = "----" + UUID.randomUUID().toString().replaceAll("-", "");
        con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        con.setRequestProperty("X-OCR-SECRET", ocrSecretKey);
        con.setRequestProperty("Accept-Charset", "UTF-8");

        // OCR 요청 JSON 구성
        JSONObject json = new JSONObject();
        json.put("version", "V2");
        json.put("requestId", UUID.randomUUID().toString());
        json.put("timestamp", System.currentTimeMillis());
        json.put("enableTableDetection", false);

        JSONObject image = new JSONObject();
        image.put("format", ext.toLowerCase());
        image.put("name", "dashboard");

        JSONArray images = new JSONArray();
        images.put(image);
        json.put("images", images);

        con.connect();
        DataOutputStream wr = new DataOutputStream(con.getOutputStream());
        writeMultiPart(wr, json.toString(), file, boundary);
        wr.close();

        int responseCode = con.getResponseCode();
        BufferedReader br;
        if (responseCode == 200) {
            br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
        } else {
            br = new BufferedReader(new InputStreamReader(con.getErrorStream(), StandardCharsets.UTF_8));
        }

        StringBuffer response = new StringBuffer();
        String inputLine;
        while ((inputLine = br.readLine()) != null) {
            response.append(inputLine);
        }
        br.close();
        return response;
    }

    /**
     * multipart/form-data 전송
     */
    private void writeMultiPart(OutputStream out, String jsonMessage, File file, String boundary) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition:form-data; name=\"message\"\r\n\r\n");
        sb.append(jsonMessage);
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.flush();

        if (file != null && file.isFile()) {
            out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
            StringBuilder fileString = new StringBuilder();
            fileString.append("Content-Disposition:form-data; name=\"file\"; filename=");
            fileString.append("\"").append(file.getName()).append("\"\r\n");
            fileString.append("Content-Type: application/octet-stream\r\n\r\n");
            out.write(fileString.toString().getBytes("UTF-8"));
            out.flush();

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                out.write("\r\n".getBytes());
            }
            out.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        }
        out.flush();
    }

    /**
     * OCR 텍스트에서 계기판 km 수치 추출 (4~6자리 숫자 중 최댓값)
     */
    private Long parseKmFromText(String text) {
        String cleaned = text.replaceAll("[^0-9\\s]", " ");
        Pattern pattern = Pattern.compile("\\b(\\d{4,6})\\b");
        Matcher matcher = pattern.matcher(cleaned);
        Long best = null;
        while (matcher.find()) {
            long val = Long.parseLong(matcher.group(1));
            if (val >= 1000 && val <= 999999) {
                if (best == null || val > best) {
                    best = val;
                }
            }
        }
        return best;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
