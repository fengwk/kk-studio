package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * {@link S3PresignService} 单元测试.
 *
 * <p>使用 AWS SDK 提供的真实 {@code S3Presigner}（path-style + SigV4）在本地完成签名，不与对象存储产生任何 IO，从而精准覆盖 key
 * 规范化、签名时长校验与响应字段语义。
 *
 * @author fengwk
 */
public class S3PresignServiceTest {

  private static final String ENDPOINT = "https://minio.example.local:9000";
  private static final String PUBLIC_ENDPOINT = "https://cdn.example.com";
  private static final String BUCKET = "test-bucket";
  private static final String REGION = "us-east-1";

  /**
   * PUT 预签名：URL 落在 public endpoint、method=PUT、bucket 与 key 与请求一致，且当指定 contentType 时该头会被签名进入响应
   * headers（浏览器必须原样回传）。
   */
  @Test
  public void testPresignUploadUsesPublicEndpointAndIncludesContentTypeHeader() {
    try (TestContext context = newTestContext()) {
      S3PresignedResponseDTO resp =
          context.service.presignUpload("uploads/demo.bin", "application/octet-stream", 120L);
      assertEquals(BUCKET, resp.getBucket());
      assertEquals("uploads/demo.bin", resp.getKey());
      assertEquals("PUT", resp.getMethod());
      assertNotNull(resp.getUrl());
      assertNotNull(resp.getExpiresAt());
      URI uri = URI.create(resp.getUrl());
      assertEquals("cdn.example.com", uri.getHost());
      assertEquals("/" + BUCKET + "/uploads/demo.bin", uri.getRawPath());
      assertTrue(
          resp.getUrl().contains("X-Amz-Signature="),
          "expected signed URL to carry X-Amz-Signature, got: " + resp.getUrl());
      assertTrue(
          resp.getUrl().contains("X-Amz-SignedHeaders="),
          "expected signed URL to declare signed headers, got: " + resp.getUrl());
      assertTrue(
          resp.getUrl().toLowerCase().contains("content-type"),
          "expected Content-Type to be among signed headers, got: " + resp.getUrl());
      assertNotNull(resp.getHeaders(), "signed headers must not be null");
      assertEquals("application/octet-stream", getHeader(resp, "content-type"));
      assertFalse(
          hasHeader(resp, "if-none-match"), "ordinary PUT must preserve overwrite semantics");
      assertFalse(resp.getUrl().toLowerCase().contains("if-none-match"));
      assertFalse(hasHeader(resp, "host"), "browser response headers must not include Host");
    }
  }

  /** Create-only PUT 必须签名并返回 If-None-Match，确保已存在对象无法被覆盖。 */
  @Test
  public void testPresignCreateOnlyUploadIncludesIfNoneMatchHeader() {
    try (TestContext context = newTestContext()) {
      S3PresignedResponseDTO resp =
          context.service.presignCreateOnlyUpload(
              "uploads/immutable.bin", "application/octet-stream", 120L);

      assertEquals("*", getHeader(resp, "if-none-match"));
      assertTrue(
          resp.getUrl().toLowerCase().contains("if-none-match"),
          "expected If-None-Match to be among signed headers, got: " + resp.getUrl());
      assertEquals("application/octet-stream", getHeader(resp, "content-type"));
      assertFalse(hasHeader(resp, "host"));
    }
  }

  /** GET 预签名：URL 落在 public endpoint、method=GET、不带 contentType 头。 */
  @Test
  public void testPresignDownloadUsesPublicEndpoint() {
    try (TestContext context = newTestContext()) {
      S3PresignedResponseDTO resp =
          context.service.presignDownload("exports/2026/07/report.pdf", 300L);
      assertEquals(BUCKET, resp.getBucket());
      assertEquals("exports/2026/07/report.pdf", resp.getKey());
      assertEquals("GET", resp.getMethod());
      URI uri = URI.create(resp.getUrl());
      assertEquals("cdn.example.com", uri.getHost());
      assertEquals("/" + BUCKET + "/exports/2026/07/report.pdf", uri.getRawPath());
      assertTrue(resp.getHeaders().isEmpty(), "GET presign should not expose implicit Host");
    }
  }

  /** contentType 空白时视为未提供，因此响应不要求浏览器显式设置任何请求头。 */
  @Test
  public void testBlankContentTypeIsOmitted() {
    try (TestContext context = newTestContext()) {
      S3PresignedResponseDTO resp = context.service.presignUpload("uploads/demo.bin", "  ", 120L);

      assertTrue(resp.getHeaders().isEmpty());
      assertFalse(resp.getUrl().toLowerCase().contains("content-type"));
    }
  }

