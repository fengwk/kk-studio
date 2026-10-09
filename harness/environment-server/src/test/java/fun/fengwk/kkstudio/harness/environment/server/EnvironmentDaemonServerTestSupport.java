package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 核心测试的内存基座：可控 fake channel / lease store，以及握手与帧构造辅助。
 *
 * <p>Fake 只表达核心真正依赖的窄端口语义（围栏返回值、递交结果、连接开关），不模拟 SQL 或 WebSocket；任何测试都不需要 Spring、JDBC 或产品 DTO
 * 即可驱动完整状态机。
 */
final class EnvironmentDaemonServerTestSupport {

  static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final EnvironmentId OTHER_ENVIRONMENT_ID =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  static final UUID CALL_ONE = new UUID(0L, 9101L);
  static final UUID CALL_TWO = new UUID(0L, 9102L);
  static final String TOKEN = "registration-token";
  static final String OTHER_TOKEN = "other-token";

  /** 同一 Daemon 进程在多次重连中复用的实例身份。 */
  static final String INSTANCE_ID = "33333333-3333-3333-3333-333333333333";

  /** 另一个 Daemon 进程的实例身份：出现即意味着旧进程已不可能再提供终态。 */
  static final String OTHER_INSTANCE_ID = "44444444-4444-4444-4444-444444444444";

  /** 测试 Daemon 上报的构建版本。 */
  static final String DAEMON_VERSION = "1.0.9";

  private static final DaemonCapabilitiesCodec CAPABILITIES_CODEC = new DaemonCapabilitiesCodec();
  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonCapabilityResultCodec RESULT_CODEC = new DaemonCapabilityResultCodec();

