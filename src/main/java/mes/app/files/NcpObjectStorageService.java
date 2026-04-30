package mes.app.files;

import lombok.extern.slf4j.Slf4j;
import mes.config.Settings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class NcpObjectStorageService {

    @Autowired
    private Settings settings;

    private S3Client s3Client;
    private String bucketName;

    @Value("${mes.project-name}")
    private String projectName;

    @PostConstruct
    public void init() {
        String endpoint  = settings.getProperty("ncp.storage.endpoint");
        String region    = settings.getProperty("ncp.storage.region");
        String accessKey = settings.getProperty("ncp_api_accessKey");
        String secretKey = settings.getProperty("ncp_api_secretKey");
        this.bucketName  = settings.getProperty("ncp.storage.bucket");

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    /**
     * NCP 오브젝트 스토리지에 파일 업로드
     */
    public void upload(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));
        log.info("[NcpStorage] 업로드 완료: {}/{}", bucketName, objectKey);
    }

    /**
     * NCP 오브젝트 스토리지에서 파일 다운로드
     */
    public ResponseInputStream<GetObjectResponse> download(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();
        return s3Client.getObject(request);
    }

    /**
     * NCP 오브젝트 스토리지에서 파일 삭제
     */
    public void delete(String objectKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();
        s3Client.deleteObject(request);
        log.info("[NcpStorage] 삭제 완료: {}/{}", bucketName, objectKey);
    }

    // TB_FILEINFO.CHECKSEQ: varchar(2) — 기능명 → 코드값 매핑
    private static final Map<String, String> CHECKSEQ_MAP;
    static {
        CHECKSEQ_MAP = new HashMap<>();
        CHECKSEQ_MAP.put("NOTICE",       "01");
        CHECKSEQ_MAP.put("QNA",          "02");
        CHECKSEQ_MAP.put("MARKETING",    "03");
        CHECKSEQ_MAP.put("DAILY_REPORT", "04");  // 업무일지 파일
    }

    public static String toCheckseq(String tableName) {
        return CHECKSEQ_MAP.getOrDefault(tableName != null ? tableName.toUpperCase() : "", "99");
    }

    /**
     * 오브젝트 키 생성: {projectName}/{dbKey}/{featureCode}/{uuid}.{ext}
     */
    public String buildObjectKey(String dbKey, String featureCode, String uuidFileName) {
        return this.projectName + "/" + dbKey + "/" + featureCode + "/" + uuidFileName;
    }

    public String getFilePrefix(String dbKey, String featureCode) {
        return this.projectName + "/" + dbKey + "/" + featureCode;
    }
}
