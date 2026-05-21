package fun.fengwk.kkstudio.core.storage;

import fun.fengwk.kkstudio.core.storage.configuration.S3StorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            assertEquals("https://cdn.example.com/test-bucket/dir/%E6%B5%8B%E8%AF%95%20image.png",
                    context.storageService.getPublicUrl("/dir/测试 image.png"));
        } finally {
            context.close();
        }
    }

    @Test
    public void testPutObject() {
        TestContext context = newTestContext(null);
        try {
            PutObjectResponse response = context.storageService.putObject(
                    "/dir/demo.txt", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3L, "text/plain");
            assertEquals("etag-demo", response.eTag());
        } finally {
            context.close();
        }
    }

    private TestContext newTestContext(String publicBaseUrl) {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("https://example-account-id.r2.cloudflarestorage.com");
        properties.setRegion("auto");
        properties.setBucket("test-bucket");
        properties.setAccessKey("ACCESS_KEY");
        properties.setSecretKey("SECRET_KEY");
        properties.setPublicBaseUrl(publicBaseUrl);
        return new TestContext(new S3StorageServiceImpl(properties, newNoopS3Client()));
    }

    private S3Client newNoopS3Client() {
        return (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(),
                new Class<?>[] {S3Client.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "putObject" -> PutObjectResponse.builder().eTag("etag-demo").build();
                    case "serviceName" -> "s3";
                    case "toString" -> "noopS3Client";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private record TestContext(S3StorageService storageService) implements AutoCloseable {

        @Override
        public void close() {
        }

    }

}