  static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          DAEMON_VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "Asia/Shanghai", "dev", "/home/dev", "note"));

  /** 测试用单条/聚合资源字节预算：与生产 16 MiB 业务上限一致。 */
  static final long MAX_RESOURCE_BYTES = 16L * 1024 * 1024;

  private EnvironmentDaemonServerTestSupport() {}

  static String readyPayload() {
    return CAPABILITIES_CODEC.encode(CAPABILITIES);
  }

  static String completedPayload(UUID invocationId, String text) {
    return RESULT_CODEC.encodeCompleted(
        EnvironmentCapabilityResult.text(invocationId.toString(), text), MAX_RESOURCE_BYTES, null);
  }

  static String partialPayload(UUID invocationId, String text) {
    return RESULT_CODEC.encodeProgress(
        EnvironmentCapabilityResult.text(invocationId.toString(), text));
  }

  /** 终态 COMPLETED payload：携带一个已就绪的 resource 上传引用（由 FakeTicketService 预先发放）。 */
  static String completedResourcePayload(UUID invocationId, ResourceRef uploaded, String preview) {
    return completedResourcePayloads(invocationId, List.of(uploaded));
  }

  /** 终态 COMPLETED payload：携带多个 resource 引用，用于重复/聚合校验。 */
  static String completedResourcePayloads(UUID invocationId, List<ResourceRef> uploads) {
    List<ResultContent> contents = new ArrayList<>();
    for (ResourceRef upload : uploads) {
      contents.add(new ResourceResultContent(upload));
    }
    return RESULT_CODEC.encodeCompleted(
        new EnvironmentCapabilityResult(invocationId.toString(), contents, false, "{}"),
        MAX_RESOURCE_BYTES,
        null);
  }

  static String helloPayload(String token) {
    return helloPayload(token, INSTANCE_ID);
  }

  static String helloPayload(String token, String daemonInstanceId) {
    return helloPayload(token, daemonInstanceId, EnvironmentCapabilityCatalog.version());
  }

  static String helloPayload(
      String token, String daemonInstanceId, String capabilityCatalogVersion) {
    return "{\"protocolVersion\":"
        + DaemonProtocol.VERSION
        + ",\"registrationToken\":\""
        + token
        + "\",\"capabilityCatalogVersion\":\""
        + capabilityCatalogVersion
        + "\",\"daemonVersion\":\""
        + DAEMON_VERSION
        + "\",\"daemonInstanceId\":\""
        + daemonInstanceId
        + "\"}";
  }

  static String encode(
      EnvironmentId scope, DaemonMessageType type, String invocationId, String payload) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(DaemonProtocol.VERSION, type, scope, invocationId, payload));
  }

  /** 一个通过 READY OS（LINUX）发送前校验的 fs.read 请求：携带绝对 path。 */
  static EnvironmentCapabilityExecutionRequest capabilityRequest(UUID callId) {
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
    return new EnvironmentCapabilityExecutionRequest(
        descriptor,
        new EnvironmentCapabilityCall(callId.toString(), "{\"path\":\"/srv/repo/README.md\"}"),
        Duration.ofSeconds(5));
  }

  /** 测试夹具：一个核心实例 + 可控租约存储 + 会话监听记录。 */
  static final class Fixture {

    final FakeLeaseStore leaseStore = new FakeLeaseStore();
    final FakeTicketService ticketService = new FakeTicketService();
    final List<EnvironmentId> readyNotifications = new ArrayList<>();
    final List<EnvironmentId> updateResultEnvironments = new ArrayList<>();
    final List<DaemonUpdateResult> updateResults = new ArrayList<>();
    final EnvironmentDaemonServer server;
    private boolean registrationDirectoryFails;

    Fixture() {
      this.server =
          new EnvironmentDaemonServer(
              leaseStore,
              token -> {
                if (registrationDirectoryFails) {
                  throw new IllegalStateException("directory unavailable");
                }
                if (TOKEN.equals(token)) {
                  return Optional.of(new DaemonRegistration(ENVIRONMENT_ID, "env-1"));
                }
                if (OTHER_TOKEN.equals(token)) {
                  return Optional.of(new DaemonRegistration(OTHER_ENVIRONMENT_ID, "env-2"));
                }
                return Optional.empty();
              },
              readyNotifications::add,
              (environmentId, result) -> {
                updateResultEnvironments.add(environmentId);
                updateResults.add(result);
              },
              ticketService,
              () -> new EnvironmentServerSettings(Duration.ofSeconds(60), 16L * 1024 * 1024));
    }

    void failRegistrationDirectory() {
      registrationDirectoryFails = true;
    }

    FakeChannel connectReady(String connectionId) {
      return connectReady(connectionId, INSTANCE_ID);
    }

    FakeChannel connectReady(String connectionId, String daemonInstanceId) {
      FakeChannel channel = new FakeChannel(connectionId);
      server.open(channel);
      receiveHello(channel, TOKEN, ENVIRONMENT_ID, daemonInstanceId);
      receiveReady(channel);
      return channel;
    }

    /** 用声明的 capability catalog 版本完成握手：用于验证目录不匹配仍可承载版本查询与受管更新。 */
    FakeChannel connectReadyWithCatalog(String connectionId, String capabilityCatalogVersion) {
      FakeChannel channel = new FakeChannel(connectionId);
      server.open(channel);
      server.receive(
          channel.connectionId(),
          encode(
              null,
              DaemonMessageType.HELLO,
              null,
              helloPayload(TOKEN, INSTANCE_ID, capabilityCatalogVersion)));
      channel.boundEnvironmentId = ENVIRONMENT_ID;
      receiveReady(channel);
      return channel;
    }

    /** 旧持有者为失效代际（例如租约过期）后，用新代际重新完成握手；同一实例身份表示同一 Daemon 进程重连。 */
    FakeChannel reconnectReady(String connectionId) {
      leaseStore.activeLeaseToken = false;
      return connectReady(connectionId);
    }

    void receiveHello(FakeChannel channel) {
      receiveHello(channel, TOKEN, ENVIRONMENT_ID);
    }

    void receiveHello(FakeChannel channel, String token, EnvironmentId environmentId) {
      receiveHello(channel, token, environmentId, INSTANCE_ID);
    }

    void receiveHello(
        FakeChannel channel, String token, EnvironmentId environmentId, String daemonInstanceId) {
      server.receive(
          channel.connectionId(),
          encode(null, DaemonMessageType.HELLO, null, helloPayload(token, daemonInstanceId)));
      channel.boundEnvironmentId = environmentId;
    }

    void receiveReady(FakeChannel channel) {
      receive(channel, DaemonMessageType.READY, null, readyPayload());
    }

    void receive(FakeChannel channel, DaemonMessageType type, String invocationId, String payload) {
      EnvironmentId scope =
          channel.boundEnvironmentId != null ? channel.boundEnvironmentId : ENVIRONMENT_ID;
      server.receive(channel.connectionId(), encode(scope, type, invocationId, payload));
    }
  }

  /** 可控租约存储：租约状态可随时整体切换，用于验证围栏语义；只表达核心依赖的围栏返回值。 */
  static final class FakeLeaseStore implements DaemonLeaseStore {

    boolean holdsReadyLease = true;
    boolean heartbeatResult = true;
    boolean activeLeaseToken = true;
    boolean acquired;
    boolean disconnected;
    boolean databaseUnavailable;
    boolean markReadyResult = true;
    boolean rejected;
    boolean retryLater;
    DaemonCapabilities readyCapabilities;

    @Override
    public LeaseBindResult tryAcquire(
        EnvironmentId environmentId, String registrationToken, Duration leaseDuration) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      if (rejected) {
        return new LeaseBindResult.Rejected("route rejected");
      }
      if (retryLater) {
        return new LeaseBindResult.RetryLater("route retry later");
      }
      if (!TOKEN.equals(registrationToken) && !OTHER_TOKEN.equals(registrationToken)) {
        return new LeaseBindResult.Rejected("invalid registration token");
      }
      acquired = true;
      return new LeaseBindResult.Acquired(UUID.randomUUID());
    }

    @Override
    public boolean markReady(
        EnvironmentId environmentId,
        UUID leaseToken,
        DaemonCapabilities capabilities,
        Duration leaseDuration) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      readyCapabilities = capabilities;
      return markReadyResult;
    }

    @Override
    public boolean heartbeat(EnvironmentId environmentId, UUID leaseToken, Duration leaseDuration) {
      return heartbeatResult;
    }

    @Override
    public boolean disconnect(
        EnvironmentId environmentId, UUID leaseToken, Duration graceDuration) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      disconnected = true;
      return true;
    }

    @Override
    public boolean holdsReadyLease(EnvironmentId environmentId, UUID leaseToken) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      return holdsReadyLease;
    }

    @Override
    public boolean hasActiveLeaseToken(EnvironmentId environmentId, UUID leaseToken) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      return activeLeaseToken;
    }
  }

  /** 记录终态事件的监听器；用于断言 exactly-once 语义。 */
  static final class RecordingListener implements EnvironmentCapabilityExecutionListener {

    final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    EnvironmentCapabilityResult completed;
    Throwable error;
    int errorCount;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      completed = result;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      errorCount++;
    }

    String completedText() {
      return completed == null ? null : ((TextResultContent) completed.contents().get(0)).text();
    }

    String partialText(int index) {
      return ((TextResultContent) partials.get(index).contents().get(0)).text();
    }
  }

  /**
   * 记录回调到达顺序的线程安全监听器：partial / complete / error 追加到同一序列，供并发 receive 的顺序断言使用。
   *
   * <p>非 final，便于测试叠加阻塞或异常行为而保留顺序记录能力。
   */
  static class SerialListener implements EnvironmentCapabilityExecutionListener {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      events.add("partial:" + ((TextResultContent) partial.contents().get(0)).text());
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      events.add("complete:" + ((TextResultContent) result.contents().get(0)).text());
    }

    @Override
    public void onError(Throwable error) {
      events.add("error:" + error.getClass().getSimpleName());
    }

    List<String> events() {
      return List.copyOf(events);
    }
  }

  /** 内存连接：记录出站帧，并可按需模拟队列容量拒绝或传输失败。 */
  static final class FakeChannel implements DaemonChannel {

    private final String connectionId;
    private final List<DaemonEnvelope> envelopes = new ArrayList<>();
    private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();
    private boolean open = true;
    private boolean closed;
    private int closeCount;
    private int closeAfterFlushCount;

    /** 下一次递交返回 BUSY：模拟本地出站队列容量/字节预算拒绝。 */
    boolean busyNextOffer;

    /** 下一次递交抛出异常：模拟传输自身发送失败。 */
    boolean failNextOffer;

    /** 下一次递交停在入口，直到 {@link #releaseOffer()}；用于制造代际替换窗口。 */
    volatile boolean holdNextOffer;

    private final CountDownLatch offerEntered = new CountDownLatch(1);
    private final CountDownLatch offerReleased = new CountDownLatch(1);

    EnvironmentId boundEnvironmentId;

    FakeChannel(String connectionId) {
      this.connectionId = connectionId;
    }

    @Override
    public String connectionId() {
      return connectionId;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public DaemonOfferResult offerText(String text) {
      if (holdNextOffer) {
        holdNextOffer = false;
        offerEntered.countDown();
        try {
          if (!offerReleased.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("offer hold was not released");
          }
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("offer hold interrupted", error);
        }
      }
      if (!open) {
        return DaemonOfferResult.CLOSED;
      }
      DaemonEnvelope envelope = codec.decode(text);
      if (busyNextOffer) {
        busyNextOffer = false;
        return DaemonOfferResult.BUSY;
      }
      if (failNextOffer) {
        failNextOffer = false;
        throw new IllegalStateException("transport send failed");
      }
      envelopes.add(envelope);
      if (envelope.environmentId() != null) {
        boundEnvironmentId = envelope.environmentId();
      }
      return DaemonOfferResult.ACCEPTED;
    }

    @Override
    public void closeAfterFlush() {
      closeAfterFlushCount += 1;
      close();
    }

    @Override
    public void close() {
      open = false;
      closed = true;
      closeCount += 1;
    }

    List<DaemonEnvelope> envelopes() {
      return envelopes;
    }

    List<DaemonMessageType> messageTypes() {
      return envelopes.stream().map(DaemonEnvelope::messageType).toList();
    }

    long countOf(DaemonMessageType type) {
      return envelopes.stream().filter(envelope -> envelope.messageType() == type).count();
    }

    DaemonEnvelope lastEnvelope() {
      return envelopes.get(envelopes.size() - 1);
    }

    boolean closed() {
      return closed;
    }

    int closeCount() {
      return closeCount;
    }

    int closeAfterFlushCount() {
      return closeAfterFlushCount;
    }

    boolean awaitOfferEntered() {
      try {
        return offerEntered.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    void releaseOffer() {
      offerReleased.countDown();
    }
  }

  /**
   * 可编程票据服务：默认「申请即 READY」（去重命中），因此测试无需字节上传即可让终态引用合法上传。
   *
   * <p>记录每次 reserve/commit/release 的调用事实，供幂等、竞态与清理断言使用。
   */
  static final class FakeTicketService implements DaemonResourceTicketService {

    final List<TransferRequest> reserves = new ArrayList<>();
    final List<UUID> commits = new ArrayList<>();
    final List<UUID> releases = new ArrayList<>();
    boolean failNextReserve;
    boolean pendingReserve;
    UUID pendingUploadId;

    @Override
    public Ticket reserve(
        EnvironmentId environmentId, String invocationId, TransferRequest request) {
      reserves.add(request);
      if (failNextReserve) {
        failNextReserve = false;
        return new Ticket.Failed("storage is unavailable");
      }
      if (pendingReserve) {
        UUID uploadId = pendingUploadId == null ? request.transferId() : pendingUploadId;
        return new Ticket.Pending(
            uploadId,
            new DaemonPresignedPut(
                "PUT",
                "https://storage.invalid/uploads/" + uploadId,
                Map.of("If-None-Match", "*")));
      }
      return new Ticket.Ready(request.transferId());
    }

    @Override
    public Ticket commit(EnvironmentId environmentId, String invocationId, UUID uploadId) {
      commits.add(uploadId);
      return new Ticket.Ready(uploadId);
    }

    @Override
    public void release(EnvironmentId environmentId, String invocationId, UUID uploadId) {
      if (uploadId != null) {
        releases.add(uploadId);
      }
    }
  }
}
