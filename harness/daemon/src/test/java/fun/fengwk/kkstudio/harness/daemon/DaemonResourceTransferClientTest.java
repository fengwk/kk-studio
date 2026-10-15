package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceUploader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 意图：调用作用域资源直传的每条可测路径都收敛为确定结论——票据按 {@code (invocationId, transferId)} 关联、READY 才携带权威元数据、 非 2xx PUT
 * 只做权威判定且绝不重复上传字节、失败说明绝不泄露预签名 URL/状态正文、等待被中断立即失败且不吞中断， 以及连接不可用与调用预算耗尽都确定失败。
 */
class DaemonResourceTransferClientTest {

  private static final String INVOCATION_ID = "call-transfer";
  private static final long AWAIT_SECONDS = 10L;

  private final DaemonResourceTransferCodec codec = new DaemonResourceTransferCodec();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final BlockingQueue<Sent> frames = new LinkedBlockingQueue<>();
  private final AtomicBoolean senderAccepts = new AtomicBoolean(true);

  @AfterEach
  void shutdownWorkers() {
    workers.shutdownNow();
  }

  /** 完整数据面：REQUEST → PENDING 票据 → 精确签名 PUT → COMMIT → READY → 权威元数据引用。 */
  @Test
  void uploadsBytesOnceAndReturnsTheReadyReference() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
      client.onConnectionReady();
      byte[] data = {(byte) 0xCA, (byte) 0xFE};
      Future<ResourceRef> uploaded =
          workers.submit(
              () ->
                  client
                      .uploaderFor(activeDeadline())
                      .upload(INVOCATION_ID, "application/octet-stream", "blob.bin", data));

