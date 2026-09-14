package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * {@link S3StorageService} 单元测试.
 *
 * @author fengwk
 */
public class S3StorageServiceTest {

  @Test
  public void testObjectContentDefensivelyCopiesBytes() {
    byte[] source = new byte[] {1, 2, 3};
    S3ObjectContent content = new S3ObjectContent(source, "image/png");

    source[0] = 0;
    assertArrayEquals(new byte[] {1, 2, 3}, content.getBytes());
    content.getBytes()[1] = 0;
    assertArrayEquals(new byte[] {1, 2, 3}, content.getBytes());
  }

  @Test
  public void testPutObject() {
    TestContext context = newTestContext();
    try {
      PutObjectResponse response =
          context.storageService.putObject(
              "dir/demo.txt", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L, "text/plain");
      assertEquals("etag-demo", response.eTag());
    } finally {
      context.close();
    }
  }

  @Test
  public void testExistsAndDownload() {
    TestContext context = newTestContext();
    try {
      assertTrue(context.storageService.exists("dir/demo.txt"));
      assertArrayEquals(new byte[] {1, 2, 3}, context.storageService.download("dir/demo.txt"));
    } finally {
      context.close();
    }
  }

  @Test
  public void testHeadReadAndDeleteUseStreamingApi() throws IOException {
    AtomicBoolean closed = new AtomicBoolean();
    TestContext context =
        newTestContext(
            methodName -> {
              if ("getObject".equals(methodName)) {
                return responseStream(new byte[] {1, 2, 3}, "image/png", closed);
              }
              return null;
            });
    try {
      S3ObjectMetadata metadata = context.storageService.headObject("dir/demo.png");
      assertEquals(3L, metadata.contentLength());

      try (S3ObjectStream object = context.storageService.readObject("dir/demo.png")) {
        assertEquals(1, object.inputStream().read());
        assertEquals("image/png", object.metadata().contentType());
      }
      assertTrue(closed.get());
      context.storageService.deleteObject("dir/demo.png");
    } finally {
      context.close();
    }
  }

  @Test
  public void testBoundedDownloadReturnsMetadata() {
    TestContext context = newTestContext();
    try {
      // 先 HEAD 再读取并复核字节长度，覆盖 ComfyUI 输入文件的大小边界。
      S3ObjectContent content = context.storageService.download("dir/demo.png", 3L);
      assertArrayEquals(new byte[] {1, 2, 3}, content.getBytes());
      content.getBytes()[0] = 0;
      assertArrayEquals(new byte[] {1, 2, 3}, content.getBytes());
      assertEquals("image/png", content.getContentType());
    } finally {
      context.close();
    }
  }