  /** contentType 会去除首尾空白并按 Spring media type 规则规范化后参与签名。 */
  @Test
  public void testContentTypeIsNormalized() {
    try (TestContext context = newTestContext()) {
      S3PresignedResponseDTO resp =
          context.service.presignUpload("uploads/demo.png", "  image/png  ", 120L);

      assertEquals("image/png", getHeader(resp, "content-type"));
      assertFalse(hasHeader(resp, "host"));
    }
  }

  /** 非法、超长或包含控制字符的 contentType 必须在生成签名前被拒绝。 */
  @Test
  public void testRejectsInvalidContentType() {
    try (TestContext context = newTestContext()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignUpload("uploads/a.bin", "not-a-media-type", 120L));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              context.service.presignUpload(
                  "uploads/a.bin",
                  "a".repeat(S3PresignServiceImpl.MAX_CONTENT_TYPE_LENGTH + 1),
                  120L));
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignUpload("uploads/a.bin", "image/png\n", 120L));
    }
  }

  /** 未配置 publicEndpoint 时回退到 endpoint，服务端内部签名场景仍可工作。 */
  @Test
  public void testPresignFallsBackToEndpointWhenPublicEndpointBlank() {
    S3StorageProperties props = newS3Properties(PUBLIC_ENDPOINT, /*publicEndpointBlank*/ true);
    try (TestContext ctx = new TestContext(props, SystemSettings.StorageMedia.DEFAULT)) {
      S3PresignedResponseDTO resp = ctx.service.presignDownload("docs/readme.md", null);
      URI uri = URI.create(resp.getUrl());
      assertEquals("minio.example.local", uri.getHost());
      assertEquals(9000, uri.getPort());
    }
  }

  /** expiresInSeconds 为 {@code null} 时使用服务端默认（600s）， expiresAt 与当前时间的差距应大致落在默认窗口内。 */
  @Test
  public void testDefaultExpiresWhenNotProvided() {
    try (TestContext context = newTestContext()) {
      long before = System.currentTimeMillis();
      S3PresignedResponseDTO resp = context.service.presignDownload("docs/readme.md", null);
      long delta = Instant.parse(resp.getExpiresAt()).toEpochMilli() - before;
      long defaultExpiresMillis =
          SystemSettings.StorageMedia.DEFAULT.s3PresignDefaultExpiresSeconds() * 1000L;
      // SDK 签名窗口存在数秒抖动，给一个 30s 的余量
      assertTrue(
          delta >= defaultExpiresMillis - 30_000L && delta <= defaultExpiresMillis + 30_000L,
          "default expiry delta out of range: " + delta);
    }
  }

  /** 显式有效期必须为正数，不能把非法值静默替换成默认值。 */
  @Test
  public void testRejectsNonPositiveExpires() {
    try (TestContext context = newTestContext()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignUpload("uploads/a.bin", "text/plain", -1L));
    }
  }

  /** 超过服务端最大有效期必须作为参数错误拒绝。 */
  @Test
  public void testRejectsExpiresOverMax() {
    S3StorageProperties props = newS3Properties(PUBLIC_ENDPOINT, false);
    SystemSettings.StorageMedia storageMedia =
        new SystemSettings.StorageMedia(3_600L, true, 600L, 7_200L, 30_000L, 512, 80);
    try (TestContext context = new TestContext(props, storageMedia)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignDownload("big/file.bin", 86_400L));
    }
  }

  /** 前导 slash 表示绝对风格路径，不能被静默改写成另一个对象键。 */
  @Test
  public void testPresignRejectsLeadingSlash() {
    try (TestContext context = newTestContext()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignDownload("/dir/leading.bin", 60L));
    }
  }

  /** 校验失败：空白 key、normalized 后仍以 / 开头、. / .. 段、控制字符、超长 key 均必须拒绝。 */
  @Test
  public void testPresignRejectsInvalidKeys() {
    try (TestContext context = newTestContext()) {
      assertThrows(
          IllegalArgumentException.class, () -> context.service.presignUpload(null, null, null));
      assertThrows(
          IllegalArgumentException.class, () -> context.service.presignUpload("", null, null));
      assertThrows(
          IllegalArgumentException.class, () -> context.service.presignUpload("   ", null, null));
      assertThrows(
          IllegalArgumentException.class, () -> context.service.presignUpload("/", null, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignUpload("dir/./file", null, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignUpload("dir/../escape", null, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> context.service.presignUpload("dir/\nfile", null, null));
      String tooLong = "a".repeat(S3ObjectKeyNormalizer.MAX_KEY_LENGTH_BYTES + 1);
      assertThrows(
          IllegalArgumentException.class, () -> context.service.presignDownload(tooLong, null));
    }
  }

  /** 边界：恰好等于 MAX_KEY_LENGTH 仍合法（不越界即可）。 */
  @Test
  public void testPresignAcceptsKeyAtMaxLength() {
    try (TestContext context = newTestContext()) {
      String key = "b".repeat(S3ObjectKeyNormalizer.MAX_KEY_LENGTH_BYTES);
      S3PresignedResponseDTO resp = context.service.presignDownload(key, null);
      assertEquals(key, resp.getKey());
      assertNotNull(resp.getUrl());
    }
  }

  /** 校验失败的 key 永不泄露到响应：异常路径上不返回 DTO。 */
  @Test
  public void testPresignFailureDoesNotLeakResponse() {
    try (TestContext context = newTestContext()) {
      S3PresignedResponseDTO resp = context.service.presignDownload("ok.bin", null);
      assertNotNull(resp);
      assertThrows(IllegalArgumentException.class, () -> context.service.presignDownload("", null));
      // res 在失败之前已构建，并保持有效；关键断言保持稳定
      assertEquals("ok.bin", resp.getKey());
      assertNull(resp.getHeaders().get("Content-Type"), "GET presign should not sign Content-Type");
      assertFalse(resp.getUrl().isEmpty());
    }
  }

  /** 浏览器直传 PUT：签名必须同时携带 If-None-Match: * 与 x-amz-checksum-sha256，且校验和出现在 signed headers。 */
  @Test
  public void testPresignChecksummedCreateOnlyUploadIncludesChecksumAndIfNoneMatch() {
    String checksum = Base64.getEncoder().encodeToString(new byte[32]);
    try (TestContext context = newTestContext()) {
      S3PresignedResponseDTO resp =
          context.service.presignChecksummedCreateOnlyUpload(
              "uploads/demo.bin", "application/octet-stream", checksum, 120L);

      assertEquals("PUT", resp.getMethod());
      assertEquals("uploads/demo.bin", resp.getKey());
      assertEquals("*", getHeader(resp, "if-none-match"));
      assertEquals(checksum, getHeader(resp, "x-amz-checksum-sha256"));
      assertEquals("application/octet-stream", getHeader(resp, "content-type"));
      assertTrue(
          resp.getUrl().toLowerCase().contains("x-amz-checksum-sha256"),
          "checksum header must be signed into the URL, got: " + resp.getUrl());
      assertTrue(
          resp.getUrl().toLowerCase().contains("if-none-match"),
          "If-None-Match must be signed into the URL, got: " + resp.getUrl());
      assertFalse(hasHeader(resp, "host"));
    }
  }

  /** 校验和必须是可解码且恰好 32 字节的 base64，否则在签名前拒绝。 */
  @Test
  public void testPresignRejectsInvalidChecksum() {
    try (TestContext context = newTestContext()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              context.service.presignChecksummedCreateOnlyUpload(
                  "uploads/a.bin", "text/plain", "   ", 120L));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              context.service.presignChecksummedCreateOnlyUpload(
                  "uploads/a.bin", "text/plain", "not-base64!", 120L));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              context.service.presignChecksummedCreateOnlyUpload(
                  "uploads/a.bin",
                  "text/plain",
                  Base64.getEncoder().encodeToString(new byte[31]),
                  120L));
    }
  }

  private static boolean hasHeader(S3PresignedResponseDTO response, String name) {
    return response.getHeaders().keySet().stream().anyMatch(name::equalsIgnoreCase);
  }

  private static String getHeader(S3PresignedResponseDTO response, String name) {
    return response.getHeaders().entrySet().stream()
        .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private TestContext newTestContext() {
    return new TestContext(
        newS3Properties(PUBLIC_ENDPOINT, false), SystemSettings.StorageMedia.DEFAULT);
  }

  private static S3StorageProperties newS3Properties(
      String publicEndpoint, boolean publicEndpointBlank) {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint(ENDPOINT);
    if (!publicEndpointBlank) {
      properties.setPublicEndpoint(publicEndpoint);
    }
    properties.setRegion(REGION);
    properties.setBucket(BUCKET);
    properties.setAccessKey("local-test-access-key");
    properties.setSecretKey("local-test-secret-key");
    return properties;
  }

  private static final class TestContext implements AutoCloseable {

    final S3PresignService service;
    final S3Presigner presigner;

    TestContext(S3StorageProperties properties, SystemSettings.StorageMedia storageMedia) {
      this.presigner =
          S3Presigner.builder()
              .endpointOverride(URI.create(properties.getEffectivePublicEndpoint()))
              .region(Region.of(properties.getRegion()))
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create(
                          properties.getAccessKey(), properties.getSecretKey())))
              .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
              .build();
      this.service = new S3PresignServiceImpl(properties, presigner, storageMedia);
    }

    @Override
    public void close() {
      presigner.close();
    }
  }
}
