package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageConfiguration;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;

/** S3 基础设施 Readiness Probe 测试：验证必配连接参数缺失在构造期拦截、headBucket 成功时正常就绪，以及 bucket 异常时 fail-fast。 */
class S3ReadinessProbeTest {

  @Test
  void throwsIllegalArgumentExceptionWhenRequiredPropertiesAreMissing() {
    // 测试意图：证明必配 S3 基础设施属性（endpoint, region, bucket, accessKey, secretKey）缺失时，在构造期即拒绝启动。
    S3Client s3Client = mock(S3Client.class);

    assertThrows(
        IllegalArgumentException.class,
        () -> new S3ReadinessProbe(properties(null, "auto", "bucket", "ak", "sk"), s3Client));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new S3ReadinessProbe(
                properties("http://localhost:9000", "", "bucket", "ak", "sk"), s3Client));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new S3ReadinessProbe(
                properties("http://localhost:9000", "auto", "   ", "ak", "sk"), s3Client));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new S3ReadinessProbe(
                properties("http://localhost:9000", "auto", "bucket", null, "sk"), s3Client));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new S3ReadinessProbe(
                properties("http://localhost:9000", "auto", "bucket", "ak", ""), s3Client));
  }

  @Test
  void checkReadinessSucceedsWhenBucketIsAccessible() {
    // 测试意图：证明 headBucket 成功响应时，Readiness 探针成功完成，并触发了正确的 S3 headBucket 调用。
    S3Client s3Client = mock(S3Client.class);
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenReturn(HeadBucketResponse.builder().build());

    S3StorageProperties properties =
        properties("http://localhost:9000", "us-east-1", "my-bucket", "ak", "sk");
    S3ReadinessProbe probe = new S3ReadinessProbe(properties, s3Client);

    assertDoesNotThrow(probe::checkReadiness);
    verify(s3Client).headBucket(any(HeadBucketRequest.class));
  }

  @Test
  void checkReadinessFailsFastWithIllegalStateExceptionAndHidesCredentials() {
    // 测试意图：证明存储桶不可达或不存在时，探针包装为明确的 IllegalStateException，且异常信息中严禁泄露 AK/SK 敏感凭据。
    S3Client s3Client = mock(S3Client.class);
    String secretKey = "super-secret-sk-12345";
    String endpoint = "http://private-s3.internal:9000";
    String bucket = "non-existent-private-bucket";
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenThrow(
            NoSuchBucketException.builder()
                .message(endpoint + "/" + bucket + "?secret=" + secretKey)
                .build());

    S3StorageProperties properties = properties(endpoint, "us-east-1", bucket, "my-ak", secretKey);
    S3ReadinessProbe probe = new S3ReadinessProbe(properties, s3Client);

    IllegalStateException error = assertThrows(IllegalStateException.class, probe::checkReadiness);
    assertTrue(error.getMessage().contains("S3 bucket readiness check failed"));
    assertNull(error.getCause());
    assertFalse(error.getMessage().contains(endpoint));
    assertFalse(error.getMessage().contains(bucket));
    assertFalse(error.getMessage().contains(secretKey));
    assertFalse(error.getMessage().contains("my-ak"));
  }

  @Test
  void invalidEndpointsDoNotLeakConfigurationValues() {
    // 测试意图：证明 SDK client/presigner 构造失败时仅报告稳定错误，不通过异常链泄露内部 endpoint。
    String internalEndpoint = "http://private s3.internal:9000";
    S3StorageProperties properties =
        properties(internalEndpoint, "us-east-1", "private-bucket", "my-ak", "my-sk");
    properties.setPublicEndpoint(internalEndpoint);
    S3StorageConfiguration configuration = new S3StorageConfiguration();

    IllegalStateException clientError =
        assertThrows(IllegalStateException.class, () -> configuration.s3Client(properties));
    IllegalStateException presignerError =
        assertThrows(IllegalStateException.class, () -> configuration.s3Presigner(properties));

    assertEquals("S3 client configuration is invalid", clientError.getMessage());
    assertNull(clientError.getCause());
    assertFalse(clientError.getMessage().contains(internalEndpoint));
    assertEquals("S3 presigner configuration is invalid", presignerError.getMessage());
    assertNull(presignerError.getCause());
    assertFalse(presignerError.getMessage().contains(internalEndpoint));
  }

  private static S3StorageProperties properties(
      String endpoint, String region, String bucket, String accessKey, String secretKey) {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint(endpoint);
    properties.setRegion(region);
    properties.setBucket(bucket);
    properties.setAccessKey(accessKey);
    properties.setSecretKey(secretKey);
    return properties;
  }
}
