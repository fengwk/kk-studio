package fun.fengwk.kkstudio.harness.daemon;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceUploader;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Daemon 侧的资源上传客户端：把内存字节经调用作用域控制面直传全局对象存储。
 *
 * <p>交互固定为 {@code RESOURCE_UPLOAD_REQUEST} → 票据 → 预签名 PUT → {@code RESOURCE_UPLOAD_COMMIT} → 票据，直至
 * READY。传输以 {@code (invocationId, transferId)} 关联：同一调用内不同资源各自独立，同一 transferId 在整次上传期间恒定，因此重试与同实例重连
 * 只会重放同一传输，绝不产生第二个上传行；指向其它调用的票据绝不可能完成本次上传。
 *
 * <p><b>重试语义：</b>控制面往返、预签名 PUT 与提交都在调用方给定的 {@link Deadline} 内重试。重试没有人为次数上限：唯一的边界是被调用 invocation 的有效
 * deadline、取消与终结状态。连接失效时等待同实例重连（服务端保留调用与传输绑定，重放幂等）；等待被中断即立即确定失败，绝不吞掉中断后继续等待。
 *
 * <p><b>不泄漏票据秘密：</b>预签名 URL 与已签名 headers 只出现在实际的 PUT 请求里，绝不进入日志、异常消息或用户可见文本；失败一律收敛为有界、 无敏感信息的说明。
 */
final class DaemonResourceTransferClient {

  /** 单次控制面等待票据的超时上限。 */
  static final Duration DEFAULT_TICKET_WAIT = Duration.ofSeconds(30);

  /** 可重试失败之间的退避间隔。 */
  static final Duration RETRY_BACKOFF = Duration.ofMillis(200);

  /** 等待连接恢复时的轮询粒度：周期性复查 deadline 与调用是否仍然有效。 */
  private static final long CONNECTION_POLL_MILLIS = 200L;

  private static final String FAILURE_MESSAGE = "resource upload failed";

  private static final String REJECTED_PREFIX = "resource upload was rejected by storage";

  private final OkHttpClient httpClient;
  private final DaemonResourceTransferCodec codec = new DaemonResourceTransferCodec();
  private final ConcurrentHashMap<
          TransferKey, CompletableFuture<DaemonResourceTransferCodec.UploadTicket>>
      pendingTickets = new ConcurrentHashMap<>();
  private final TransferSender sender;
  private final Duration ticketWait;
  private final Object connectionMonitor = new Object();

  /** 当前是否存在可递交控制帧的连接：重连 READY 前置为 false，连接失效时复位。 */
  private boolean connectionAvailable;

  /** 出站控制帧递交端口；返回 false 表示连接当前不可用（帧确定未发送）。 */
  interface TransferSender {

    boolean send(DaemonMessageType messageType, String invocationId, String payloadJson);
  }

  /**
   * 一次上传的等待边界。
   *
   * <p>由运行时按被调用的 invocation 提供，因此上传既不会在调用已取消/终结后继续等待，也不会超出该调用的有效超时。
   */
  interface Deadline {

    /** 调用是否仍可继续等待（未取消、未终结且未超出 deadline）。 */
    boolean canContinue();

    /** 距离 deadline 的剩余毫秒数；已超出或不可继续时为 0。 */
    long remainingMillis();
  }

  /** 票据关联键：调用与传输共同唯一确定一次上传，缺一不可。 */
  private record TransferKey(String invocationId, UUID transferId) {

    private TransferKey {
      Objects.requireNonNull(invocationId, "invocationId");
      Objects.requireNonNull(transferId, "transferId");
    }
  }

  DaemonResourceTransferClient(OkHttpClient httpClient, TransferSender sender) {
    this(httpClient, sender, DEFAULT_TICKET_WAIT);
  }

