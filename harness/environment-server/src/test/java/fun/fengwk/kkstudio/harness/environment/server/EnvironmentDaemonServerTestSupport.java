package fun.fengwk.kkstudio.harness.environment.server;

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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

  private static final DaemonCapabilitiesCodec CAPABILITIES_CODEC = new DaemonCapabilitiesCodec();
  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonCapabilityResultCodec RESULT_CODEC = new DaemonCapabilityResultCodec();

  static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(DaemonOperatingSystem.LINUX, "Asia/Shanghai", "note", "/root"),
          0,
          List.of());

  private EnvironmentDaemonServerTestSupport() {}

  static String readyPayload() {
    return CAPABILITIES_CODEC.encode(CAPABILITIES);
  }

  static String completedPayload(UUID invocationId, String text) {
    return RESULT_CODEC.encodeCompleted(
        EnvironmentCapabilityResult.text(invocationId.toString(), text), inlineStore());
  }

  static String partialPayload(UUID invocationId, String text) {
    return RESULT_CODEC.encodePartial(
        EnvironmentCapabilityResult.text(invocationId.toString(), text), inlineStore());
  }

  static String helloPayload(String token) {
    return helloPayload(token, INSTANCE_ID);
  }

  static String helloPayload(String token, String daemonInstanceId) {
    return "{\"protocolVersion\":"
        + DaemonProtocol.VERSION
        + ",\"registrationToken\":\""
        + token
        + "\",\"capabilityCatalogVersion\":\""
        + EnvironmentCapabilityCatalog.version()
        + "\",\"daemonInstanceId\":\""
        + daemonInstanceId
        + "\"}";
  }

  static String encode(
      EnvironmentId scope, DaemonMessageType type, String invocationId, String payload) {
    return ENVELOPE_CODEC.encode(
        new DaemonEnvelope(DaemonProtocol.VERSION, type, scope, invocationId, payload));
  }

  /** 一个通过 READY OS（LINUX）发送前 workdir 校验的 fs.read 请求：frame send 前必须携带显式绝对 workdir。 */
  static EnvironmentCapabilityExecutionRequest capabilityRequest(UUID callId) {
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
    return new EnvironmentCapabilityExecutionRequest(
        descriptor,
        new EnvironmentCapabilityCall(
            callId.toString(), "{\"workdir\":\"/srv/repo\",\"path\":\"README.md\"}"),
        Duration.ofSeconds(5));
  }

  private static DaemonResourceStore inlineStore() {
    return new DaemonResourceStore() {
      @Override
      public DaemonResourceRef store(byte[] content, String mediaType) {
        throw new UnsupportedOperationException();
      }

      @Override
      public byte[] read(DaemonResourceRef ref) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** 测试夹具：一个核心实例 + 可控租约存储 + 会话监听记录。 */
  static final class Fixture {

    final FakeLeaseStore leaseStore = new FakeLeaseStore();
    final List<EnvironmentId> readyNotifications = new ArrayList<>();
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
              () -> new EnvironmentServerSettings(Duration.ofSeconds(60), 8L * 1024 * 1024));
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
  }
}
