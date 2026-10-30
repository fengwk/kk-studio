package fun.fengwk.kkstudio.platform.pluginhost.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginMediaFamily;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceUnavailableException;
import fun.fengwk.kkstudio.platform.pluginhost.PluginProperties;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/** StoragePluginResourceGateway 核心生产实现高强度测试。 */
class StoragePluginResourceGatewayTest {

  @TempDir Path tempDir;

  private HarnessStore harnessStore;
  private SessionBlobRefManager sessionBlobRefManager;
  private StorageBlobManager storageBlobManager;
  private StorageUploadService storageUploadService;
  private PluginProperties properties;
  private FakeRemoteMediaTransport transport;
  private StoragePluginResourceGateway gateway;

  @BeforeEach
  void setUp() {
    harnessStore = mock(HarnessStore.class);
    sessionBlobRefManager = mock(SessionBlobRefManager.class);
    storageBlobManager = mock(StorageBlobManager.class);
    storageUploadService = mock(StorageUploadService.class);

    properties = new PluginProperties();
    properties.getResource().setTempDirectory(tempDir.toString());
    properties.getResource().setMaxBytes(1024 * 1024);
    properties.getResource().setRequestTimeout(Duration.ofSeconds(10));
    properties.getResource().setConnectTimeout(Duration.ofSeconds(2));
    properties.getResource().setUploadTimeout(Duration.ofMinutes(1));

    transport = new FakeRemoteMediaTransport();

    gateway =
        new StoragePluginResourceGateway(
            harnessStore,
            sessionBlobRefManager,
            storageBlobManager,
            storageUploadService,
            properties,
            transport);
  }

  // =========================================================================
  // 1. URI 解析与 Session 授权（resolveSessionResource）
  // =========================================================================