      DaemonResourceTransferCodec.UploadRequest request =
          codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
      storage.expectUpload(request.sha256());
      UUID uploadId = UUID.randomUUID();
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.pending(
                  request.transferId(),
                  uploadId,
                  new DaemonPresignedPut("PUT", storage.putUrl(), Map.of("If-None-Match", "*")))));

      Sent commit = awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_COMMIT);
      DaemonResourceTransferCodec.UploadCommit committed = codec.decodeCommit(commit.payloadJson());
      assertEquals(request.transferId(), committed.transferId());
      assertEquals(uploadId, committed.uploadId());
      assertEquals(1, storage.uploadCount(), "the exact signed PUT must be issued exactly once");
      assertEquals("PUT", storage.lastMethod());
      assertArrayEquals(data, storage.lastBody());

      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.ready(request.transferId(), uploadId)));

      ResourceRef reference = uploaded.get(AWAIT_SECONDS, TimeUnit.SECONDS);
      assertEquals(uploadId, reference.blobUploadId());
      assertEquals("application/octet-stream", reference.mediaType());
      assertEquals(Long.valueOf(data.length), reference.size());
      assertEquals(request.sha256(), reference.sha256());
    }
  }

  /** 票据只按 {@code (invocationId, transferId)} 关联：已结束 transfer 的迟到票据绝不能完成另一次等待。 */
  @Test
  void lateTicketForAFinishedTransferNeverCompletesAnotherUpload() throws Exception {
    DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
    client.onConnectionReady();

    Future<ResourceRef> first =
        workers.submit(
            () ->
                client
                    .uploaderFor(activeDeadline())
                    .upload(INVOCATION_ID, "text/plain", null, new byte[] {1}));
    DaemonResourceTransferCodec.UploadRequest firstRequest =
        codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
    UUID firstUploadId = UUID.randomUUID();
    client.onTicket(
        INVOCATION_ID,
        codec.encodeTicket(
            DaemonResourceTransferCodec.UploadTicket.ready(
                firstRequest.transferId(), firstUploadId)));
    assertEquals(firstUploadId, first.get(AWAIT_SECONDS, TimeUnit.SECONDS).blobUploadId());

    Future<ResourceRef> second =
        workers.submit(
            () ->
                client
                    .uploaderFor(activeDeadline())
                    .upload(INVOCATION_ID, "text/plain", null, new byte[] {2}));
    DaemonResourceTransferCodec.UploadRequest secondRequest =
        codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
    assertNotEquals(firstRequest.transferId(), secondRequest.transferId());
    // 第一个 transfer 的迟到 READY 到达时必须被丢弃，第二个上传只能由自己的票据完成。
    client.onTicket(
        INVOCATION_ID,
        codec.encodeTicket(
            DaemonResourceTransferCodec.UploadTicket.ready(
                firstRequest.transferId(), UUID.randomUUID())));
    UUID secondUploadId = UUID.randomUUID();
    client.onTicket(
        INVOCATION_ID,
        codec.encodeTicket(
            DaemonResourceTransferCodec.UploadTicket.ready(
                secondRequest.transferId(), secondUploadId)));

    assertEquals(secondUploadId, second.get(AWAIT_SECONDS, TimeUnit.SECONDS).blobUploadId());
  }

  /** 对象存储以非 2xx 拒绝 PUT：字节不重复上传、先做权威提交判定，失败说明只有有界文案，绝无预签名 URL 或状态正文。 */
  @Test
  void rejectedPutIsAnAuthoritativeCommitWithoutReuploadingOrLeaking() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      // 端点声明一个不可能的摘要，因此以 412 拒绝每次 PUT。
      storage.expectUpload("b".repeat(64));
      DaemonResourceTransferClient client = client(Duration.ofMillis(200));
      client.onConnectionReady();
      AtomicBoolean active = new AtomicBoolean(true);
      byte[] data = {7, 7, 7};
      Future<ResourceRef> uploaded =
          workers.submit(
              () ->
                  client
                      .uploaderFor(toggledDeadline(active))
                      .upload(INVOCATION_ID, "application/octet-stream", "x.bin", data));

      DaemonResourceTransferCodec.UploadRequest request =
          codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.pending(
                  request.transferId(),
                  UUID.randomUUID(),
                  new DaemonPresignedPut("PUT", storage.putUrl(), Map.of("If-None-Match", "*")))));

      // PUT 被拒绝后必须先提交拿权威结论，而不是重复上传字节。
      awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_COMMIT);
      assertEquals(0, storage.uploadCount(), "a rejected PUT must never be counted as uploaded");
      assertEquals("PUT", storage.lastMethod());
      assertArrayEquals(data, storage.lastBody());
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.failed(request.transferId(), "rejected")));

      // 权威失败结论把传输复位到上传阶段，但仍重放完全相同的申请。
      DaemonResourceTransferCodec.UploadRequest retried =
          codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
      assertEquals(request.transferId(), retried.transferId());
      active.set(false);

      ExecutionException failure =
          assertThrows(
              ExecutionException.class, () -> uploaded.get(AWAIT_SECONDS, TimeUnit.SECONDS));
      IOException cause = assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(
          "resource upload failed", cause.getMessage(), "only the bounded text may escape");
      storage.assertNoSensitiveLeakIn(cause);
    }
  }

  /**
   * 预签名 PUT 绝不跟随 301/302/303/307/308：第二目的地零请求、零 header/body 外泄。3xx 按既有非成功存储响应处理，
   * 最终失败仍收敛为有界文案，且整个调用受 invocation deadline 约束。
   */
  @Test
  void neverFollowsStorageRedirectsForTheSignedPut() throws Exception {
    for (int status : new int[] {301, 302, 303, 307, 308}) {
      assertSignedPutRedirectIsRefused(status);
    }
  }

  private void assertSignedPutRedirectIsRefused(int status) throws Exception {
    String secret = "SECRETQUERY-" + status;
    AtomicInteger destinationHits = new AtomicInteger();
    AtomicReference<String> leakedHeader = new AtomicReference<>();
    AtomicReference<byte[]> leakedBody = new AtomicReference<>();

    HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    destination.createContext(
        "/object",
        exchange -> {
          destinationHits.incrementAndGet();
          leakedHeader.set(exchange.getRequestHeaders().getFirst("X-Amz-Signature"));
          leakedBody.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    destination.start();
    int destinationPort = destination.getAddress().getPort();

    AtomicInteger signedTargetHits = new AtomicInteger();
    HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    redirector.createContext(
        "/signed",
        exchange -> {
          signedTargetHits.incrementAndGet();
          // 先排空请求体，3xx 才能可靠返回；此即已签名目标，收到签名属预期。
          exchange.getRequestBody().readAllBytes();
          exchange
              .getResponseHeaders()
              .add("Location", "http://127.0.0.1:" + destinationPort + "/object?sig=" + secret);
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    redirector.start();
    String signedUrl =
        "http://127.0.0.1:"
            + redirector.getAddress().getPort()
            + "/signed?X-Amz-Signature="
            + secret;

    try {
      DaemonResourceTransferClient client = client(Duration.ofMillis(200));
      client.onConnectionReady();
      AtomicBoolean active = new AtomicBoolean(true);
      Future<ResourceRef> uploaded =
          workers.submit(
              () ->
                  client
                      .uploaderFor(toggledDeadline(active))
                      .upload(
                          INVOCATION_ID,
                          "application/octet-stream",
                          "secret.bin",
                          new byte[] {9, 9}));

      DaemonResourceTransferCodec.UploadRequest request =
          codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.pending(
                  request.transferId(),
                  UUID.randomUUID(),
                  new DaemonPresignedPut(
                      "PUT", signedUrl, Map.of("X-Amz-Signature", "signed-" + secret)))));

      // 3xx 被当作非成功存储响应：只做权威提交判定，绝不重发字节到重定向目的地。
      awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_COMMIT);
      assertEquals(
          1,
          signedTargetHits.get(),
          "the signed PUT target must be hit exactly once for " + status);
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.failed(request.transferId(), "rejected")));
      awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST);
      active.set(false);

      ExecutionException failure =
          assertThrows(
              ExecutionException.class, () -> uploaded.get(AWAIT_SECONDS, TimeUnit.SECONDS));
      IOException cause = assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(
          "resource upload failed", cause.getMessage(), "only the bounded text may escape");

      assertEquals(
          0, destinationHits.get(), "a redirect target must never receive a request: " + status);
      assertNull(leakedHeader.get(), "signed headers must not leak to the redirect target");
      assertNull(leakedBody.get(), "the signed body must not leak to the redirect target");
      String text = String.valueOf(cause);
      assertFalse(text.contains(secret), text);
      assertFalse(text.contains("X-Amz-Signature"), text);
    } finally {
      destination.stop(0);
      redirector.stop(0);
    }
  }

  /** 等待票据期间被中断：立即确定失败、不吞中断，也绝不继续等待。 */
  @Test
  void interruptedTicketWaitFailsImmediatelyAndKeepsTheInterruptFlag() throws Exception {
    DaemonResourceTransferClient client = client(Duration.ofSeconds(30));
    client.onConnectionReady();

    assertInterruptFailsTheUpload(client, true);
  }

  /** 在等待票据/连接恢复时中断工作线程：上传立即确定失败，中断标记必须被恢复。 */
  private void assertInterruptFailsTheUpload(
      DaemonResourceTransferClient client, boolean expectRequestFrame) throws Exception {
    AtomicReference<IOException> failure = new AtomicReference<>();
    AtomicBoolean interruptFlagRestored = new AtomicBoolean();
    AtomicBoolean finished = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              try {
                client
                    .uploaderFor(activeDeadline())
                    .upload(INVOCATION_ID, "text/plain", null, new byte[] {1});
              } catch (IOException error) {
                failure.set(error);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
              } finally {
                finished.set(true);
              }
            },
            "transfer-interrupt");
    worker.start();
    try {
      if (expectRequestFrame) {
        awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST);
      } else {
        // 等待轮询已开始后再中断，确保中断落在重连等待上。
        Thread.sleep(200L);
      }
      worker.interrupt();

      worker.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
      assertTrue(finished.get(), "the interrupted wait must not keep waiting");
      IOException error = failure.get();
      assertInstanceOf(IOException.class, error);
      assertEquals("resource upload failed", error.getMessage());
      assertTrue(interruptFlagRestored.get(), "the interrupt must be restored, never swallowed");
    } finally {
      worker.interrupt();
    }
  }

  /** 调用预算已耗尽：上传一边界都不开始。 */
  @Test
  void refusesUploadWhenTheInvocationDeadlineAlreadyPassed() {
    DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
    client.onConnectionReady();
    DaemonResourceUploader uploader = client.uploaderFor(limitedDeadline(0));

    IOException error =
        assertThrows(
            IOException.class,
            () -> uploader.upload(INVOCATION_ID, "text/plain", null, new byte[] {1}));

    assertEquals("resource upload failed", error.getMessage());
    assertTrue(
        frames.isEmpty(), "no control frame may be sent once the invocation cannot continue");
  }

  /** 连接始终不可用：等待必须以有界失败收敛，绝不永久等待。 */
  @Test
  void failsWhenTheConnectionNeverBecomesAvailable() {
    DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
    // 未 onConnectionReady：连接不可用。
    DaemonResourceUploader uploader = client.uploaderFor(limitedDeadline(1));

    IOException error =
        assertThrows(
            IOException.class,
            () -> uploader.upload(INVOCATION_ID, "text/plain", null, new byte[] {1}));

    assertEquals("resource upload failed", error.getMessage());
    assertTrue(frames.isEmpty());
  }

  /** 控制帧确定未发送（连接不可用）：上传以有界失败收敛。 */
  @Test
  void failsWhenTheControlFrameCannotBeSent() {
    senderAccepts.set(false);
    DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
    client.onConnectionReady();
    DaemonResourceUploader uploader = client.uploaderFor(limitedDeadline(2));

    IOException error =
        assertThrows(
            IOException.class,
            () -> uploader.upload(INVOCATION_ID, "text/plain", null, new byte[] {1}));

    assertEquals("resource upload failed", error.getMessage());
    assertTrue(frames.isEmpty(), "an unsent control frame must not be recorded");
  }

  /** 服务端回放尚未提交的申请票据：只重新提交，绝不重复上传字节。 */
  @Test
  void replayedPendingTicketOnlyRecommitsWithoutUploadingBytesAgain() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
      client.onConnectionReady();
      byte[] data = {5, 6, 7};
      Future<ResourceRef> uploaded =
          workers.submit(
              () ->
                  client
                      .uploaderFor(activeDeadline())
                      .upload(INVOCATION_ID, "application/octet-stream", "blob.bin", data));

      DaemonResourceTransferCodec.UploadRequest request =
          codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
      storage.expectUpload(request.sha256());
      UUID uploadId = UUID.randomUUID();
      DaemonPresignedPut presigned =
          new DaemonPresignedPut("PUT", storage.putUrl(), Map.of("If-None-Match", "*"));
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.pending(
                  request.transferId(), uploadId, presigned)));
      awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_COMMIT);
      assertEquals(1, storage.uploadCount());

      // 重放同一张 PENDING 票据（提交尚未被服务端接受）：必须只重新提交。
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.pending(
                  request.transferId(), uploadId, presigned)));
      awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_COMMIT);
      assertEquals(
          1, storage.uploadCount(), "a replayed PENDING ticket must never re-upload bytes");

      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.ready(request.transferId(), uploadId)));
      assertEquals(uploadId, uploaded.get(AWAIT_SECONDS, TimeUnit.SECONDS).blobUploadId());
      assertEquals(1, storage.uploadCount());
    }
  }

  /** 等待连接恢复期间被中断：立即确定失败、不吞中断。 */
  @Test
  void interruptedConnectionWaitFailsImmediatelyAndKeepsTheInterruptFlag() throws Exception {
    DaemonResourceTransferClient client = client(Duration.ofSeconds(30));
    // 未 onConnectionReady：上传进入重连等待轮询，只能被中断打断。

    assertInterruptFailsTheUpload(client, false);
  }

  /** 调用预算已在重试退避期间耗尽：上传确定失败，绝不在调用失效后继续重试。 */
  @Test
  void failsWhenTheInvocationBudgetExpiresDuringRetryBackoff() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
      client.onConnectionReady();
      // 该 deadline 只在恰好 6 次「仍可继续」检查内有效：申请、PUT 前、提交与退避开始各一次，
      // 之后在退避结束复查时收敛失败。
      DaemonResourceTransferClient.Deadline deadline = limitedDeadline(6);
      Future<ResourceRef> uploaded =
          workers.submit(
              () ->
                  client
                      .uploaderFor(deadline)
                      .upload(INVOCATION_ID, "application/octet-stream", "x.bin", new byte[] {8}));

      DaemonResourceTransferCodec.UploadRequest request =
          codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
      storage.expectUpload(request.sha256());
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.pending(
                  request.transferId(),
                  UUID.randomUUID(),
                  new DaemonPresignedPut("PUT", storage.putUrl(), Map.of("If-None-Match", "*")))));
      awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_COMMIT);
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.failed(request.transferId(), "rejected")));

      ExecutionException failure =
          assertThrows(
              ExecutionException.class, () -> uploaded.get(AWAIT_SECONDS, TimeUnit.SECONDS));
      assertEquals("resource upload failed", failure.getCause().getMessage());
    }
  }

  /** 票据到达时调用已不可继续：连 PUT 都不发起，直接收敛失败。 */
  @Test
  void refusesPutWhenTheInvocationDeadlineExpiresBeforeTheUpload() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
      client.onConnectionReady();
      AtomicBoolean active = new AtomicBoolean(true);
      Future<ResourceRef> uploaded =
          workers.submit(
              () ->
                  client
                      .uploaderFor(toggledDeadline(active))
                      .upload(INVOCATION_ID, "application/octet-stream", "y.bin", new byte[] {4}));

      DaemonResourceTransferCodec.UploadRequest request =
          codec.decodeRequest(awaitFrame(DaemonMessageType.RESOURCE_UPLOAD_REQUEST).payloadJson());
      storage.expectUpload(request.sha256());
      active.set(false);
      client.onTicket(
          INVOCATION_ID,
          codec.encodeTicket(
              DaemonResourceTransferCodec.UploadTicket.pending(
                  request.transferId(),
                  UUID.randomUUID(),
                  new DaemonPresignedPut("PUT", storage.putUrl(), Map.of("If-None-Match", "*")))));

      ExecutionException failure =
          assertThrows(
              ExecutionException.class, () -> uploaded.get(AWAIT_SECONDS, TimeUnit.SECONDS));
      assertEquals("resource upload failed", failure.getCause().getMessage());
      assertEquals(
          0, storage.uploadCount(), "no PUT may be issued once the invocation cannot continue");
    }
  }

  /** 非正票据等待上限是构造期错误，必须立即失败而不是退化成无界等待。 */
  @Test
  void rejectsANonPositiveTicketWait() {
    DaemonResourceTransferClient.TransferSender sender = (type, invocationId, payload) -> true;

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new DaemonResourceTransferClient(new OkHttpClient(), sender, Duration.ZERO));

    assertTrue(error.getMessage().contains("ticketWait must be positive"), error.getMessage());
  }

  /** 非法票据与不携带调用关联的票据只被丢弃：绝不破坏连接，也不唤醒任何等待。 */
  @Test
  void discardsMalformedAndUnattributedTickets() {
    DaemonResourceTransferClient client = client(Duration.ofSeconds(5));
    client.onConnectionReady();
    String validTicket =
        codec.encodeTicket(
            DaemonResourceTransferCodec.UploadTicket.ready(UUID.randomUUID(), UUID.randomUUID()));

    client.onTicket(INVOCATION_ID, "{not-json");
    client.onTicket(INVOCATION_ID, validTicket);
    client.onTicket("  ", validTicket);
    client.onTicket(null, validTicket);

    assertTrue(frames.isEmpty(), "discarded tickets must not emit any control frame");
  }

  private DaemonResourceTransferClient client(Duration ticketWait) {
    return new DaemonResourceTransferClient(
        new OkHttpClient(),
        (type, invocationId, payloadJson) -> {
          if (!senderAccepts.get()) {
            return false;
          }
          frames.add(new Sent(type, invocationId, payloadJson));
          return true;
        },
        ticketWait);
  }

  private Sent awaitFrame(DaemonMessageType expected) throws InterruptedException {
    Sent frame = frames.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(frame != null, "no " + expected + " frame within " + AWAIT_SECONDS + "s");
    assertEquals(expected, frame.type(), frame.toString());
    assertEquals(INVOCATION_ID, frame.invocationId());
    return frame;
  }

  private static DaemonResourceTransferClient.Deadline activeDeadline() {
    return new DaemonResourceTransferClient.Deadline() {
      @Override
      public boolean canContinue() {
        return true;
      }

      @Override
      public long remainingMillis() {
        return 60_000L;
      }
    };
  }

  private static DaemonResourceTransferClient.Deadline toggledDeadline(AtomicBoolean active) {
    return new DaemonResourceTransferClient.Deadline() {
      @Override
      public boolean canContinue() {
        return active.get();
      }

      @Override
      public long remainingMillis() {
        return 60_000L;
      }
    };
  }

  /** 前 {@code trueChecks} 次答复「仍可继续」，之后一律「不可继续」，用于确定地收敛重试循环。 */
  private static DaemonResourceTransferClient.Deadline limitedDeadline(int trueChecks) {
    AtomicInteger checks = new AtomicInteger();
    return new DaemonResourceTransferClient.Deadline() {
      @Override
      public boolean canContinue() {
        return checks.getAndIncrement() < trueChecks;
      }

      @Override
      public long remainingMillis() {
        return 60_000L;
      }
    };
  }

  private record Sent(DaemonMessageType type, String invocationId, String payloadJson) {}
}
