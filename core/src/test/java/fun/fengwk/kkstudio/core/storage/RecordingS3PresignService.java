package fun.fengwk.kkstudio.core.storage;

import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;

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
public class RecordingS3PresignService implements S3PresignService {

  private final List<PresignRecord> records = new ArrayList<>();

  public synchronized void clear() {
    records.clear();
  }

  public synchronized List<PresignRecord> records() {
    return List.copyOf(records);
  }

  @Override
  public synchronized S3PresignedResponseDTO presignChecksummedCreateOnlyUpload(
      String key, String contentType, String checksumSha256Base64, Long expiresInSeconds) {
    records.add(
        new PresignRecord(
            "checksummedCreateOnly", key, contentType, checksumSha256Base64, expiresInSeconds));
    return response("PUT", key, headers(contentType, "*", checksumSha256Base64));
  }

  @Override
  public synchronized S3PresignedResponseDTO presignUpload(
      String key, String contentType, Long expiresInSeconds) {
    records.add(new PresignRecord("upload", key, contentType, null, expiresInSeconds));
    return response("PUT", key, headers(contentType, null, null));
  }

  @Override
  public synchronized S3PresignedResponseDTO presignCreateOnlyUpload(
      String key, String contentType, Long expiresInSeconds) {
    records.add(new PresignRecord("createOnly", key, contentType, null, expiresInSeconds));
    return response("PUT", key, headers(contentType, "*", null));
  }

  @Override
  public synchronized S3PresignedResponseDTO presignDownload(String key, Long expiresInSeconds) {
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

  private static S3PresignedResponseDTO response(
      String method, String key, Map<String, String> headers) {
    return S3PresignedResponseDTO.builder()
        .bucket("test-bucket")
        .key(key)
        .method(method)
        .url("https://cdn.example.com/test-bucket/" + key + "?X-Amz-Signature=fake")
        .headers(headers)
        .expiresAt(Instant.now().plusSeconds(600L).toString())
        .build();
  }

  /** 一次预签名调用记录（kind 取值 upload/createOnly/checksummedCreateOnly/download）。 */
  public record PresignRecord(
      String kind,
      String key,
      String contentType,
      String checksumSha256Base64,
      Long expiresInSeconds) {}
}
