package fun.fengwk.kkstudio.platform.storage;

import static org.mockito.Mockito.mock;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignedUrl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用预签名服务：记录每次请求（key、contentType、校验和、有效期），并返回不含真实签名的固定响应。
 *
 * @author fengwk
 */
public class RecordingS3PresignService extends S3PresignService {

  public RecordingS3PresignService() {
    // 两个预签名方法都由本假件覆盖；超类只要求有效的部署连接、签名器与设置快照引用。
    super(
        new S3StorageProperties(),
        mock(S3Presigner.class),
        new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT));
  }

  private final List<PresignRecord> records = new ArrayList<>();
  private RuntimeException nextCreateOnlyFailure;

  public synchronized void clear() {
    records.clear();
    nextCreateOnlyFailure = null;
  }

  public synchronized List<PresignRecord> records() {
    return List.copyOf(records);
  }

  /** 让下一次 checksummedCreateOnly 预签名抛出给定异常，用于模拟签票失败。 */
  public synchronized void failNextCreateOnly(RuntimeException failure) {
    this.nextCreateOnlyFailure = failure;
  }

  @Override
  public synchronized S3PresignedUrl presignChecksummedCreateOnlyUpload(
      String key, String contentType, String checksumSha256Base64, Long expiresInSeconds) {
    if (nextCreateOnlyFailure != null) {
      RuntimeException failure = nextCreateOnlyFailure;
      nextCreateOnlyFailure = null;
      throw failure;
    }
    records.add(
        new PresignRecord(
            "checksummedCreateOnly", key, contentType, checksumSha256Base64, expiresInSeconds));
    return response("PUT", key, headers(contentType, "*", checksumSha256Base64));
  }

  @Override
  public synchronized S3PresignedUrl presignDownload(String key, Long expiresInSeconds) {
    records.add(new PresignRecord("download", key, null, null, expiresInSeconds));
    return response("GET", key, Map.of());
  }

  private static Map<String, String> headers(
      String contentType, String ifNoneMatch, String checksumSha256Base64) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (contentType != null) {
      headers.put("content-type", contentType);
    }
    if (ifNoneMatch != null) {
      headers.put("if-none-match", ifNoneMatch);
    }
    if (checksumSha256Base64 != null) {
      headers.put("x-amz-checksum-sha256", checksumSha256Base64);
    }
    return headers;
  }

  private static S3PresignedUrl response(String method, String key, Map<String, String> headers) {
    return S3PresignedUrl.builder()
        .method(method)
        .url("https://cdn.example.com/test-bucket/" + key + "?X-Amz-Signature=fake")
        .headers(headers)
        .expiresAt(Instant.now().plusSeconds(600L))
        .build();
  }

  /** 一次预签名调用记录（kind 取值 checksummedCreateOnly/download）。 */
  public record PresignRecord(
      String kind,
      String key,
      String contentType,
      String checksumSha256Base64,
      Long expiresInSeconds) {}
}
