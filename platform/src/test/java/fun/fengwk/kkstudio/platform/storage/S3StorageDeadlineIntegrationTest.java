package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import fun.fengwk.kkstudio.platform.harness.read.PlatformReadException;
import fun.fengwk.kkstudio.platform.harness.read.ReadTextWindow;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.impl.StorageBlobContentServiceImpl;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实 AWS SDK 客户端 + 本地 HTTP 服务端的读取截止集成测试。
 *
 * <p>测试意图：验证截止语义依赖的 SDK/HTTP 客户端行为确实成立 —— 同步 getObject 的返回流不受 {@code apiCallTimeout} 约束、{@code
 * close()} 会读完剩余响应体，因此必须由读取边界在看门狗到点时 abort 连接。测试使用真实 {@link S3Client}（Apache HTTP
 * 客户端、path-style）指向本地服务端：
 *
 * <ul>
 *   <li>服务端收到请求后不返回响应头：读取必须在截止点附近失败，而不是等到 socket 超时；
 *   <li>服务端发送部分响应体后停住：读取必须在截止点附近失败，且客户端不会把剩余响应体读完（abort 而非 close）。
 * </ul>
 *
 * <p>测试客户端的 socket 超时被显式放宽到 5 秒并远大于读取截止（1 秒），这样断言才能区分“截止机制生效”与“socket 超时兜底”。
 */
class S3StorageDeadlineIntegrationTest {

  private static final String BUCKET = "kk-studio-test";
  private static final String KEY = "blobs/00000000-0000-0000-0000-000000000000/original";

  private HttpServer server;
  private ExecutorService serverExecutor;
  private S3Client s3Client;

  @AfterEach
  void tearDown() {
    if (s3Client != null) {
      s3Client.close();
      s3Client = null;
    }
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (serverExecutor != null) {
      serverExecutor.shutdownNow();
      serverExecutor = null;
    }
  }

