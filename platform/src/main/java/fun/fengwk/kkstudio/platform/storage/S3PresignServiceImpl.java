package fun.fengwk.kkstudio.platform.storage;

import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.awscore.presigner.PresignedRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link S3PresignService} 的默认实现：基于 AWS SDK v2 的 {@link S3Presigner} 在本地完成签名， 不与 S3 产生任何 IO。bucket
 * 来自 {@link S3StorageProperties}（部署连接），expiry 默认值/上限来自 {@link SystemSettings.StorageMedia}。
 *
 * <p>响应 headers 只返回调用方发起请求时必须显式设置的已签名头（例如 PUT 场景下的 {@code Content-Type}）， 浏览器根据 URL 自动发送且脚本禁止设置的
 * {@code Host} 不会出现在响应中。
 *
 * @author fengwk
 */
public class S3PresignServiceImpl implements S3PresignService {

  /** {@code contentType} 字段的最大长度，防止异常输入撑爆签名头。 */
  static final int MAX_CONTENT_TYPE_LENGTH = 255;

  private final S3StorageProperties properties;
  private final S3Presigner s3Presigner;
  private final SystemSettings.StorageMedia storageMedia;

  public S3PresignServiceImpl(
      S3StorageProperties properties,
      S3Presigner s3Presigner,
      SystemSettings.StorageMedia storageMedia) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.s3Presigner = Objects.requireNonNull(s3Presigner, "s3Presigner must not be null");
    this.storageMedia = Objects.requireNonNull(storageMedia, "storageMedia must not be null");
  }

  @Override
  public S3PresignedUrl presignChecksummedCreateOnlyUpload(
      String key, String contentType, String checksumSha256Base64, Long expiresInSeconds) {
    validateChecksumSha256Base64(checksumSha256Base64);
    PutObjectRequest putRequest =
        newPutObjectRequest(key, contentType)
            .ifNoneMatch("*")
            .checksumSHA256(checksumSha256Base64)
            .build();
    Duration duration = resolveExpires(expiresInSeconds);
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(duration)
            .putObjectRequest(putRequest)
            .build();
    PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
    return toPresignedUrl(presigned, "PUT");
  }

  @Override
  public S3PresignedUrl presignDownload(String key, Long expiresInSeconds) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    Duration duration = resolveExpires(expiresInSeconds);
    GetObjectRequest getRequest =
        GetObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build();
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(duration)
            .getObjectRequest(getRequest)
            .build();
    PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
    return toPresignedUrl(presigned, "GET");
  }

  private PutObjectRequest.Builder newPutObjectRequest(String key, String contentType) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    String normalizedContentType = normalizeContentType(contentType);
    PutObjectRequest.Builder putBuilder =
        PutObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey);
    if (normalizedContentType != null) {
      putBuilder.contentType(normalizedContentType);
    }
    return putBuilder;
  }

  private String normalizeContentType(String contentType) {
    if (!StringUtils.hasText(contentType)) {
      return null;
    }
    for (int i = 0; i < contentType.length(); i++) {
      Assert.isTrue(
          !Character.isISOControl(contentType.charAt(i)),
          "contentType must not contain control characters");
    }
    String normalized = contentType.trim();
    Assert.isTrue(
        normalized.length() <= MAX_CONTENT_TYPE_LENGTH,
        "contentType must not exceed " + MAX_CONTENT_TYPE_LENGTH + " characters");
    try {
      return MimeTypeUtils.parseMimeType(normalized).toString();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("contentType must be a valid media type", e);
    }
  }

  private void validateChecksumSha256Base64(String checksumSha256Base64) {
    Assert.hasText(checksumSha256Base64, "checksumSha256Base64 must not be blank");
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(checksumSha256Base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("checksumSha256Base64 must be valid base64", e);
    }
    Assert.isTrue(
        decoded.length == 32,
        "checksumSha256Base64 must decode to a 32-byte SHA-256 digest, got " + decoded.length);
  }

  private Duration resolveExpires(Long expiresInSeconds) {
    long defaultExpires = storageMedia.s3PresignDefaultExpiresSeconds();
    long maxExpires = storageMedia.s3PresignMaxExpiresSeconds();
    long requested = expiresInSeconds == null ? defaultExpires : expiresInSeconds;
    Assert.isTrue(requested > 0L, "expiresInSeconds must be positive");
    Assert.isTrue(requested <= maxExpires, "expiresInSeconds must not exceed " + maxExpires);
    return Duration.ofSeconds(requested);
  }

  private S3PresignedUrl toPresignedUrl(PresignedRequest presigned, String method) {
    Map<String, List<String>> signedHeaders = presigned.signedHeaders();
    // 对不暴露独立 signedHeaders map 的 SDK 实现，回退到已签名的 HTTP 请求头。
    if (signedHeaders == null || signedHeaders.isEmpty()) {
      signedHeaders = presigned.httpRequest().headers();
    }
    Map<String, String> flatHeaders = new LinkedHashMap<>();
    if (signedHeaders != null) {
      for (Map.Entry<String, List<String>> e : signedHeaders.entrySet()) {
        if ("host".equalsIgnoreCase(e.getKey())) {
          continue;
        }
        List<String> values = e.getValue();
        if (values != null && !values.isEmpty()) {
          flatHeaders.put(e.getKey(), values.get(0));
        }
      }
    }
    return S3PresignedUrl.builder()
        .method(method)
        .url(presigned.url().toString())
        .headers(flatHeaders)
        .expiresAt(presigned.expiration().toString())
        .build();
  }
}
