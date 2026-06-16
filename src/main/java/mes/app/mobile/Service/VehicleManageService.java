package mes.app.mobile.Service;

import mes.domain.model.AjaxResult;
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

    // 사용자 정보 조회 (personid 기준 - TB_PB209 JOIN 제거)
    public Map<String, Object> getUserInfo(int personId) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personId", personId);

        String sql = """
                SELECT TOP 1
                          p.id,
                          p.name      AS first_name,
                          t.sttime,
                          e.carcd,
                          e.carnum,
                          e.gubun     AS fuelcd,
                          e.samt
                      FROM person p
                      LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                      LEFT JOIN TB_E047 e ON e.perid = CAST(p.id AS VARCHAR)
                      WHERE p.id = :personId
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

    // 유류 단가 정보 조회 (TB_E037_1) - gareacd 무관, 단가 최고값 1건
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
                ORDER BY uamt DESC
                """;

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    // 차량 목록 조회 (TB_E047) - gareacd 제거, 단가 최고값 서브쿼리로 fuelnm 매핑
    public List<Map<String, Object>> getVehicleList(String spjangcd, String keyword) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT e.carcd, e.carnum, e.gubun AS fuelcd, e.samt, f.fuelnm
                FROM TB_E047 e
                LEFT JOIN (
                    SELECT f1.fuelcd, f1.fuelnm, f1.uamt
                    FROM TB_E037_1 f1
                    WHERE f1.spjangcd = :spjangcd
                      AND f1.useyn    = '1'
                      AND f1.uamt = (
                          SELECT MAX(f2.uamt)
                          FROM TB_E037_1 f2
                          WHERE f2.fuelcd   = f1.fuelcd
                            AND f2.spjangcd = f1.spjangcd
                            AND f2.useyn    = '1'
                      )
                ) f ON f.fuelcd = e.gubun
                WHERE 1=1
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND e.carnum LIKE :keyword";
            dicParam.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY e.carnum";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    // =====================================================
    // OCR: 계기판 사진 → km 숫자 추출
    // =====================================================
    public Map<String, Object> extractKmFromImage(MultipartFile imageFile) {
        Map<String, Object> result = new HashMap<>();
        File tempFile = null;
        try {
            String ext = getExtension(imageFile.getOriginalFilename());
            tempFile = File.createTempFile("ocr_", "." + ext);
            imageFile.transferTo(tempFile);

            StringBuffer response = callClovaOcr(tempFile, ext);

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

    // =====================================================
    // 차량 운행 등록 (TB_E037_CONF INSERT)
    // =====================================================
    public AjaxResult submitAttendance(Map<String, Object> param, String spjangcd) {

        AjaxResult result = new AjaxResult();

        try {
            String custcd = getCustcdBySpjangcd(spjangcd);
            if (custcd == null || custcd.isBlank()) {
                result.success = false;
                result.message = "거래처코드(custcd)를 찾을 수 없습니다.";
                return result;
            }

            String userId    = (String) param.get("userId");
            String startDate = ((String) param.get("startDate")).replace("-", "");
            String confmon   = startDate.substring(0, 6);
            String vehicleCd = (String) param.get("vehicleCd");
            String fuelKind  = (String) param.get("fuelKind");
            String actcd     = (String) param.get("siteCd");
            String unitAmt   = (String) param.get("unitAmt");

            double totalKM   = parseDouble(param.get("totalKM"));
            double liter     = parseDouble(param.get("liter"));
            double total     = parseDouble(param.get("total"));
            double uamt      = parseDouble(unitAmt);
            double usedLiter = (liter > 0) ? (totalKM / liter) : 0;

            String divicd = getDivicd(userId);
            String kcnum  = getNextKcnum(custcd, spjangcd, startDate);
            String kcseq  = "001";

            MapSqlParameterSource dicParam = new MapSqlParameterSource();
            dicParam.addValue("custcd",   custcd);
            dicParam.addValue("spjangcd", spjangcd);
            dicParam.addValue("kcdate",   startDate);
            dicParam.addValue("kcnum",    kcnum);
            dicParam.addValue("confmon",  confmon);
            dicParam.addValue("perid",    userId);
            dicParam.addValue("kcseq",    kcseq);
            dicParam.addValue("carcd",    vehicleCd);
            dicParam.addValue("gubun",    fuelKind);
            dicParam.addValue("km",       totalKM);
            dicParam.addValue("liter",    usedLiter);
            dicParam.addValue("uamt",     uamt);
            dicParam.addValue("samt",     total);
            dicParam.addValue("actcd",    actcd);
            dicParam.addValue("divicd",   divicd);
            dicParam.addValue("unit",     "KM");
            dicParam.addValue("confyn",   "0");
            dicParam.addValue("indate",   startDate);

            String sql = """
                    INSERT INTO TB_E037_CONF (
                        custcd, spjangcd, kcdate, kcnum, confmon, perid, kcseq,
                        carcd, gubun,
                        km, liter, uamt, samt,
                        actcd, divicd, unit, confyn, indate
                    ) VALUES (
                        :custcd, :spjangcd, :kcdate, :kcnum, :confmon, :perid, :kcseq,
                        :carcd, :gubun,
                        :km, :liter, :uamt, :samt,
                        :actcd, :divicd, :unit, :confyn, :indate
                    )
                    """;

            this.sqlRunner.execute(sql, dicParam);

            result.success = true;
            result.message = "운행기록이 등록되었습니다.";

        } catch (Exception e) {
            result.success = false;
            result.message = "등록 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    private String getNextKcnum(String custcd, String spjangcd, String kcdate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("kcdate",   kcdate);

        String sql = """
                SELECT ISNULL(MAX(CAST(kcnum AS INT)), 0) + 1 AS nextnum
                FROM TB_E037_CONF
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND kcdate   = :kcdate
                """;

        Map<String, Object> row = this.sqlRunner.getRow(sql, param);
        int next = (row != null && row.get("nextnum") != null)
                ? ((Number) row.get("nextnum")).intValue() : 1;
        return String.format("%04d", next);
    }

    private String getDivicd(String userId) {
        MapSqlParameterSource sqlParam = new MapSqlParameterSource();
        sqlParam.addValue("perid", "p" + userId);

        String sql = """
                SELECT divicd
                FROM TB_JA001
                WHERE perid = :perid
                """;

        Map<String, Object> row = this.sqlRunner.getRow(sql, sqlParam);
        if (row == null || row.isEmpty()) return null;
        Object divicd = row.get("divicd");
        return divicd == null ? null : String.valueOf(divicd).trim();
    }

    private String getCustcdBySpjangcd(String spjangcd) {
        MapSqlParameterSource sqlParam = new MapSqlParameterSource();
        sqlParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT custcd
                FROM tb_xa012
                WHERE spjangcd = :spjangcd
                """;

        Map<String, Object> row = this.sqlRunner.getRow(sql, sqlParam);
        if (row == null || row.isEmpty()) return null;
        Object custcd = row.get("custcd");
        return custcd == null ? null : String.valueOf(custcd).trim();
    }

    private double parseDouble(Object val) {
        if (val == null) return 0.0;
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    // ── 차량운행 현황 조회 (TB_E037_CONF) ────────────────────
    public List<Map<String, Object>> getStatusList(String spjangcd, String fromDate, String toDate, String carnum, String perid) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);
        param.addValue("perid",    perid);

        String sql = """
                SELECT
                    c.kcdate,
                    c.kcnum,
                    c.spjangcd,
                    c.perid,
                    j.pernm,
                    jc.divinm,
                    e.carnum,
                    f.fuelnm,
                    c.km,
                    c.liter,
                    c.uamt,
                    c.samt,
                    s.actnm
                FROM TB_E037_CONF c
                LEFT JOIN TB_JA001 j   ON j.perid    = c.perid
                                      AND j.spjangcd  = c.spjangcd
                LEFT JOIN TB_JC002 jc  ON jc.divicd  = j.divicd
                                      AND jc.spjangcd = j.spjangcd
                LEFT JOIN TB_E047 e    ON e.carcd     = c.carcd
                LEFT JOIN (
                    SELECT f1.fuelcd, f1.fuelnm
                    FROM TB_E037_1 f1
                    WHERE f1.spjangcd = :spjangcd
                      AND f1.useyn    = '1'
                      AND f1.uamt = (
                          SELECT MAX(f2.uamt)
                          FROM TB_E037_1 f2
                          WHERE f2.fuelcd   = f1.fuelcd
                            AND f2.spjangcd = f1.spjangcd
                            AND f2.useyn    = '1'
                      )
                ) f ON f.fuelcd = c.gubun
                LEFT JOIN TB_E601 s    ON s.actcd     = c.actcd
                                      AND s.spjangcd  = c.spjangcd
                WHERE c.spjangcd = :spjangcd
                  AND c.kcdate  BETWEEN :fromDate AND :toDate
                  AND c.perid   = :perid
                """;

        if (carnum != null && !carnum.trim().isEmpty()) {
            sql += " AND e.carnum LIKE :carnum";
            param.addValue("carnum", "%" + carnum.trim() + "%");
        }

        sql += " ORDER BY c.kcdate DESC, c.kcnum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 차량운행 수정 (TB_E037_CONF UPDATE) ──────────────────
    public void updateStatus(String spjangcd, String kcdate, String kcnum,
                             String newKcdate, String actcd, String actnm,
                             String gubun, String km, String liter,
                             String uamt, String samt) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("kcdate",   kcdate);
        param.addValue("kcnum",    kcnum);
        param.addValue("newKcdate", newKcdate != null && !newKcdate.isBlank() ? newKcdate : kcdate);
        param.addValue("actcd",    actcd);
        param.addValue("actnm",    actnm);
        param.addValue("gubun",    gubun);
        param.addValue("km",       km != null && !km.isBlank() ? Double.parseDouble(km) : null);
        param.addValue("liter",    liter != null && !liter.isBlank() ? Double.parseDouble(liter) : null);
        param.addValue("uamt",     uamt != null && !uamt.isBlank() ? Double.parseDouble(uamt) : null);
        param.addValue("samt",     samt != null && !samt.isBlank() ? Double.parseDouble(samt) : null);

        this.sqlRunner.execute("""
                UPDATE TB_E037_CONF SET
                    kcdate = :newKcdate,
                    actcd  = :actcd,
                    gubun  = :gubun,
                    km     = :km,
                    liter  = :liter,
                    uamt   = :uamt,
                    samt   = :samt
                WHERE spjangcd = :spjangcd
                  AND kcdate   = :kcdate
                  AND kcnum    = :kcnum
                """, param);
    }

    // ── 차량운행 삭제 (TB_E037_CONF DELETE) ──────────────────
    public void deleteStatus(String spjangcd, String kcdate, String kcnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("kcdate",   kcdate);
        param.addValue("kcnum",    kcnum);

        String sql = """
                DELETE FROM TB_E037_CONF
                WHERE spjangcd = :spjangcd
                  AND kcdate   = :kcdate
                  AND kcnum    = :kcnum
                """;

        this.sqlRunner.execute(sql, param);
    }
}
