package fun.fengwk.kkstudio.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import fun.fengwk.kkstudio.core.storage.configuration.S3StorageProperties;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * {@link S3StorageService} 单元测试.
 *
 * @author fengwk
 */
public class S3StorageServiceTest {

  @Test
  public void testGetPublicUrl() {
    TestContext context = newTestContext("https://cdn.example.com/{bucket}");
    try {
      assertEquals(
          "https://cdn.example.com/test-bucket/dir/%E6%B5%8B%E8%AF%95%20image.png",
          context.storageService.getPublicUrl("/dir/测试 image.png"));
    } finally {
      context.close();
    }
  }

  @Test
  public void testPutObject() {
    TestContext context = newTestContext(null);
    try {
      PutObjectResponse response =
          context.storageService.putObject(
              "/dir/demo.txt", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L, "text/plain");
      assertEquals("etag-demo", response.eTag());
    } finally {
      context.close();
    }
  }

  @Test
  public void testExistsAndDownload() {
    TestContext context = newTestContext(null);
    try {
      assertTrue(context.storageService.exists("/dir/demo.txt"));
      assertArrayEquals(new byte[] {1, 2, 3}, context.storageService.download("dir/demo.txt"));
    } finally {
      context.close();
    }
  }

  @Test
  public void testExistsReturnsFalseForMissingKey() {
    TestContext context =
        newTestContext(
            null,
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
    TestContext context = newTestContext(null);
    try {
      assertThrows(IllegalArgumentException.class, () -> context.storageService.getPublicUrl(" "));
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

  private TestContext newTestContext(String publicBaseUrl) {
    return newTestContext(publicBaseUrl, methodName -> null);
  }

  private TestContext newTestContext(String publicBaseUrl, Function<String, Object> override) {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint("https://example-account-id.r2.cloudflarestorage.com");
    properties.setRegion("auto");
    properties.setBucket("test-bucket");
    properties.setAccessKey("ACCESS_KEY");
    properties.setSecretKey("SECRET_KEY");
    properties.setPublicBaseUrl(publicBaseUrl);
    return new TestContext(new S3StorageServiceImpl(properties, newNoopS3Client(override)));
  }

  private S3Client newNoopS3Client(Function<String, Object> override) {
    return (S3Client)
        Proxy.newProxyInstance(
            S3Client.class.getClassLoader(),
            new Class<?>[] {S3Client.class},
            (proxy, method, args) -> {
              Object overridden = override.apply(method.getName());
              if (overridden != null) {
                return overridden;
              }
              return switch (method.getName()) {
                case "close" -> null;
                case "putObject" -> PutObjectResponse.builder().eTag("etag-demo").build();
                case "headObject" -> HeadObjectResponse.builder().build();
                case "getObjectAsBytes" -> ResponseBytes.fromByteArray(
                    GetObjectResponse.builder().build(), new byte[] {1, 2, 3});
                case "serviceName" -> "s3";
                case "toString" -> "noopS3Client";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
  }

  private record TestContext(S3StorageService storageService) implements AutoCloseable {

    @Override
    public void close() {}
  }
}