  /** 测试意图：服务端迟迟不返回响应头时，SDK 的 apiCallTimeout（由读取预算派生）必须在读取截止内中止阻塞的 getObject，并由读取边界翻译为受管资源读取超时。 */
  @Test
  void readFailsWithinDeadlineWhenServerStallsBeforeHeaders() throws IOException {
    CountDownLatch releaseHandler = new CountDownLatch(1);
    AtomicBoolean stalledWithoutResponse = new AtomicBoolean();
    startServer(
        exchange -> {
          try {
            stalledWithoutResponse.set(true);
            releaseHandler.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    S3StorageServiceImpl storageService = newStorageService();

    // SDK 层：apiCallTimeout 覆盖响应头握手，阻塞的手握会被 SDK 中止（异常类型由 SDK 决定）。
    long startedAt = System.nanoTime();
    assertThrows(
        ApiCallTimeoutException.class,
        () -> storageService.readObject(KEY, ReadDeadline.after(Duration.ofSeconds(1L))));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    assertTrue(stalledWithoutResponse.get(), "服务端必须已收到请求但未返回响应头");
    assertTrue(elapsedMillis < 4_000L, "握手必须在读取截止内失败，实际耗时 " + elapsedMillis + " ms");

    // 读取边界：握手超时对外表达为读取超时，并保留 SDK 失败作为原因。
    UUID blobId = UUID.randomUUID();
    StorageReadTimeoutException failure =
        assertThrows(
            StorageReadTimeoutException.class,
            () ->
                readResourceText(
                    newContentService(storageService, blobId, mock(StorageBlobManager.class)),
                    blobId));
    assertTrue(
        failure.getCause() instanceof ApiCallTimeoutException,
        "读取超时必须保留 SDK 握手失败作为原因，实际 " + failure.getCause());
    releaseHandler.countDown();
  }

  /** 测试意图：响应体停滞时读取在截止内失败、中止连接并释放 blob 引用，且不把剩余响应体读完（abort 而非 close 排水）。 */
  @Test
  void withBlobStreamFailsWithinDeadlineWithoutDrainingStalledBody() throws IOException {
    int declaredBodyBytes = 8 * 1024;
    AtomicInteger bodyBytesWritten = new AtomicInteger();
    AtomicBoolean closedByClient = new AtomicBoolean();
    CountDownLatch releaseHandler = new CountDownLatch(1);
    startServer(
        exchange -> {
          try {
            exchange.sendResponseHeaders(200, declaredBodyBytes);
            OutputStream body = exchange.getResponseBody();
            byte[] chunk = "text-line\n".repeat(7).getBytes(StandardCharsets.UTF_8);
            body.write(chunk);
            body.flush();
            bodyBytesWritten.addAndGet(chunk.length);
            // 慢速滴流：若客户端 close（排空剩余响应体），写入会一直成功直到整个声明长度。
            while (bodyBytesWritten.get() < declaredBodyBytes
                && !releaseHandler.await(50, TimeUnit.MILLISECONDS)) {
              body.write(chunk);
              body.flush();
              bodyBytesWritten.addAndGet(chunk.length);
            }
          } catch (IOException e) {
            // 客户端 abort 关停连接后写入失败，这是“未排空剩余响应体”的直接证据。
            closedByClient.set(true);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
            releaseHandler.countDown();
          }
        });
    S3StorageServiceImpl storageService = newStorageService();

    UUID blobId = UUID.randomUUID();
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentServiceImpl contentService =
        newContentService(storageService, blobId, blobManager);

    long startedAt = System.nanoTime();
    assertThrows(StorageReadTimeoutException.class, () -> readResourceText(contentService, blobId));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    assertTrue(elapsedMillis < 4_000L, "响应体停滞必须在读取截止内失败，实际耗时 " + elapsedMillis + " ms");
    assertTrue(awaitTrue(closedByClient, 2_000L), "读取边界必须 abort 连接");
    assertTrue(
        bodyBytesWritten.get() < declaredBodyBytes,
        "提前退出不能排空剩余响应体，实际写入 " + bodyBytesWritten.get() + " 字节");
    verify(blobManager).release(blobId);
    releaseHandler.countDown();
  }

  /**
   * 测试意图：消费方提前退出（二进制判定）时读取边界必须 abort 连接而不是 close 排空响应体。
   *
   * <p>服务端连续发送而声明长度极大（1 GiB）：若提前退出走 close 排空，服务端会把整个声明长度写出来；abort 则让客户端立刻停止 读取，服务端写入很快停在自身缓冲区大小附近。
   */
  @Test
  void withBlobStreamAbortsInsteadOfDrainingWhenConsumerStopsEarly() throws IOException {
    long declaredBodyBytes = 1024L * 1024L * 1024L;
    AtomicLong bodyBytesWritten = new AtomicLong();
    AtomicBoolean closedByClient = new AtomicBoolean();
    byte[] chunk = new byte[64 * 1024];
    Arrays.fill(chunk, (byte) 'x');
    chunk[0] = '\n';
    startServer(
        exchange -> {
          try {
            exchange.sendResponseHeaders(200, declaredBodyBytes);
            OutputStream body = exchange.getResponseBody();
            byte[] prefix = "text-line\n\u0000".getBytes(StandardCharsets.UTF_8);
            body.write(prefix);
            bodyBytesWritten.addAndGet(prefix.length);
            while (bodyBytesWritten.get() < declaredBodyBytes) {
              body.write(chunk);
              bodyBytesWritten.addAndGet(chunk.length);
            }
          } catch (IOException e) {
            // 客户端 abort 关停连接后写入失败，这是“未排空剩余响应体”的直接证据。
            closedByClient.set(true);
          } finally {
            exchange.close();
          }
        });
    S3StorageServiceImpl storageService = newStorageService();
    UUID blobId = UUID.randomUUID();
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentServiceImpl contentService =
        newContentService(storageService, blobId, blobManager);

    PlatformReadException failure =
        assertThrows(PlatformReadException.class, () -> readResourceText(contentService, blobId));

    assertTrue(failure.getMessage().contains("binary"), "实际失败：" + failure.getMessage());
    assertTrue(awaitTrue(closedByClient, 5_000L), "提前退出必须 abort 连接而不是排空响应体");
    assertTrue(
        bodyBytesWritten.get() < 64L * 1024L * 1024L,
        "提前退出不能排空剩余响应体，实际写入 " + bodyBytesWritten.get() + " 字节");
    verify(blobManager).release(blobId);
  }

  private static StorageBlobContentServiceImpl newContentService(
      S3StorageServiceImpl storageService, UUID blobId, StorageBlobManager blobManager) {
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);
    when(blobManager.release(blobId)).thenReturn(true);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return new StorageBlobContentServiceImpl(blobManager, storageService, transactionManager);
  }

  /** 走受管资源文本读取的完整边界：读取预算在入口冻结，超时由读取边界翻译。 */
  private static void readResourceText(StorageBlobContentServiceImpl contentService, UUID blobId) {
    contentService.withBlobStream(
        blobId,
        ReadDeadline.after(Duration.ofSeconds(1L)),
        stream ->
            ReadTextWindow.format(
                stream, null, null, null, "kkstudio:/resources/" + blobId, "unsupported"));
  }

  private static boolean awaitTrue(AtomicBoolean flag, long timeoutMillis) {
    long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
    while (System.nanoTime() < deadline) {
      if (flag.get()) {
        return true;
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return flag.get();
      }
    }
    return flag.get();
  }

  private void startServer(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "s3-test-server");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(serverExecutor);
    server.createContext("/", handler);
    server.start();
  }

  private S3StorageServiceImpl newStorageService() {
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint(endpoint);
    properties.setRegion("us-east-1");
    properties.setBucket(BUCKET);
    properties.setAccessKey("test-access-key");
    properties.setSecretKey("test-secret-key");
    s3Client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test-access-key", "test-secret-key")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .httpClientBuilder(ApacheHttpClient.builder().socketTimeout(Duration.ofSeconds(5L)))
            .build();
    return new S3StorageServiceImpl(properties, s3Client);
  }
}