  DaemonResourceTransferClient(
      OkHttpClient httpClient, TransferSender sender, Duration ticketWait) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.sender = Objects.requireNonNull(sender, "sender");
    this.ticketWait = requirePositive(ticketWait, "ticketWait");
  }

  /**
   * 接收一条服务端票据帧；{@code (invocationId, transferId)} 与当前等待的传输匹配时唤醒等待方，否则丢弃。
   *
   * <p>不匹配的票据（例如调用已终结后的迟到回执，或错误地把其它调用的 transferId 关联过来的票据）不是协议错误：调用方已经放弃等待或本就不属于该调用， 连接仍可继续服务其它调用。
   *
   * @param invocationId 票据 envelope 声明的调用 id（绝不能只用 payload 中的 transferId 关联）
   */
  void onTicket(String invocationId, String payloadJson) {
    DaemonResourceTransferCodec.UploadTicket ticket;
    try {
      ticket = codec.decodeTicket(payloadJson);
    } catch (RuntimeException ignored) {
      // 非法票据是服务端的协议问题；客户端不因单条控制帧内容破坏已建立的连接。
      return;
    }
    if (invocationId == null || invocationId.isBlank()) {
      return;
    }
    CompletableFuture<DaemonResourceTransferCodec.UploadTicket> future =
        pendingTickets.get(new TransferKey(invocationId, ticket.transferId()));
    if (future != null) {
      future.complete(ticket);
    }
  }

  /** 连接失效：唤醒等待方进入重连等待，恢复后由 {@link #onConnectionReady()} 放行。 */
  void onConnectionLost() {
    List<CompletableFuture<DaemonResourceTransferCodec.UploadTicket>> abandoned;
    synchronized (connectionMonitor) {
      connectionAvailable = false;
      abandoned = new ArrayList<>(pendingTickets.values());
      pendingTickets.clear();
      connectionMonitor.notifyAll();
    }
    abandoned.forEach(future -> future.completeExceptionally(new IOException("connection lost")));
  }

  /** 连接已 READY：放行等待重连的上传继续重放同一 transfer。 */
  void onConnectionReady() {
    synchronized (connectionMonitor) {
      connectionAvailable = true;
      connectionMonitor.notifyAll();
    }
  }

  /** 绑定调用 deadline 的上传端口：编码终态时按当前 invocation 的有效预算直传字节。 */
  DaemonResourceUploader uploaderFor(Deadline deadline) {
    Objects.requireNonNull(deadline, "deadline");
    return (invocationId, mediaType, name, bytes) ->
        upload(invocationId, mediaType, name, bytes, deadline);
  }

  private ResourceRef upload(
      String invocationId, String mediaType, String name, byte[] bytes, Deadline deadline)
      throws IOException {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(bytes, "bytes");
    DaemonResourceTransferCodec.UploadRequest request =
        new DaemonResourceTransferCodec.UploadRequest(
            UUID.randomUUID(), mediaType, name, bytes.length, sha256Hex(bytes));
    TransferKey key = new TransferKey(invocationId, request.transferId());
    // uploaded 表示「本 transfer 的字节可能已经在对象存储中」：只有提交给出的权威失败结论才能把它复位。
    boolean uploaded = false;
    UUID uploadId = null;
    try {
      while (true) {
        if (!canContinue(deadline)) {
          throw new IOException(FAILURE_MESSAGE);
        }
        awaitConnection(deadline);
        DaemonResourceTransferCodec.UploadTicket ticket;
        try {
          ticket =
              uploaded
                  ? control(
                      key,
                      DaemonMessageType.RESOURCE_UPLOAD_COMMIT,
                      codec.encodeCommit(
                          new DaemonResourceTransferCodec.UploadCommit(
                              request.transferId(), uploadId)),
                      deadline)
                  : control(
                      key,
                      DaemonMessageType.RESOURCE_UPLOAD_REQUEST,
                      codec.encodeRequest(request),
                      deadline);
        } catch (IOException unavailable) {
          // 连接失效或控制面等待超时：同一 (invocation, transfer) 重放完全相同的申请，服务端绑定不变。
          awaitRetry(deadline);
          continue;
        }
        switch (ticket.state()) {
          case READY -> {
            return readyRef(ticket.uploadId(), request);
          }
          case FAILED -> {
            // 提交失败说明对象确实不在存储中，回到上传阶段；申请失败则本来就没有上传过。
            uploaded = false;
            uploadId = null;
            awaitRetry(deadline);
          }
          case PENDING -> {
            if (uploaded) {
              // 服务端回放了尚未提交的申请票据：只重新提交，绝不重复上传字节。
              awaitRetry(deadline);
            } else {
              uploadId = ticket.uploadId();
              uploaded = true;
              try {
                putObject(ticket.presignedPut(), bytes, deadline);
              } catch (IOException rejected) {
                // 对象存储拒绝或不确定字节是否落盘：下一次提交做权威判定，失败时再回到上传阶段。
                awaitRetry(deadline);
              }
            }
          }
        }
      }
    } finally {
      pendingTickets.remove(key);
    }
  }

  /** 发起一次控制面往返；等待上限同时受 ticket 上限与调用剩余预算约束。 */
  private DaemonResourceTransferCodec.UploadTicket control(
      TransferKey key, DaemonMessageType messageType, String payloadJson, Deadline deadline)
      throws IOException {
    CompletableFuture<DaemonResourceTransferCodec.UploadTicket> future = new CompletableFuture<>();
    CompletableFuture<DaemonResourceTransferCodec.UploadTicket> previous;
    synchronized (connectionMonitor) {
      // 与 onConnectionLost 原子化：要么本 future 在断连快照中并被立即唤醒，要么已观察到连接不可用而不开始等待。
      if (!connectionAvailable) {
        throw new IOException("resource upload control is unavailable");
      }
      previous = pendingTickets.put(key, future);
    }
    if (previous != null) {
      previous.completeExceptionally(new IOException("resource transfer was superseded"));
    }
    try {
      if (!sender.send(messageType, key.invocationId(), payloadJson)) {
        throw new IOException("resource upload control is unavailable");
      }
      return future.get(boundedWait(deadline), TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException(FAILURE_MESSAGE, error);
    } catch (ExecutionException | TimeoutException error) {
      throw new IOException(FAILURE_MESSAGE, error);
    } finally {
      pendingTickets.remove(key, future);
    }
  }

  /** 单次控制面等待时长：不超过 ticket 等待上限，也不超过调用剩余预算。 */
  private long boundedWait(Deadline deadline) {
    return Math.max(1L, Math.min(ticketWait.toMillis(), Math.max(1L, deadline.remainingMillis())));
  }

  /** 等待连接可用；deadline 内未能恢复时直接返回（由调用方按同一 transfer 继续重试或收敛）。 */
  private void awaitConnection(Deadline deadline) throws IOException {
    synchronized (connectionMonitor) {
      while (!connectionAvailable && canContinue(deadline)) {
        try {
          connectionMonitor.wait(CONNECTION_POLL_MILLIS);
        } catch (InterruptedException error) {
          // 中断必须立即失败：绝不吞掉中断后继续等待。
          Thread.currentThread().interrupt();
          throw new IOException(FAILURE_MESSAGE, error);
        }
      }
      if (!connectionAvailable || !canContinue(deadline)) {
        throw new IOException(FAILURE_MESSAGE);
      }
    }
  }

  /**
   * 可重试失败之间的退避。
   *
   * <p>没有人为重试上限：唯一边界是被调用 invocation 的有效 deadline 与取消/终结状态；调用仍有效时持续重放同一 {@code (invocationId,
   * transferId)}，服务端绑定保证不会产生第二个上传行。
   */
  private void awaitRetry(Deadline deadline) throws IOException {
    if (!canContinue(deadline)) {
      throw new IOException(FAILURE_MESSAGE);
    }
    try {
      TimeUnit.MILLISECONDS.sleep(
          Math.min(RETRY_BACKOFF.toMillis(), Math.max(1L, deadline.remainingMillis())));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException(FAILURE_MESSAGE, error);
    }
    if (!canContinue(deadline)) {
      throw new IOException(FAILURE_MESSAGE);
    }
  }

  private boolean canContinue(Deadline deadline) {
    return deadline.canContinue();
  }

  /** 按票据的已签名事实原样发起请求；方法与 headers 必须与签名完全一致，整个 HTTP 调用不得越过 invocation deadline。 */
  private void putObject(DaemonPresignedPut presigned, byte[] bytes, Deadline deadline)
      throws IOException {
    if (!canContinue(deadline)) {
      throw new IOException(FAILURE_MESSAGE);
    }
    Request.Builder builder = new Request.Builder().url(presigned.url());
    for (Map.Entry<String, String> header : presigned.headers().entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }
    // 绝不自造 Content-Type：只发送票据声明的已签名 headers，并被签名者显式覆盖时按签名事实放行。
    builder.method(presigned.method(), RequestBody.create(bytes, null));
    OkHttpClient deadlineClient =
        httpClient
            .newBuilder()
            // OkHttp 4.x 的 duration 上限是 Integer.MAX_VALUE ms；更长的 invocation
            // 以单次约 24 天的调用窗口滚动重试，不能因 duration 溢出把合法上传误判为失败。
            .callTimeout(
                Math.min(Integer.MAX_VALUE, Math.max(1L, deadline.remainingMillis())),
                TimeUnit.MILLISECONDS)
            .build();
    try (Response response = deadlineClient.newCall(builder.build()).execute()) {
      if (!response.isSuccessful()) {
        // 只报告状态码：预签名 URL 与签名 headers 绝不进入错误文本。
        throw new IOException(REJECTED_PREFIX + ": " + response.code());
      }
    } catch (IOException error) {
      String message = error.getMessage();
      throw new IOException(
          message != null && message.startsWith(REJECTED_PREFIX) ? message : FAILURE_MESSAGE);
    }
  }

  private static ResourceRef readyRef(
      UUID uploadId, DaemonResourceTransferCodec.UploadRequest request) {
    return new ResourceRef(
        ResourceRef.blobUploadUri(uploadId),
        request.mediaType(),
        request.name(),
        request.size(),
        request.sha256());
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
