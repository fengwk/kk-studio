package fun.fengwk.kkstudio.core.storage;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import fun.fengwk.kkstudio.core.storage.configuration.S3StorageProperties;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * S3 存储服务实现.
 *
 * @author fengwk
 */
public class S3StorageServiceImpl implements S3StorageService {

  private final S3StorageProperties properties;
  private final S3Client s3Client;

  public S3StorageServiceImpl(S3StorageProperties properties, S3Client s3Client) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
  }

  @Override
  public PutObjectResponse putObject(
      String key, InputStream content, long contentLength, String contentType) {
    Objects.requireNonNull(content, "content must not be null");
    Assert.isTrue(contentLength >= 0L, "contentLength must be greater than or equal to 0");
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    PutObjectRequest.Builder builder =
        PutObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey);
    if (StringUtils.hasText(contentType)) {
      builder.contentType(contentType);
    }
    return s3Client.putObject(builder.build(), RequestBody.fromInputStream(content, contentLength));
  }

  @Override
  public boolean exists(String key) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    try {
      s3Client.headObject(
          HeadObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (AwsServiceException e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  @Override
  public String getPublicUrl(String key) {
    Assert.hasText(
        properties.getPublicBaseUrl(),
        "kk-studio.storage.s3.public-base-url must not be blank when resolving public url");
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    return normalizePublicBaseUrl(properties.getPublicBaseUrl()) + "/" + encodeKey(normalizedKey);
  }

  @Override
  public byte[] download(String key) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    ResponseBytes<GetObjectResponse> response = getObject(normalizedKey);
    return response.asByteArray();
  }

  @Override
  public S3ObjectContent download(String key, long maxSizeBytes) {
    Assert.isTrue(maxSizeBytes >= 0L, "maxSizeBytes must be greater than or equal to 0");
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    HeadObjectRequest headRequest =
        HeadObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build();
    Long declaredContentLength = s3Client.headObject(headRequest).contentLength();
    // 防御性校验：S3 响应必须报告非负的对象长度；缺失或非法声明不允许继续读取，避免把"无长度"对象加载到下游。
    Assert.isTrue(
        declaredContentLength != null && declaredContentLength >= 0L,
        "S3 HEAD response must report a non-negative content length for key: " + normalizedKey);
    Assert.isTrue(declaredContentLength <= maxSizeBytes, "S3 object exceeds max input file size");
    ResponseBytes<GetObjectResponse> response = getObject(normalizedKey);
    byte[] bytes = response.asByteArray();
    Assert.isTrue(bytes.length <= maxSizeBytes, "S3 object exceeds max input file size");
    return new S3ObjectContent(bytes, response.response().contentType());
  }

  private ResponseBytes<GetObjectResponse> getObject(String normalizedKey) {
    return s3Client.getObjectAsBytes(
        GetObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build());
  }

  private String normalizePublicBaseUrl(String publicBaseUrl) {
    String normalized = publicBaseUrl.strip().replace("{bucket}", properties.getBucket());
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private String encodeKey(String key) {
    return Arrays.stream(key.split("/", -1))
        .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
        .collect(Collectors.joining("/"));
  }
}