  @Test
  public void testBoundedDownloadRejectsOversizedObjectBeforeRead() {
    TestContext context =
        newTestContext(
            methodName ->
                "headObject".equals(methodName)
                    ? HeadObjectResponse.builder().contentLength(4L).build()
                    : null);
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> context.storageService.download("dir/demo.png", 3L));
    } finally {
      context.close();
    }
  }

  @Test
  public void testBoundedDownloadRejectsNegativeLimit() {
    TestContext context = newTestContext();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> context.storageService.download("dir/demo.png", -1L));
    } finally {
      context.close();
    }
  }

  @Test
  public void testBoundedDownloadRejectsMissingHeadContentLength() {
    TestContext context =
        newTestContext(
            methodName ->
                "headObject".equals(methodName)
                    ? HeadObjectResponse.builder().contentLength(null).build()
                    : null);
    try {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> context.storageService.download("dir/demo.png", 1024L));
      assertTrue(
          error.getMessage().contains("non-negative content length"),
          "actual message: " + error.getMessage());
    } finally {
      context.close();
    }
  }

  @Test
  public void testBoundedDownloadRejectsNegativeHeadContentLength() {
    TestContext context =
        newTestContext(
            methodName ->
                "headObject".equals(methodName)
                    ? HeadObjectResponse.builder().contentLength(-1L).build()
                    : null);
    try {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> context.storageService.download("dir/demo.png", 1024L));
      assertTrue(
          error.getMessage().contains("non-negative content length"),
          "actual message: " + error.getMessage());
    } finally {
      context.close();
    }
  }

  @Test
  public void testExistsReturnsFalseForMissingKey() {
    TestContext context =
        newTestContext(
            methodName -> {
              if ("headObject".equals(methodName)) {
                throw NoSuchKeyException.builder().message("missing").build();
              }
              return null;
            });
    try {
      assertFalse(context.storageService.exists("missing.txt"));
    } finally {
      context.close();
    }
  }

  @Test
  public void testRejectInvalidArguments() {
    TestContext context = newTestContext();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              context.storageService.putObject(
                  "/", new ByteArrayInputStream(new byte[0]), 0L, null));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              context.storageService.putObject(
                  "demo.txt", new ByteArrayInputStream(new byte[0]), -1L, null));
      assertThrows(
          NullPointerException.class,
          () -> context.storageService.putObject("demo.txt", null, 0L, null));
    } finally {
      context.close();
    }
  }

  /** checksum mode HEAD 必须带上 x-amz-checksum-mode: ENABLED，并把响应校验和映射进元数据。 */
  @Test
  public void testHeadObjectWithChecksumRequestsChecksumMode() {
    AtomicReference<HeadObjectRequest> captured = new AtomicReference<>();
    S3StorageProperties properties = newS3Properties();
    S3StorageService service =
        new S3StorageServiceImpl(
            properties,
            newNoopS3Client(
                methodName -> {
                  if ("headObject".equals(methodName)) {
                    return HeadObjectResponse.builder()
                        .contentLength(7L)
                        .contentType("image/png")
                        .checksumSHA256("YWJjZGVmZw==")
                        .build();
                  }
                  return null;
                },
                (proxy, method, args) -> {
                  if ("headObject".equals(method.getName())) {
                    captured.set((HeadObjectRequest) args[0]);
                  }
                }));
    S3ObjectMetadata metadata = service.headObjectWithChecksum("dir/demo.png");
    assertEquals(ChecksumMode.ENABLED, captured.get().checksumMode());
    assertEquals(7L, metadata.contentLength());
    assertEquals("YWJjZGVmZw==", metadata.checksumSha256());
    assertEquals("image/png", metadata.contentType());

    // 普通 HEAD 不带 checksum mode，也不返回校验和。
    captured.set(null);
    assertNull(service.headObject("dir/demo.png").checksumSha256());
    assertNull(captured.get().checksumMode());
  }

  /** copyObject 必须把源与目标都解析到固定 bucket 并原样下发。 */
  @Test
  public void testCopyObjectUsesFixedBucket() {
    AtomicReference<CopyObjectRequest> captured = new AtomicReference<>();
    S3StorageProperties properties = newS3Properties();
    S3StorageService service =
        new S3StorageServiceImpl(
            properties,
            newNoopS3Client(
                methodName ->
                    "copyObject".equals(methodName) ? CopyObjectResponse.builder().build() : null,
                (proxy, method, args) -> {
                  if ("copyObject".equals(method.getName())) {
                    captured.set((CopyObjectRequest) args[0]);
                  }
                }));
    service.copyObject("uploads/u1/original", "blobs/b1/original");
    assertEquals("test-bucket", captured.get().sourceBucket());
    assertEquals("uploads/u1/original", captured.get().sourceKey());
    assertEquals("test-bucket", captured.get().destinationBucket());
    assertEquals("blobs/b1/original", captured.get().destinationKey());
  }

  /** deleteObjectIfExists 对缺失对象（404/NoSuchKey）静默成功，其它 S3 错误原样抛出。 */
  @Test
  public void testDeleteObjectIfExistsIsIdempotent() {
    AtomicInteger deletedCalls = new AtomicInteger();
    S3StorageProperties properties = newS3Properties();
    S3StorageService service =
        new S3StorageServiceImpl(
            properties,
            newNoopS3Client(
                methodName -> null,
                (proxy, method, args) -> {
                  if ("deleteObject".equals(method.getName())) {
                    if (deletedCalls.incrementAndGet() == 1) {
                      throw NoSuchKeyException.builder().message("missing").build();
                    }
                  }
                }));
    assertDoesNotThrow(() -> service.deleteObjectIfExists("missing/object.bin"));
    assertDoesNotThrow(() -> service.deleteObjectIfExists("missing/object.bin"));
    assertEquals(2, deletedCalls.get());
  }

  /** deleteObjectIfExists 只吞 404：非 404 的服务端错误必须向上抛出。 */
  @Test
  public void testDeleteObjectIfExistsRethrowsNon404Errors() {
    S3StorageProperties properties = newS3Properties();
    S3StorageService service =
        new S3StorageServiceImpl(
            properties,
            newNoopS3Client(
                methodName -> null,
                (proxy, method, args) -> {
                  if ("deleteObject".equals(method.getName())) {
                    throw S3Exception.builder().message("forbidden").statusCode(403).build();
                  }
                }));
    assertThrows(S3Exception.class, () -> service.deleteObjectIfExists("forbidden.bin"));
  }

  private S3StorageProperties newS3Properties() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint("https://example-account-id.r2.cloudflarestorage.com");
    properties.setRegion("auto");
    properties.setBucket("test-bucket");
    properties.setAccessKey("ACCESS_KEY");
    properties.setSecretKey("SECRET_KEY");
    return properties;
  }

  private TestContext newTestContext() {
    return newTestContext(methodName -> null);
  }

  private TestContext newTestContext(Function<String, Object> override) {
    return new TestContext(new S3StorageServiceImpl(newS3Properties(), newNoopS3Client(override)));
  }

  private S3Client newNoopS3Client(Function<String, Object> override) {
    return newNoopS3Client(override, (proxy, method, args) -> {});
  }

  private S3Client newNoopS3Client(Function<String, Object> override, RequestObserver observer) {
    return (S3Client)
        Proxy.newProxyInstance(
            S3Client.class.getClassLoader(),
            new Class<?>[] {S3Client.class},
            (proxy, method, args) -> {
              observer.observe(proxy, method, args);
              Object overridden = override.apply(method.getName());
              if (overridden != null) {
                return overridden;
              }
              return switch (method.getName()) {
                case "close" -> null;
                case "putObject" -> PutObjectResponse.builder().eTag("etag-demo").build();
                case "headObject" -> HeadObjectResponse.builder().contentLength(3L).build();
                case "getObject" -> responseStream(
                    new byte[] {1, 2, 3}, "image/png", new AtomicBoolean());
                case "deleteObject" -> null;
                case "copyObject" -> CopyObjectResponse.builder().build();
                case "serviceName" -> "s3";
                case "toString" -> "noopS3Client";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
  }

  @FunctionalInterface
  private interface RequestObserver {
    void observe(Object proxy, Method method, Object[] args);
  }

  private ResponseInputStream<GetObjectResponse> responseStream(
      byte[] bytes, String contentType, AtomicBoolean closed) {
    ByteArrayInputStream source =
        new ByteArrayInputStream(bytes) {
          @Override
          public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        };
    return new ResponseInputStream<>(
        GetObjectResponse.builder()
            .contentLength((long) bytes.length)
            .contentType(contentType)
            .build(),
        AbortableInputStream.create(source));
  }

  private record TestContext(S3StorageService storageService) implements AutoCloseable {

    @Override
    public void close() {}
  }
}