  /** 合法规范 URI、有效 Thread 且包含 blob 引用时，成功签发原件下载地址，并断言确切调用。 */
  @Test
  void resolveSessionResourceSuccess() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    mockThreadSession(threadId, sessionId);
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);

    String signedUrl = "https://s3.example.com/blobs/" + blobId + "?token=xyz";
    StoragePresignedUrlDTO presigned = StoragePresignedUrlDTO.builder().url(signedUrl).build();
    when(storageBlobManager.presignOriginalUrl(blobId)).thenReturn(presigned);

    URI resolved = gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId);

    assertEquals(URI.create(signedUrl), resolved);
    verify(storageBlobManager).presignOriginalUrl(blobId);
  }

  /** 非规范形态的 resourceUri 必须全部确定性拒绝，且绝对不触发任何底层存储或会话交互。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "kkstudio:/resources/00000000-0000-0000-0000-00000000000A",
        "kkstudio:/resources/A87D0E54-6A92-4C61-B9D1-331B2D98822A",
        "kkstudio:/resources/00000000-0000-0000-0000-000000000001/extra",
        "kkstudio:/other/00000000-0000-0000-0000-000000000001",
        "kkstudio:/resources/00000000-0000-0000-0000-000000000001?download=true",
        "kkstudio:/resources/00000000-0000-0000-0000-000000000001#fragment",
        "kkstudio:/resources/",
        "http://example.com/resources/00000000-0000-0000-0000-000000000001",
        "https://example.com/resources/00000000-0000-0000-0000-000000000001",
        "",
        "   "
      })
  void resolveSessionResourceRejectsNonCanonicalUrisWithoutStoreCalls(String invalidUri) {
    UUID threadId = UUID.randomUUID();

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, invalidUri));
    assertTrue(exception.getMessage().contains("must be kkstudio:/resources/<blobId>"));
    verifyNoInteractions(harnessStore, sessionBlobRefManager, storageBlobManager);
  }

  /** resourceUri 为 null 时必须立即失败，且不触发任何底层存储调用。 */
  @Test
  void resolveSessionResourceRejectsNullUriWithoutStoreCalls() {
    UUID threadId = UUID.randomUUID();

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, null));
    assertTrue(exception.getMessage().contains("must be kkstudio:/resources/<blobId>"));
    verifyNoInteractions(harnessStore, sessionBlobRefManager, storageBlobManager);
  }

  /** threadId 为 null 时必须立即拒绝，且不触发任何底层存储或会话交互。 */
  @Test
  void resolveSessionResourceRejectsNullThreadIdWithoutStoreCalls() {
    UUID blobId = UUID.randomUUID();
    String uri = "kkstudio:/resources/" + blobId;

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(null, uri));
    assertTrue(exception.getMessage().contains("requires an invocation thread id"));
    verifyNoInteractions(harnessStore, sessionBlobRefManager, storageBlobManager);
  }

  /** Thread 记录不存在时，必须确定性拒绝且不调用 SessionBlobRefManager 或 StorageBlobManager。 */
  @Test
  void resolveSessionResourceFailsWhenThreadNotFound() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    mockThreadNotFound(threadId);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
    assertTrue(exception.getMessage().contains("the invocation thread is no longer available"));
    verifyNoInteractions(sessionBlobRefManager, storageBlobManager);
  }

  /** 当 Session 并不包含该 blobId 引用时（越权访问），必须拒绝且绝不签发任何预签名 URL。 */
  @Test
  void resolveSessionResourceRejectsUnauthorizedAccessWithoutPresigning() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    mockThreadSession(threadId, sessionId);
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(false);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
    assertTrue(exception.getMessage().contains("does not reference this resource"));
    verifyNoInteractions(storageBlobManager);
  }

  /** 签发原件 URL 抛出 StorageResourceNotFoundException 时必须收敛为 PluginResourceUnavailableException。 */
  @Test
  void resolveSessionResourceConvergencesStorageResourceNotFoundException() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    mockThreadSession(threadId, sessionId);
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);
    when(storageBlobManager.presignOriginalUrl(blobId))
        .thenThrow(new StorageResourceNotFoundException("blob", blobId.toString()));

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
    assertTrue(exception.getMessage().contains("is no longer available"));
    assertTrue(exception.getCause() instanceof StorageResourceNotFoundException);
  }

  /** 预签名返回非 HTTPS、带 userinfo、空 host 或格式非法时必须拒绝，防不可用地址交给插件。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://s3.example.com/blob",
        "https://user:pass@s3.example.com/blob",
        "https:///blob-without-host",
        "invalid://s3.example.com/blob",
        "not-a-valid-url"
      })
  void resolveSessionResourceRejectsUnusablePresignedUrls(String url) {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    mockThreadSession(threadId, sessionId);
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);
    when(storageBlobManager.presignOriginalUrl(blobId))
        .thenReturn(StoragePresignedUrlDTO.builder().url(url).build());

    assertThrows(
        PluginResourceUnavailableException.class,
        () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
  }

  /** 预签名 DTO 或其内部 URL 为空时必须拒绝。 */
  @Test
  void resolveSessionResourceRejectsNullOrBlankPresignedUrl() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    mockThreadSession(threadId, sessionId);
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);

    when(storageBlobManager.presignOriginalUrl(blobId)).thenReturn(null);
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));

    when(storageBlobManager.presignOriginalUrl(blobId))
        .thenReturn(StoragePresignedUrlDTO.builder().url("").build());
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
  }

  /** 底层事务读取失败抛出 RuntimeException 时必须收敛为 PluginResourceUnavailableException。 */
  @Test
  void resolveSessionResourceConvergencesStoreTransactionException() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();

    when(harnessStore.transaction(any())).thenThrow(new RuntimeException("Database timeout"));
    PluginResourceUnavailableException txEx =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
    assertTrue(txEx.getMessage().contains("cannot read the invocation thread"));
  }

  /**
   * sessionBlobRefManager.contains 抛出 RuntimeException 时必须收敛为 PluginResourceUnavailableException。
   */
  @Test
  void resolveSessionResourceConvergencesSessionRefContainsException() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();

    mockThreadSession(threadId, sessionId);
    when(sessionBlobRefManager.contains(sessionId, blobId))
        .thenThrow(new RuntimeException("Database error"));
    PluginResourceUnavailableException authEx =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
    assertTrue(authEx.getMessage().contains("cannot verify session resource ownership"));
  }

  // =========================================================================
  // 2. SSRF 地址准入与前置校验（stageRemoteMedia）
  // =========================================================================

  /** 远端媒体 URI 格式不合法（非 HTTPS、带 userinfo、带 fragment、非绝对路径）时在任何网络交互前失败。 */
  @Test
  void stageRemoteMediaRejectsInvalidRemoteUriBeforeTransport() {
    URI nonHttps = URI.create("http://media.example.com/test.png");
    URI withUserInfo = URI.create("https://user:pass@media.example.com/test.png");
    URI withFragment = URI.create("https://media.example.com/test.png#fragment");
    URI nonAbsolute = URI.create("/path/only/test.png");

    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(nonHttps, PluginMediaFamily.IMAGE, "test.png"));
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(withUserInfo, PluginMediaFamily.IMAGE, "test.png"));
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(withFragment, PluginMediaFamily.IMAGE, "test.png"));
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(nonAbsolute, PluginMediaFamily.IMAGE, "test.png"));
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(null, PluginMediaFamily.IMAGE, "test.png"));

    assertEquals(0, transport.getGetCallCount());
  }

  // =========================================================================
  // 3. 下载安全与预算（stageRemoteMedia）
  // =========================================================================

  /** 远端返回 3xx 重定向时必须确定性拒绝，且禁止发起第二次请求跟随重定向。 */
  @Test
  void stageRemoteMediaRejectsRedirectWithoutFollowing() {
    URI uri = URI.create("https://media.example.com/pic.png");
    transport.setGetResponse(
        302, Map.of("location", List.of("https://other.example.com/pic.png")), new byte[0]);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("responded with status 302"));
    assertEquals(1, transport.getGetCallCount());
  }

  /** 远端返回 4xx/5xx HTTP 错误时必须确定性失败。 */
  @ParameterizedTest
  @ValueSource(ints = {400, 403, 404, 500, 502, 503})
  void stageRemoteMediaRejectsHttpErrorStatuses(int status) {
    URI uri = URI.create("https://media.example.com/pic.png");
    transport.setGetResponse(status, Map.of(), new byte[0]);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("responded with status " + status));
  }

  /** Content-Length 超过 max-bytes 时，必须在读取任何 body 字节前失败，且不残留临时文件。 */
  @Test
  void stageRemoteMediaRejectsContentLengthExceedingMaxBytesBeforeReadingBody() throws IOException {
    long maxBytes = 1024L;
    properties.getResource().setMaxBytes(maxBytes);
    URI uri = URI.create("https://media.example.com/pic.png");

    TrackedInputStream body = new TrackedInputStream(createPngData(2048));
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(maxBytes + 1))), body);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("more bytes than this deployment can stage"));
    assertFalse(body.isReadInvoked(), "Body should not have been read");
    assertTrue(body.isClosed(), "Body stream must have been closed");
    assertTempDirClean();
  }

  /** 未声明 Content-Length 但实际读取字节数超过 max-bytes 时，必须失败且清理已写入的临时文件。 */
  @Test
  void stageRemoteMediaRejectsActualBodyExceedingMaxBytesWithoutContentLength() throws IOException {
    long maxBytes = 256L;
    properties.getResource().setMaxBytes(maxBytes);
    URI uri = URI.create("https://media.example.com/pic.png");

    byte[] oversizedData = createPngData((int) maxBytes + 100);
    transport.setGetResponse(200, Map.of(), oversizedData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("exceeds the configured staging byte budget"));
    assertTempDirClean();
  }

  /** Content-Length 声明值与实际读取字节数不一致（少报或多报）时必须失败并清理临时文件。 */
  @Test
  void stageRemoteMediaRejectsContentLengthMismatch() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] actualData = createPngData(100);

    // 少报
    transport.setGetResponse(200, Map.of("content-length", List.of("50")), actualData);
    PluginResourceUnavailableException underEx =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(underEx.getMessage().contains("content-length does not match"));
    assertTempDirClean();

    // 多报
    transport.setGetResponse(200, Map.of("content-length", List.of("200")), actualData);
    PluginResourceUnavailableException overEx =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(overEx.getMessage().contains("content-length does not match"));
    assertTempDirClean();
  }

  /** max-bytes 上限界限：恰好等于上限时成功，恰好超过 1 字节时失败。 */
  @Test
  void stageRemoteMediaMaxBytesBoundary() throws IOException {
    long maxBytes = 200L;
    properties.getResource().setMaxBytes(maxBytes);
    URI uri = URI.create("https://media.example.com/pic.png");

    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    mockReserveReady(uploadId, blobId);

    // 恰好等于 maxBytes
    byte[] exactData = createPngData((int) maxBytes);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(maxBytes))), exactData);
    ResourceRef ref = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png");
    assertEquals(maxBytes, ref.size());
    assertTempDirClean();

    // 恰好超过 1 字节
    byte[] plusOneData = createPngData((int) maxBytes + 1);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(maxBytes + 1))), plusOneData);
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTempDirClean();
  }

  /** 非数字的 Content-Length 请求头必须确定性失败。 */
  @Test
  void stageRemoteMediaRejectsNonNumericContentLength() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    TrackedInputStream body = new TrackedInputStream(createPngData(100));
    transport.setGetResponse(200, Map.of("content-length", List.of("not-a-number")), body);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("content-length is not a number"));
    assertTrue(body.isClosed(), "响应头不合法时仍须关闭下载响应体");
    assertTempDirClean();
  }

  /** 成功下载路径：临时文件写入确切字节，SHA-256 摘要与大小准确计算并传递给上传服务。 */
  @Test
  void stageRemoteMediaComputesAccurateDigestAndSize() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(512);
    String expectedSha256 = computeSha256(pngData);

    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    mockReserveReady(uploadId, blobId);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);
    ResourceRef ref = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png");

    assertEquals(pngData.length, ref.size());
    assertEquals(expectedSha256, ref.sha256());
    assertEquals("image/png", ref.mediaType());
    assertEquals("pic.png", ref.name());

    ArgumentCaptor<StorageUploadReserveRequestDTO> captor =
        ArgumentCaptor.forClass(StorageUploadReserveRequestDTO.class);
    verify(storageUploadService).reserve(captor.capture());
    assertEquals(expectedSha256, captor.getValue().getSha256());
    assertEquals((long) pngData.length, captor.getValue().getSizeBytes());
    assertEquals("image/png", captor.getValue().getMediaType());
    assertTempDirClean();
  }

  /** 下载过程中超过 requestTimeout 预算时，必须抛出超时异常且清理临时文件。 */
  @Test
  void stageRemoteMediaFailsWhenDownloadExceedsDeadline() throws IOException {
    properties.getResource().setRequestTimeout(Duration.ofNanos(1));
    URI uri = URI.create("https://media.example.com/pic.png");

    // 构造一个多次读取的分块流，使得进入 while 循环后必定超过 1 纳秒 deadline
    byte[] pngData = createPngData(2048);
    InputStream slowStream =
        new InputStream() {
          private int index = 0;

          @Override
          public int read() {
            if (index >= pngData.length) {
              return -1;
            }
            return pngData[index++] & 0xFF;
          }

          @Override
          public int read(byte[] b, int off, int len) {
            if (index >= pngData.length) {
              return -1;
            }
            int available = Math.min(len, 64);
            System.arraycopy(pngData, index, b, off, available);
            index += available;
            return available;
          }
        };

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), slowStream);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("exceeded the response deadline"));
    assertTempDirClean();
  }

  /**
   * 响应体在读完响应头后完全停滞时，调用线程不允许被永久挂住：整体期限到点即确定性失败并清理临时文件。
   *
   * <p>这条用例守住的是「HTTP 请求期限只覆盖到响应头」这一真实缺口：若复制只在调用线程内同步进行，停滞的 {@code read} 会一直阻塞。
   */
  @Test
  void stageRemoteMediaFailsWhenBodyStallsBeyondDeadline() throws Exception {
    properties.getResource().setRequestTimeout(Duration.ofMillis(200));
    URI uri = URI.create("https://media.example.com/stalled.png");

    CountDownLatch release = new CountDownLatch(1);
    InputStream stalledStream =
        new InputStream() {
          @Override
          public int read() throws IOException {
            awaitRelease();
            return -1;
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            awaitRelease();
            return -1;
          }

          private void awaitRelease() throws IOException {
            try {
              release.await();
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IOException("interrupted while waiting for the stalled body", error);
            }
          }

          @Override
          public void close() {
            release.countDown();
          }
        };
    transport.setGetResponse(200, Map.of(), stalledStream);

    long startedNanos = System.nanoTime();
    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "stalled.png"));
    long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;

    assertTrue(exception.getMessage().contains("exceeded the response deadline"));
    assertTrue(
        elapsedMillis < 5_000L,
        () -> "Stalled body must not block the caller indefinitely, took " + elapsedMillis + "ms");
    assertTrue(release.await(2, TimeUnit.SECONDS), "Gateway must close the stalled response body");
    assertTempDirClean();
  }

  // =========================================================================
  // 4. MIME 判定与族校验（stageRemoteMedia）
  // =========================================================================

  /** 当魔数嗅探出的类型与调用方声明的媒体族不匹配时（如图片能力拿到 WAV 音频），必须拒绝并清理临时文件。 */
  @Test
  void stageRemoteMediaFailsWhenMagicMismatchesDeclaredFamily() throws IOException {
    URI uri = URI.create("https://media.example.com/sound.wav");
    byte[] wavData = createWavData(256);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(wavData.length))), wavData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "sound.wav"));
    assertTrue(exception.getMessage().contains("remote media type is not the declared image"));
    assertTempDirClean();
  }

  /** 当魔数无法识别时，回退到 Content-Type 响应头，规范化去除参数后匹配族成功。 */
  @Test
  void stageRemoteMediaFallsBackToNormalizedContentTypeWhenMagicUnknown() throws IOException {
    URI uri = URI.create("https://media.example.com/data.txt");
    byte[] textData = "plain text without magic header".getBytes();

    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    mockReserveReady(uploadId, blobId);

    transport.setGetResponse(
        200,
        Map.of(
            "content-length", List.of(String.valueOf(textData.length)),
            "content-type", List.of("Text/Plain; charset=UTF-8")),
        textData);

    ResourceRef ref = stageRemoteMedia(uri, PluginMediaFamily.DOCUMENT, "data.txt");
    assertEquals("text/plain", ref.mediaType());
    assertTempDirClean();
  }

  /** 当魔数无法识别且回退的 Content-Type 也不属于声明族时，必须拒绝并清理临时文件。 */
  @Test
  void stageRemoteMediaFailsWhenFallbackContentTypeMismatchesFamily() throws IOException {
    URI uri = URI.create("https://media.example.com/data.bin");
    byte[] rawData = new byte[] {0x01, 0x02, 0x03, 0x04};

    transport.setGetResponse(
        200,
        Map.of(
            "content-length", List.of(String.valueOf(rawData.length)),
            "content-type", List.of("text/plain")),
        rawData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "data.bin"));
    assertTrue(exception.getMessage().contains("remote media type is not the declared image"));
    assertTempDirClean();
  }

  /** 魔数与 Content-Type 均无法判定时，必须拒绝并清理临时文件。 */
  @Test
  void stageRemoteMediaFailsWhenBothMagicAndContentTypeUnknown() throws IOException {
    URI uri = URI.create("https://media.example.com/mystery");
    byte[] rawData = new byte[] {0x01, 0x02, 0x03, 0x04};

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(rawData.length))), rawData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.DOCUMENT, "mystery"));
    assertTrue(exception.getMessage().contains("cannot determine remote media type"));
    assertTempDirClean();
  }

  // =========================================================================
  // 5. reserve / PUT / complete 与清理（stageRemoteMedia）
  // =========================================================================

  /** reserve 返回 READY（去重命中）时，必须不调用 PUT、不调用 complete，并返回准确引用且清理临时文件。 */
  @Test
  void stageRemoteMediaReserveReadySkipsPutAndComplete() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(300);

    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    mockReserveReady(uploadId, blobId);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    ResourceRef ref = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png");

    assertEquals(ResourceRef.blobUploadUri(uploadId), ref.uri());
    assertEquals((long) pngData.length, ref.size());
    assertEquals(computeSha256(pngData), ref.sha256());
    assertEquals("image/png", ref.mediaType());
    assertEquals("pic.png", ref.name());

    assertEquals(0, transport.getPutCallCount());
    verify(storageUploadService, never()).complete(any());
    assertTempDirClean();
  }

  /**
   * reserve 返回 PENDING 时，必须调用 PUT 直传（原样携带 signed headers，过滤 restricted headers，上传与源一致）， 成功后调用
   * complete，返回 READY 后结束，且清理临时文件。
   */
  @Test
  void stageRemoteMediaReservePendingExecutesPutAndCompletes() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(400);

    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();

    Map<String, String> signedHeaders =
        Map.of(
            "x-amz-checksum-sha256", "checksum-abc",
            "content-type", "image/png",
            "Host", "s3.example.com",
            "content-length", "400",
            "Connection", "keep-alive");

    StoragePresignedUrlDTO presignedPut =
        StoragePresignedUrlDTO.builder()
            .method("PUT")
            .url("https://s3.example.com/uploads/" + uploadId)
            .headers(signedHeaders)
            .build();

    mockReservePending(uploadId, presignedPut);

    StorageUploadDTO completed =
        StorageUploadDTO.builder()
            .id(uploadId.toString())
            .state(StorageUploadState.READY)
            .blobId(blobId.toString())
            .build();
    when(storageUploadService.complete(uploadId)).thenReturn(completed);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);
    transport.setPutStatus(200);

    ResourceRef ref = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png");

    assertEquals(ResourceRef.blobUploadUri(uploadId), ref.uri());
    assertEquals(1, transport.getPutCallCount());
    assertEquals(
        URI.create("https://s3.example.com/uploads/" + uploadId), transport.getLastPutUri());

    // 验证 headers：包含签名头，已过滤受限头
    Map<String, String> passedHeaders = transport.getLastPutHeaders();
    assertEquals("checksum-abc", passedHeaders.get("x-amz-checksum-sha256"));
    assertEquals("image/png", passedHeaders.get("content-type"));
    assertFalse(passedHeaders.containsKey("Host"));
    assertFalse(passedHeaders.containsKey("host"));
    assertFalse(passedHeaders.containsKey("content-length"));
    assertFalse(passedHeaders.containsKey("Connection"));

    // 验证上传内容与下载内容逐字节一致
    assertArrayEquals(pngData, transport.getLastPutBytes());

    verify(storageUploadService).complete(uploadId);
    assertTempDirClean();
  }

  /** presignedPut 的 HTTP method 不是 PUT 时，必须拒绝并触发 delete 清理，清理临时文件。 */
  @Test
  void stageRemoteMediaPutRejectsNonPutMethod() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);
    UUID uploadId = UUID.randomUUID();

    StoragePresignedUrlDTO presignedPut =
        StoragePresignedUrlDTO.builder()
            .method("POST")
            .url("https://s3.example.com/uploads/" + uploadId)
            .build();
    mockReservePending(uploadId, presignedPut);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("unexpected method"));
    verify(storageUploadService).delete(uploadId);
    assertTempDirClean();
  }

  /** presignedPut 的 URL 携带 userinfo 时，必须拒绝并触发 delete 清理。 */
  @Test
  void stageRemoteMediaPutRejectsUserInfoInTargetUrl() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);
    UUID uploadId = UUID.randomUUID();

    StoragePresignedUrlDTO presignedPut =
        StoragePresignedUrlDTO.builder()
            .method("PUT")
            .url("https://user:pass@s3.example.com/uploads/" + uploadId)
            .build();
    mockReservePending(uploadId, presignedPut);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("must not carry userinfo"));
    verify(storageUploadService).delete(uploadId);
    assertTempDirClean();
  }

  /** PUT 直传返回 4xx/5xx 时，必须失败且触发 delete 清理已预约的上传。 */
  @ParameterizedTest
  @ValueSource(ints = {400, 403, 500, 503})
  void stageRemoteMediaPutFailsTriggersDelete(int putStatus) throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);
    UUID uploadId = UUID.randomUUID();

    StoragePresignedUrlDTO presignedPut =
        StoragePresignedUrlDTO.builder()
            .method("PUT")
            .url("https://s3.example.com/uploads/" + uploadId)
            .build();
    mockReservePending(uploadId, presignedPut);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);
    transport.setPutStatus(putStatus);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("responded with status " + putStatus));
    verify(storageUploadService).delete(uploadId);
    assertTempDirClean();
  }

  /** complete 抛异常、返回 null 或非 READY 状态时，必须失败且触发 delete 清理。 */
  @Test
  void stageRemoteMediaCompleteFailsTriggersDelete() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);
    UUID uploadId = UUID.randomUUID();

    StoragePresignedUrlDTO presignedPut =
        StoragePresignedUrlDTO.builder()
            .method("PUT")
            .url("https://s3.example.com/uploads/" + uploadId)
            .build();
    mockReservePending(uploadId, presignedPut);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);
    transport.setPutStatus(200);

    // complete 返回 PENDING
    when(storageUploadService.complete(uploadId))
        .thenReturn(
            StorageUploadDTO.builder()
                .id(uploadId.toString())
                .state(StorageUploadState.PENDING)
                .build());
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    verify(storageUploadService).delete(uploadId);
    assertTempDirClean();

    // complete 抛出异常
    when(storageUploadService.complete(uploadId))
        .thenThrow(new RuntimeException("Checksum verify failed in storage"));
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTempDirClean();
  }

  /** reserve 阶段直接抛异常时，不调用 delete（因为尚无 uploadId），不调用 PUT 直传，且清理临时文件。 */
  @Test
  void stageRemoteMediaReserveThrowsDoesNotCallDeleteOrPut() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);

    when(storageUploadService.reserve(any()))
        .thenThrow(new RuntimeException("Storage unavailable"));
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("cannot reserve a staging upload"));
    verify(storageUploadService, never()).delete(any());
    assertEquals(0, transport.getPutCallCount());
    assertTempDirClean();
  }

  /** delete 自身抛出异常时，不得掩盖原本已确定的失败结果。 */
  @Test
  void stageRemoteMediaDeleteFailureDoesNotMaskOriginalException() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);
    UUID uploadId = UUID.randomUUID();

    StoragePresignedUrlDTO presignedPut =
        StoragePresignedUrlDTO.builder()
            .method("PUT")
            .url("https://s3.example.com/uploads/" + uploadId)
            .build();
    mockReservePending(uploadId, presignedPut);

    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);
    transport.setPutStatus(500);

    doThrow(new RuntimeException("Failed to delete upload in storage"))
        .when(storageUploadService)
        .delete(uploadId);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "pic.png"));
    assertTrue(exception.getMessage().contains("responded with status 500"));
    assertTempDirClean();
  }

  // =========================================================================
  // 6. 文件名清洗与扩展名处理（stageRemoteMedia）
  // =========================================================================

  /** 文件名包含路径、控制字符或超长时必须被清洗，保留合法部分且不超过合理长度。 */
  @Test
  void stageRemoteMediaSanitizesComplexFilenames() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);

    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    mockReserveReady(uploadId, blobId);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    // 路径剥离
    ResourceRef pathRef =
        stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "/var/log/upload/avatar.png");
    assertEquals("avatar.png", pathRef.name());

    // Windows 路径剥离
    ResourceRef winRef =
        stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "C:\\Users\\test\\my_photo.png");
    assertEquals("my_photo.png", winRef.name());

    // 控制字符替换为下划线
    ResourceRef controlRef = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "photo\r\n\tname.png");
    assertEquals("photo___name.png", controlRef.name());

    // 空/空白名称回退为 media.png
    ResourceRef blankRef = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "   ");
    assertEquals("media.png", blankRef.name());

    ResourceRef nullRef = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, null);
    assertEquals("media.png", nullRef.name());

    // 超长文件名截断（baseName 截断到 120，随后按需追加扩展名）
    String longName = "a".repeat(200);
    ResourceRef longRef = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, longName);
    assertTrue(longRef.name().length() <= 130);
    assertTrue(longRef.name().startsWith("a".repeat(120)));
    assertEquals("a".repeat(120) + ".png", longRef.name());
    assertFalse(longRef.name().contains("/"));
    assertTempDirClean();
  }

  /** 无扩展名时按嗅探类型补齐扩展名；已有扩展名时保留原有扩展名。 */
  @Test
  void stageRemoteMediaAppendsExtensionOnlyWhenMissing() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(100);

    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    mockReserveReady(uploadId, blobId);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    // 无扩展名，补 .png
    ResourceRef noExtRef = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "myavatar");
    assertEquals("myavatar.png", noExtRef.name());

    // 已有扩展名，保留原有扩展名（即使与 MIME 略有差异）
    ResourceRef hasExtRef = stageRemoteMedia(uri, PluginMediaFamily.IMAGE, "myavatar.jpeg");
    assertEquals("myavatar.jpeg", hasExtRef.name());

    assertTempDirClean();
  }

  // =========================================================================
  // 7. 每请求权威授权围栏（beforeRequest）
  // =========================================================================

  /** 下载 GET 出网前必须执行宿主授权检查；检查失败时不得发出下载请求，且不留临时文件。 */
  @Test
  void stageRemoteMediaChecksBeforeRequestBeforeGetAndSkipsNetworkWhenRevoked() throws IOException {
    AtomicInteger checks = new AtomicInteger();
    PluginCredentialUnavailableException revoked =
        new PluginCredentialUnavailableException(
            PluginCredentialUnavailableReason.REAUTH_REQUIRED, "credential revoked");

    PluginCredentialUnavailableException thrown =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () ->
                gateway.stageRemoteMedia(
                    URI.create("https://media.example.com/pic.png"),
                    PluginMediaFamily.IMAGE,
                    "pic.png",
                    () -> {
                      checks.incrementAndGet();
                      throw revoked;
                    }));

    assertSame(revoked, thrown, "beforeRequest 失败必须保持 PluginCredentialUnavailableException 类型");
    assertEquals(1, checks.get(), "下载 GET 前必须恰好执行一次授权检查");
    assertEquals(0, transport.getGetCallCount(), "授权失败不得发出下载请求");
    assertTempDirClean();
  }

  /** 下载完成后授权被撤销：PUT 前重新检查失败，不发上传请求，并清理已 reserve 上传与临时文件。 */
  @Test
  void stageRemoteMediaRevokedAfterGetSkipsPutAndCleansReservationAndTemp() throws IOException {
    URI uri = URI.create("https://media.example.com/pic.png");
    byte[] pngData = createPngData(300);
    UUID uploadId = UUID.randomUUID();
    StoragePresignedUrlDTO presignedPut =
        StoragePresignedUrlDTO.builder()
            .method("PUT")
            .url("https://s3.example.com/uploads/" + uploadId)
            .build();
    mockReservePending(uploadId, presignedPut);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    AtomicInteger checks = new AtomicInteger();
    PluginCredentialUnavailableException revoked =
        new PluginCredentialUnavailableException(
            PluginCredentialUnavailableReason.REAUTH_REQUIRED, "credential revoked");

    PluginCredentialUnavailableException thrown =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () ->
                gateway.stageRemoteMedia(
                    uri,
                    PluginMediaFamily.IMAGE,
                    "pic.png",
                    () -> {
                      // 第一次（GET 前）放行，第二次（PUT 前）判定授权已撤销。
                      if (checks.incrementAndGet() == 2) {
                        throw revoked;
                      }
                    }));

    assertSame(revoked, thrown, "PUT 前授权失败必须保持原类型而不被包装成资源错误");
    assertEquals(2, checks.get(), "GET 与 PUT 的紧前都必须检查授权");
    assertEquals(1, transport.getGetCallCount());
    assertEquals(0, transport.getPutCallCount(), "授权失败不得发送预签名上传请求");
    verify(storageUploadService).delete(uploadId);
    assertTempDirClean();
  }

  /** reserve 直接 READY（无需 PUT）时只在真正出网的那一步检查授权：GET 前一次，不产生多余的 PUT 检查。 */
  @Test
  void stageRemoteMediaChecksBeforeRequestOnlyForIssuedNetworkCalls() throws IOException {
    byte[] pngData = createPngData(200);
    UUID uploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    mockReserveReady(uploadId, blobId);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    AtomicInteger checks = new AtomicInteger();
    ResourceRef ref =
        gateway.stageRemoteMedia(
            URI.create("https://media.example.com/pic.png"),
            PluginMediaFamily.IMAGE,
            "pic.png",
            checks::incrementAndGet);

    assertEquals(ResourceRef.blobUploadUri(uploadId), ref.uri());
    assertEquals(1, checks.get(), "只有实际出网的 GET 需要执行授权检查");
    assertEquals(0, transport.getPutCallCount());
    assertTempDirClean();
  }

  // =========================================================================
  // 8. 资源释放（close）
  // =========================================================================

  /** 关闭网关必须把关闭动作交给下游传输（Spring 组合根按 inferred destroy method 调用），且重复关闭也安全。 */
  @Test
  void closeReleasesTheTransportIdempotently() throws IOException {
    gateway.close();
    gateway.close();

    assertEquals(2, transport.getCloseCallCount());
  }

  // =========================================================================
  // 9. 生产装配与边界/错误路径
  // =========================================================================

  /** 生产装配路径：公共构造器自行装配真实传输与配置的临时目录，且关闭幂等。 */
  @Test
  void publicConstructorWiresRealTransportAndClosesIdempotently() throws IOException {
    StoragePluginResourceGateway publicGateway =
        new StoragePluginResourceGateway(
            harnessStore,
            sessionBlobRefManager,
            storageBlobManager,
            storageUploadService,
            properties);
    publicGateway.close();
    publicGateway.close();
  }

  /** 签发会话资源下载地址时的非预期底层失败必须收敛为资源不可用，而不是泄露原始异常。 */
  @Test
  void resolveSessionResourceFailsWhenSigningBackendThrows() {
    UUID blobId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    mockThreadSession(threadId, sessionId);
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);
    when(storageBlobManager.presignOriginalUrl(blobId))
        .thenThrow(new IllegalStateException("signing backend unavailable"));

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> gateway.resolveSessionResource(threadId, "kkstudio:/resources/" + blobId));
    assertTrue(exception.getMessage().contains("cannot sign this session resource"));
  }

  /** 缺少 content-length 响应头时按无长度处理，仍以嗅探结果完成暂存。 */
  @Test
  void stageRemoteMediaStagesWithoutContentLengthHeader() throws IOException {
    byte[] pngData = createPngData(128);
    mockReserveReady(UUID.randomUUID(), UUID.randomUUID());
    transport.setGetResponse(200, Map.of(), pngData);

    ResourceRef ref =
        stageRemoteMedia(
            URI.create("https://media.example.com/pic.png"), PluginMediaFamily.IMAGE, "pic.png");

    assertEquals((long) pngData.length, ref.size());
    assertEquals("image/png", ref.mediaType());
    assertTempDirClean();
  }

  /** 预签名 PUT 目标缺失（空白 URL）时必须在出网前拒绝并清理已 reserve 上传。 */
  @Test
  void stageRemoteMediaPutRejectsMissingPresignedTarget() throws IOException {
    byte[] pngData = createPngData(96);
    UUID uploadId = UUID.randomUUID();
    mockReservePending(uploadId, StoragePresignedUrlDTO.builder().method("PUT").url("   ").build());
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () ->
                stageRemoteMedia(
                    URI.create("https://media.example.com/pic.png"),
                    PluginMediaFamily.IMAGE,
                    "pic.png"));

    assertTrue(exception.getMessage().contains("no presigned target"));
    assertEquals(0, transport.getPutCallCount());
    verify(storageUploadService).delete(uploadId);
    assertTempDirClean();
  }

  /** 预签名 PUT 目标 URL 无法解析时必须在出网前拒绝并清理。 */
  @Test
  void stageRemoteMediaPutRejectsMalformedPresignedUrl() throws IOException {
    byte[] pngData = createPngData(96);
    UUID uploadId = UUID.randomUUID();
    mockReservePending(
        uploadId,
        StoragePresignedUrlDTO.builder().method("PUT").url("https://s3.example.com/a b").build());
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () ->
                stageRemoteMedia(
                    URI.create("https://media.example.com/pic.png"),
                    PluginMediaFamily.IMAGE,
                    "pic.png"));

    assertTrue(exception.getMessage().contains("url is malformed"));
    assertEquals(0, transport.getPutCallCount());
    verify(storageUploadService).delete(uploadId);
    assertTempDirClean();
  }

  /** reserve 返回的上传 id 缺失或非规范 UUID 时确定性拒绝，且不进行 PUT。 */
  @Test
  void stageRemoteMediaRejectsUploadWithoutIdOrWithNonUuidId() throws IOException {
    byte[] pngData = createPngData(96);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), pngData);

    when(storageUploadService.reserve(any()))
        .thenReturn(StorageUploadDTO.builder().state(StorageUploadState.PENDING).build());
    PluginResourceUnavailableException noId =
        assertThrows(
            PluginResourceUnavailableException.class,
            () ->
                stageRemoteMedia(
                    URI.create("https://media.example.com/pic.png"),
                    PluginMediaFamily.IMAGE,
                    "pic.png"));
    assertTrue(noId.getMessage().contains("has no id"));

    when(storageUploadService.reserve(any()))
        .thenReturn(
            StorageUploadDTO.builder().id("not-a-uuid").state(StorageUploadState.PENDING).build());
    PluginResourceUnavailableException badId =
        assertThrows(
            PluginResourceUnavailableException.class,
            () ->
                stageRemoteMedia(
                    URI.create("https://media.example.com/pic.png"),
                    PluginMediaFamily.IMAGE,
                    "pic.png"));
    assertTrue(badId.getMessage().contains("not a canonical uuid"));

    assertEquals(0, transport.getPutCallCount());
    assertTempDirClean();
  }

  /** 临时目录不可创建（被同名普通文件占用）时确定性失败，且不发出上传。 */
  @Test
  void stageRemoteMediaFailsWhenTempDirectoryIsNotCreatable() throws IOException {
    Path blocked = tempDir.resolve("blocked-temp-path");
    Files.writeString(blocked, "occupied");
    PluginProperties broken = new PluginProperties();
    broken.getResource().setTempDirectory(blocked.toString());
    broken.getResource().setMaxBytes(1024 * 1024);
    broken.getResource().setRequestTimeout(Duration.ofSeconds(10));
    broken.getResource().setConnectTimeout(Duration.ofSeconds(2));
    broken.getResource().setUploadTimeout(Duration.ofMinutes(1));
    StoragePluginResourceGateway blockedGateway =
        new StoragePluginResourceGateway(
            harnessStore,
            sessionBlobRefManager,
            storageBlobManager,
            storageUploadService,
            broken,
            transport);
    byte[] pngData = createPngData(96);
    TrackedInputStream body = new TrackedInputStream(pngData);
    transport.setGetResponse(
        200, Map.of("content-length", List.of(String.valueOf(pngData.length))), body);

    PluginResourceUnavailableException exception =
        assertThrows(
            PluginResourceUnavailableException.class,
            () ->
                blockedGateway.stageRemoteMedia(
                    URI.create("https://media.example.com/pic.png"),
                    PluginMediaFamily.IMAGE,
                    "pic.png",
                    () -> {}));

    assertTrue(exception.getMessage().contains("cannot create a staging temp file"));
    assertTrue(body.isClosed(), "创建暂存文件失败后仍须释放已打开的下载响应体");
    assertEquals(0, transport.getPutCallCount());
  }

  /** 未配置临时目录时回退到系统临时目录，构造仍然成功。 */
  @Test
  void gatewayFallsBackToSystemTempDirectoryWhenUnset() {
    PluginProperties defaults = new PluginProperties();
    defaults.getResource().setTempDirectory("   ");
    StoragePluginResourceGateway defaultGateway =
        new StoragePluginResourceGateway(
            harnessStore,
            sessionBlobRefManager,
            storageBlobManager,
            storageUploadService,
            defaults,
            transport);

    assertNotNull(defaultGateway);
  }

  /** 无扩展名时按嗅探出的 subtype 派生扩展名（video/mp4 → mp4、text/plain → txt）。 */
  @Test
  void stageRemoteMediaDerivesExtensionFromMediaSubtype() throws IOException {
    mockReserveReady(UUID.randomUUID(), UUID.randomUUID());
    byte[] payload = new byte[64];

    transport.setGetResponse(200, Map.of("content-type", List.of("video/mp4")), payload);
    assertEquals(
        "clip.mp4",
        stageRemoteMedia(
                URI.create("https://media.example.com/clip"), PluginMediaFamily.VIDEO, "clip")
            .name());

    transport.setGetResponse(200, Map.of("content-type", List.of("text/plain")), payload);
    assertEquals(
        "notes.txt",
        stageRemoteMedia(
                URI.create("https://media.example.com/notes"), PluginMediaFamily.DOCUMENT, "notes")
            .name());
  }

  // =========================================================================
  // 测试辅助方法与替身类
  // =========================================================================

  /** 3 参便捷入口：宿主授权检查恒为通过（{@code beforeRequest} 为空操作）；每请求围栏语义由第 7 组用例单独覆盖。 */
  private ResourceRef stageRemoteMedia(URI remoteUri, PluginMediaFamily family, String name) {
    return gateway.stageRemoteMedia(remoteUri, family, name, () -> {});
  }

  private void mockThreadSession(UUID threadId, UUID sessionId) {
    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn(sessionId);
    when(harnessStore.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, Object> callback = invocation.getArgument(0);
              HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
              ThreadStore threadStore = mock(ThreadStore.class);
              when(tx.threads()).thenReturn(threadStore);
              when(threadStore.find(threadId)).thenReturn(Optional.of(thread));
              return callback.apply(tx);
            });
  }

  private void mockThreadNotFound(UUID threadId) {
    when(harnessStore.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, Object> callback = invocation.getArgument(0);
              HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
              ThreadStore threadStore = mock(ThreadStore.class);
              when(tx.threads()).thenReturn(threadStore);
              when(threadStore.find(threadId)).thenReturn(Optional.empty());
              return callback.apply(tx);
            });
  }

  private void mockReserveReady(UUID uploadId, UUID blobId) {
    StorageUploadDTO upload =
        StorageUploadDTO.builder()
            .id(uploadId.toString())
            .state(StorageUploadState.READY)
            .blobId(blobId.toString())
            .build();
    when(storageUploadService.reserve(any())).thenReturn(upload);
  }

  private void mockReservePending(UUID uploadId, StoragePresignedUrlDTO presignedPut) {
    StorageUploadDTO upload =
        StorageUploadDTO.builder()
            .id(uploadId.toString())
            .state(StorageUploadState.PENDING)
            .presignedPut(presignedPut)
            .build();
    when(storageUploadService.reserve(any())).thenReturn(upload);
  }

  private void assertTempDirClean() throws IOException {
    try (Stream<Path> stream = Files.list(tempDir)) {
      List<Path> files = stream.toList();
      assertTrue(
          files.isEmpty(), () -> "Temporary directory must be clean but contained: " + files);
    }
  }

  private static byte[] createPngData(int totalLength) {
    byte[] data = new byte[Math.max(totalLength, 8)];
    byte[] header = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(header, 0, data, 0, header.length);
    for (int i = header.length; i < data.length; i++) {
      data[i] = (byte) (i & 0xFF);
    }
    return data;
  }

  private static byte[] createWavData(int totalLength) {
    byte[] data = new byte[Math.max(totalLength, 12)];
    byte[] riff = "RIFF".getBytes();
    byte[] wave = "WAVE".getBytes();
    System.arraycopy(riff, 0, data, 0, 4);
    System.arraycopy(wave, 0, data, 8, 4);
    for (int i = 12; i < data.length; i++) {
      data[i] = (byte) (i & 0xFF);
    }
    return data;
  }

  private static String computeSha256(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static class FakeRemoteMediaTransport implements RemoteMediaTransport {
    private int getCallCount = 0;
    private final List<URI> getUris = new ArrayList<>();
    private int responseStatus = 200;
    private Map<String, List<String>> responseHeaders = Map.of();
    private byte[] responseBody = new byte[0];
    private InputStream customStream;

    private int putCallCount = 0;
    private URI lastPutUri;
    private Map<String, String> lastPutHeaders;
    private byte[] lastPutBytes;
    private int putStatus = 200;
    private int closeCallCount = 0;

    void setGetResponse(int status, Map<String, List<String>> headers, byte[] body) {
      this.responseStatus = status;
      this.responseHeaders = headers != null ? Map.copyOf(headers) : Map.of();
      this.responseBody = body != null ? body.clone() : new byte[0];
      this.customStream = null;
    }

    void setGetResponse(int status, Map<String, List<String>> headers, InputStream bodyStream) {
      this.responseStatus = status;
      this.responseHeaders = headers != null ? Map.copyOf(headers) : Map.of();
      this.responseBody = null;
      this.customStream = bodyStream;
    }

    void setPutStatus(int status) {
      this.putStatus = status;
    }

    int getGetCallCount() {
      return getCallCount;
    }

    int getPutCallCount() {
      return putCallCount;
    }

    int getCloseCallCount() {
      return closeCallCount;
    }

    URI getLastPutUri() {
      return lastPutUri;
    }

    Map<String, String> getLastPutHeaders() {
      return lastPutHeaders;
    }

    byte[] getLastPutBytes() {
      return lastPutBytes;
    }

    @Override
    public MediaResponse get(URI uri, Duration timeout) {
      getCallCount++;
      getUris.add(uri);
      InputStream stream =
          customStream != null
              ? customStream
              : new ByteArrayInputStream(responseBody != null ? responseBody : new byte[0]);
      return new MediaResponse(responseStatus, responseHeaders, stream);
    }

    @Override
    public int put(URI uri, Map<String, String> headers, Path file, Duration timeout) {
      putCallCount++;
      this.lastPutUri = uri;
      this.lastPutHeaders = headers != null ? Map.copyOf(headers) : Map.of();
      try {
        this.lastPutBytes = Files.readAllBytes(file);
      } catch (IOException e) {
        throw new PluginResourceUnavailableException("Cannot read put file", e);
      }
      return putStatus;
    }

    @Override
    public void close() {
      closeCallCount++;
    }
  }

  private static class TrackedInputStream extends ByteArrayInputStream {
    private boolean readInvoked = false;
    private boolean closed = false;

    TrackedInputStream(byte[] buf) {
      super(buf);
    }

    boolean isReadInvoked() {
      return readInvoked;
    }

    boolean isClosed() {
      return closed;
    }

    @Override
    public int read() {
      readInvoked = true;
      return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) {
      readInvoked = true;
      return super.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
