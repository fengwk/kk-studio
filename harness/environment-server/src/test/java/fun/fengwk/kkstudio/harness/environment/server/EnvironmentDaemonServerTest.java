package fun.fengwk.kkstudio.harness.environment.server;

import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.CALL_ONE;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.CALL_TWO;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.INSTANCE_ID;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.OTHER_ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.OTHER_INSTANCE_ID;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.OTHER_TOKEN;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.TOKEN;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.capabilityRequest;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.completedPayload;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.helloPayload;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.partialPayload;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.readyPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.FakeChannel;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.RecordingListener;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link EnvironmentDaemonServer} 的会话、并发、重连恢复与终态所有权契约测试。
 *
 * <p>全部测试使用内存 fake channel/lease store：核心对同一 Environment 的多个 invocation 必须并发关联；连接丢失不得终结在途调用，
 * 同实例重连以相同 invocationId 重放；不同实例接管与调用方 expire 才产生终态。
 */
class EnvironmentDaemonServerTest {

  /**
   * 测试意图：同一 Environment 的第二个 invocation 必须立即发送并并发持有（无 Busy、无容量槽位），且两个 invocation 的 COMPLETED
   * 严格归属于各自的 invocationId。
   */
  @Test
  void sameEnvironmentHoldsConcurrentInvocationsWithoutBusyRejection() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-concurrent");

    RecordingListener first = new RecordingListener();
    RecordingListener second = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), first);
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_TWO), second);

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.INVOKE),
        channel.messageTypes());
    assertEquals(CALL_ONE.toString(), channel.envelopes().get(1).invocationId());
    assertEquals(CALL_TWO.toString(), channel.envelopes().get(2).invocationId());

    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "one"));
    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_TWO.toString(),
        completedPayload(CALL_TWO, "two"));

    assertEquals("one", first.completedText());
    assertEquals("two", second.completedText());
    assertNull(first.error);
    assertNull(second.error);
    assertFalse(channel.closed());
  }

  /** 测试意图：相同 Environment 下重复使用同一活动 invocationId 属于调用方错误，必须拒绝且不产生第二次 wire 发送。 */
  @Test
  void duplicateActiveInvocationIdIsRejectedWithoutSecondSend() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-duplicate-id");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), channel.messageTypes());
  }

  /** 测试意图：同步终态回调中再次发起同一环境调用必须成功，证明回调不占用环境级排他状态。 */
  @Test
  void synchronousTerminalCallbackCanImmediatelyStartTheNextInvocation() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-sync-terminal");
    AtomicBoolean reentered = new AtomicBoolean();
    RecordingListener nested = new RecordingListener();
    EnvironmentCapabilityExecutionListener listener =
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {}

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            if (reentered.compareAndSet(false, true)) {
              fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_TWO), nested);
            }
          }

          @Override
          public void onError(Throwable error) {}
        };
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "one"));

    assertTrue(reentered.get());
    assertEquals(CALL_TWO.toString(), channel.envelopes().get(2).invocationId());
  }

  /** 测试意图：listener 回调发生在核心锁外，回调中重入核心 API 不得死锁。 */
  @Test
  void listenerCallbacksRunOutsideCoreLocks() throws Exception {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-no-lock");
    CountDownLatch entered = new CountDownLatch(1);
    AtomicBoolean readyInsideCallback = new AtomicBoolean();
    fixture.server.invoke(
        ENVIRONMENT_ID,
        capabilityRequest(CALL_ONE),
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {}

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            readyInsideCallback.set(fixture.server.isReady(ENVIRONMENT_ID));
            entered.countDown();
          }

          @Override
          public void onError(Throwable error) {}
        });

    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "one"));

    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertTrue(readyInsideCallback.get());
  }

  /** 测试意图：PROGRESS 事件按序透传，且不改变该 invocation 的所有权。 */
  @Test
  void progressEventsAreForwardedInOrder() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-partials");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        DaemonMessageType.PROGRESS,
        CALL_ONE.toString(),
        partialPayload(CALL_ONE, "chunk-1"));
    fixture.receive(
        channel,
        DaemonMessageType.PROGRESS,
        CALL_ONE.toString(),
        partialPayload(CALL_ONE, "chunk-2"));
    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "done"));

    assertEquals(2, listener.partials.size());
    assertEquals("chunk-1", listener.partialText(0));
    assertEquals("chunk-2", listener.partialText(1));
    assertEquals("done", listener.completedText());
  }

  /**
   * 测试意图：本地出站队列拒绝（BUSY）不得被当成传输不确定 —— 调用方必须收到确定性的 busy 异常，连接保持可用，且该调用不残留在途目录 （同一 invocationId
   * 可以立即重新发起并成功）。
   */
  @Test
  void queueRejectionIsDeterministicBusyAndKeepsConnectionUsable() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-busy");

    channel.busyNextOffer = true;
    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));

    assertFalse(channel.closed());
    assertTrue(channel.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
    // 只有 WELCOME：被拒绝的 INVOKE 没有产生任何 wire 帧。
    assertEquals(List.of(DaemonMessageType.WELCOME), channel.messageTypes());

    // 同一 invocationId 立即重试成功，证明拒绝未留下任何在途残留。
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), channel.messageTypes());
    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "done"));
    assertEquals("done", listener.completedText());
  }

  /**
   * 测试意图：物理连接丢失不得终结在途调用。同一 Daemon 进程（相同 daemonInstanceId）重连 READY 后，核心必须以相同 invocationId 重放
   * INVOKE，并继续为同一 invocation 派发 daemon 终态。
   */
  @Test
  void sameDaemonInstanceReconnectReplaysActiveInvokeAndKeepsTerminalOwnership() {
    Fixture fixture = new Fixture();
    FakeChannel first = fixture.connectReady("channel-instance-a");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), first.messageTypes());

    // 物理 socket 丢失：连接代际被清理，但在途调用必须保留。
    fixture.server.close(first.connectionId());
    assertTrue(first.closed());
    assertNull(listener.error);
    assertNull(listener.completed);
    assertEquals(Set.of(), fixture.server.readyEnvironments());

    FakeChannel second = fixture.connectReady("channel-instance-b");
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), second.messageTypes());
    assertEquals(CALL_ONE.toString(), second.envelopes().get(1).invocationId());
    // 每次 READY 都唤醒一次会话监听器：重连后 Environment 再次被登记为可用。
    assertEquals(List.of(ENVIRONMENT_ID, ENVIRONMENT_ID), fixture.readyNotifications);

    // 终态仍归属同一 invocation：重放的 STARTED 静默接受，COMPLETED 只回调一次。
    fixture.receive(second, DaemonMessageType.STARTED, CALL_ONE.toString(), "{\"replayed\":true}");
    fixture.receive(
        second,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "replayed"));
    fixture.receive(
        second,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "duplicate"));
    assertEquals("replayed", listener.completedText());
    assertNull(listener.error);
    assertFalse(second.closed());
  }

  /**
   * 测试意图：身份不同的 Daemon 进程接管同一 Environment 时，旧进程已不可能再提供这些调用的终态，因此每个在途调用必须恰好一次地以 send-uncertain
   * 终结，且新进程随后不得再为其派发终态。
   */
  @Test
  void differentDaemonInstanceTakesOverAndOldInvocationBecomesUncertainOnce() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-old-instance");
    RecordingListener first = new RecordingListener();
    RecordingListener second = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), first);
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_TWO), second);

    fixture.server.close("channel-old-instance");
    FakeChannel replacement = fixture.connectReady("channel-new-instance", OTHER_INSTANCE_ID);

    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, first.error);
    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, second.error);
    assertEquals(1, first.errorCount);
    assertEquals(1, second.errorCount);
    assertEquals(List.of(DaemonMessageType.WELCOME), replacement.messageTypes());

    // 旧调用已终结：新连接上的迟到终态被忽略（tombstone），不再是新进程的越权回调。
    fixture.receive(
        replacement,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "late"));
    assertNull(first.completed);
    assertEquals(1, first.errorCount);
    assertFalse(replacement.closed());
  }

  /** 测试意图：同一 Daemon 进程在第 N 次断开重连后仍不产生终态；只有换进程或调用方放弃才收敛，且 BUSY 拒绝不等于放弃。 */
  @Test
  void transportFailureKeepsInvocationActiveForSameInstanceRecovery() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-send-failure");
    RecordingListener listener = new RecordingListener();
    channel.failNextOffer = true;

    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    assertNotNull(handle);
    assertNull(listener.error);
    assertNull(listener.completed);
    assertTrue(channel.closed());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));

    // 同一实例重连：INVOKE 被重放，调用仍在途并可正常收敛。
    FakeChannel reconnected = fixture.connectReady("channel-send-failure-retry");
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), reconnected.messageTypes());
    fixture.receive(
        reconnected,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "done"));
    assertEquals("done", listener.completedText());
    assertNull(listener.error);
  }

  /**
   * 测试意图：FAILED 终态映射为明确异常并终结该 invocation；终态之后同一 invocationId 的迟到终结回调不再回调 listener，而是按 协议 contract
   * 判为越权回调并关闭连接。
   */
  @Test
  void terminalCallbackIsDeliveredOnceAndLateTerminalFrameIsRejected() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-terminal-once");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel, DaemonMessageType.FAILED, CALL_ONE.toString(), "{\"message\":\"boom\"}");
    assertInstanceOf(EnvironmentCapabilityFailedException.class, listener.error);
    assertEquals("boom", listener.error.getMessage());

    // 终态后 handle 不再发送 CANCEL 帧。
    int envelopesAfterTerminal = channel.envelopes().size();
    handle.cancel();
    assertEquals(envelopesAfterTerminal, channel.envelopes().size());

    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "late"));
    assertNull(listener.completed);
    assertEquals(1, listener.errorCount);
    assertFalse(channel.closed());
  }

  /** 测试意图：daemon 主动 CANCELLED 终态映射为取消异常，并释放该 invocation 的关联。 */
  @Test
  void remoteCancelledMapsToCancelledException() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-cancelled");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel, DaemonMessageType.CANCELLED, CALL_ONE.toString(), "{\"reason\":\"stop\"}");

    assertInstanceOf(EnvironmentCapabilityCancelledException.class, listener.error);
    assertEquals("stop", listener.error.getMessage());
  }

  /** 测试意图：STARTED 允许空 payload 或 {@code replayed=true}，非法 replayed 值属于协议违规。 */
  @Test
  void startedAcceptsEmptyOrReplayedTrueAndRejectsOtherPayloads() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-started");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(channel, DaemonMessageType.STARTED, CALL_ONE.toString(), "{}");
    fixture.receive(channel, DaemonMessageType.STARTED, CALL_ONE.toString(), "{\"replayed\":true}");
    assertFalse(channel.closed());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), channel.messageTypes());

    fixture.receive(
        channel, DaemonMessageType.STARTED, CALL_ONE.toString(), "{\"replayed\":false}");
    assertTrue(channel.closed());
    assertEquals(DaemonMessageType.ERROR, channel.lastEnvelope().messageType());
  }

  /**
   * 测试意图：显式 expire（调用方 deadline 路径）是终态收敛点：只发一次 CANCEL、移出在途目录（不再随重连重放），且随后到达的 daemon 终态不再回调
   * listener。
   */
  @Test
  void expireSendsCancelOnceAndSuppressesLaterTerminalCallback() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-expire");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.server.expire(handle);
    fixture.server.expire(handle);

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.CANCEL),
        channel.messageTypes());

    // 迟到终态属于已知 invocation：静默丢弃，不得再回调 listener。
    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "late"));
    assertNull(listener.completed);
    assertNull(listener.error);
    assertFalse(channel.closed());

    // 已放弃的调用不再随重连重放（否则会重新执行副作用）。
    fixture.server.close(channel.connectionId());
    FakeChannel reconnected = fixture.connectReady("channel-expire-retry");
    assertEquals(List.of(DaemonMessageType.WELCOME), reconnected.messageTypes());
  }

  /** 测试意图：expire 只接受本核心签发的执行句柄。 */
  @Test
  void expireRejectsForeignHandle() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-foreign-handle");
    EnvironmentCapabilityExecutionHandle foreign =
        new EnvironmentCapabilityExecutionHandle() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> fixture.server.expire(foreign));
  }

  /** 测试意图：cancel 幂等，终态后 cancel 不再产生 CANCEL 帧。 */
  @Test
  void cancelIsIdempotentAndSkippedAfterTerminal() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-cancel-idempotent");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    handle.cancel();
    handle.cancel();
    assertTrue(handle.isCancelled());
    assertEquals(1, channel.countOf(DaemonMessageType.CANCEL));

    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "done"));
    int afterTerminal = channel.envelopes().size();
    handle.cancel();
    assertEquals(afterTerminal, channel.envelopes().size());
    assertEquals("done", listener.completedText());
  }

  /** 测试意图：同 connectionId 的新连接代际必须幂等清理旧代际，但不终结其保留的在途调用。 */
  @Test
  void reopeningSameConnectionIdCleansUpPreviousGeneration() {
    Fixture fixture = new Fixture();
    FakeChannel first = fixture.connectReady("channel-same-id");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    FakeChannel second = fixture.connectReady("channel-same-id");

    assertTrue(first.closed());
    assertNull(listener.error);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), second.messageTypes());
    assertTrue(second.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：旧连接代际被新 HELLO 抢占后，旧代际的迟到帧不得影响新代际。 */
  @Test
  void staleGenerationFramesAreIgnoredAfterTakeover() {
    Fixture fixture = new Fixture();
    FakeChannel first = fixture.connectReady("channel-first");
    RecordingListener firstListener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), firstListener);

    // 旧持有者被判定为已失效（例如租约过期后同一环境重新 HELLO）。
    FakeChannel second = fixture.reconnectReady("channel-second");

    assertTrue(first.closed());
    assertNull(firstListener.error);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), second.messageTypes());

    // 旧代际已解绑：迟到帧被直接丢弃，不产生协议错误帧，也不影响新代际。
    int secondEnvelopes = second.envelopes().size();
    fixture.receive(first, DaemonMessageType.HEARTBEAT, null, "{}");
    assertEquals(secondEnvelopes, second.envelopes().size());
    assertTrue(second.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：READY 之后租约失效（fence lost）必须使后续 HEARTBEAT 以协议错误关闭连接。 */
  @Test
  void heartbeatFenceLossClosesConnection() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-fence-lost");
    fixture.leaseStore.heartbeatResult = false;

    fixture.receive(channel, DaemonMessageType.HEARTBEAT, null, "{}");

    assertTrue(channel.closed());
    assertEquals(DaemonMessageType.ERROR, channel.lastEnvelope().messageType());
  }

  /** 测试意图：READY 时围栏已失效不得进入 READY，必须按协议错误关闭连接。 */
  @Test
  void readyFenceLossClosesConnection() {
    Fixture fixture = new Fixture();
    fixture.leaseStore.markReadyResult = false;
    FakeChannel channel = new FakeChannel("channel-ready-fence");
    fixture.server.open(channel);
    fixture.receiveHello(channel);
    fixture.receiveReady(channel);

    assertTrue(channel.closed());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：未 READY 或租约已失效时 INVOKE 必须在发送前失败，且不产生 wire 帧。 */
  @Test
  void invokeRequiresLiveReadyLease() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("channel-not-ready");
    fixture.server.open(channel);
    fixture.receiveHello(channel);
    int afterHello = channel.envelopes().size();
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(afterHello, channel.envelopes().size());

    fixture.receiveReady(channel);
    fixture.leaseStore.holdsReadyLease = false;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(afterHello, channel.envelopes().size());
  }

  /** 测试意图：租约存储不可用时 INVOKE 必须 fail-closed 且不发送 wire 帧。 */
  @Test
  void invokeFailsClosedWhenLeaseStoreIsUnavailable() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-db-down");
    fixture.leaseStore.databaseUnavailable = true;

    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(List.of(DaemonMessageType.WELCOME), channel.messageTypes());
  }

  /** 测试意图：未注册环境的调用立即不可用，descriptor 漂移与非法 workdir/callId 都在发送前拒绝。 */
  @Test
  void invokeValidatesRequestBeforeWireSend() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-invoke-validation");

    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                OTHER_ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));

    EnvironmentCapabilityDescriptor drifted =
        new EnvironmentCapabilityDescriptor(
            EnvironmentCapabilityIds.FS_READ,
            "999",
            descriptor().inputSchema(),
            Duration.ofSeconds(5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, requestWithDescriptor(drifted, CALL_ONE), new RecordingListener()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID,
                new EnvironmentCapabilityExecutionRequest(
                    descriptor(),
                    new EnvironmentCapabilityCall("not-a-uuid", "{\"path\":\"README.md\"}"),
                    Duration.ofSeconds(5)),
                new RecordingListener()));
  }

  /**
   * 测试意图：coding 能力的 workdir 在 frame send 前按该连接 READY 中冻结的 OS 做词法校验——缺失、相对与跨 OS 形态都拒绝且不产生 INVOKE 帧；而
   * skill.load 不要求 workdir，照常发送。
   */
  @Test
  void validatesWorkdirAgainstReadyOperatingSystemBeforeFrameSend() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-workdir-validation");

    // 缺失 workdir / 相对 workdir / Windows drive 形态（该连接冻结的是 LINUX）都在发送前拒绝。
    // 注意 `/srv/../repo` 仍是形状合法的 Unix 绝对路径：词法校验不折叠 `..`，越界事实由 Daemon 自身 Path 与 permission 处理。
    String[] rejectedArguments = {
      "{\"path\":\"README.md\"}",
      "{\"workdir\":\"relative/dir\",\"path\":\"README.md\"}",
      "{\"workdir\":\"C:\\\\repo\",\"path\":\"README.md\"}",
      "{\"workdir\":\" /srv/repo\",\"path\":\"README.md\"}"
    };
    for (String arguments : rejectedArguments) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.server.invoke(
                  ENVIRONMENT_ID, requestWith(arguments, CALL_ONE), new RecordingListener()),
          "必须在发送前拒绝: " + arguments);
    }
    // 全部被拒：通道上除了 WELCOME 没有任何 INVOKE 帧。
    assertEquals(List.of(DaemonMessageType.WELCOME), channel.messageTypes());

    // 显式绝对 workdir 通过校验并真正产生 INVOKE 帧。
    fixture.server.invoke(
        ENVIRONMENT_ID,
        requestWith("{\"workdir\":\"/srv/repo\",\"path\":\"README.md\"}", CALL_ONE),
        new RecordingListener());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), channel.messageTypes());
  }

  /** 测试意图：skill.load 不属于 workdir 能力，缺少 workdir 也必须照常发送 INVOKE（身份三字段仍需齐全）。 */
  @Test
  void skillLoadDoesNotRequireWorkdir() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-skill-load");
    EnvironmentCapabilityDescriptor skillLoad =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_LOAD);

    fixture.server.invoke(
        ENVIRONMENT_ID,
        new EnvironmentCapabilityExecutionRequest(
            skillLoad,
            new EnvironmentCapabilityCall(
                CALL_ONE.toString(),
                "{\"sourceId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                    + "\"name\":\"dev\",\"revision\":\""
                    + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}"),
            Duration.ofSeconds(5)),
        new RecordingListener());

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), channel.messageTypes());
  }

  /** 测试意图：并发 HELLO 抢占同一环境时只有一个连接获得 WELCOME，另一个以 RETRY_LATER 关闭。 */
  @Test
  void concurrentHelloForActiveRouteIsRejectedWithRetryLater() {
    Fixture fixture = new Fixture();
    FakeChannel first = fixture.connectReady("channel-held");
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));

    FakeChannel second = new FakeChannel("channel-second");
    fixture.server.open(second);
    fixture.receiveHello(second);

    assertTrue(second.closed());
    assertEquals(DaemonMessageType.ERROR, second.lastEnvelope().messageType());
    assertTrue(second.lastEnvelope().payloadJson().contains(DaemonProtocol.ERROR_CODE_RETRY_LATER));
    assertTrue(first.isOpen());

    // 旧持有者必须仍然可服务调用。
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), first.messageTypes());
  }

  /** 测试意图：租约存储判定旧 leaseToken 不再活跃时，新 HELLO 必须直接接管而非 RETRY_LATER。 */
  @Test
  void staleHolderTokenIsTakenOverByFreshHello() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-stale-holder");
    FakeChannel takeover = fixture.reconnectReady("channel-takeover");
    assertTrue(takeover.isOpen());
    assertEquals(List.of(DaemonMessageType.WELCOME), takeover.messageTypes());
  }

  /** 测试意图：未知注册凭据必须以通用 REGISTRATION_REJECTED 拒绝，不占用路由且不回显凭据。 */
  @Test
  void unknownRegistrationTokenIsRejected() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("channel-bad-token");
    fixture.server.open(channel);

    fixture.receiveHello(channel, "wrong-token", ENVIRONMENT_ID);

    assertTrue(channel.closed());
    assertEquals(DaemonMessageType.ERROR, channel.lastEnvelope().messageType());
    String errorPayload = channel.lastEnvelope().payloadJson();
    assertTrue(errorPayload.contains(DaemonProtocol.ERROR_CODE_REGISTRATION_REJECTED));
    assertFalse(errorPayload.contains("wrong-token"));
    assertFalse(fixture.leaseStore.acquired);
  }

  /** 测试意图：租约存储判定凭据无效或路由活跃时，分别以对应错误码关闭连接。 */
  @Test
  void leaseStoreRejectionAndRetryLaterMapToErrorCodes() {
    Fixture rejected = new Fixture();
    rejected.leaseStore.rejected = true;
    FakeChannel rejectedChannel = new FakeChannel("channel-registration-rejected");
    rejected.server.open(rejectedChannel);
    rejected.receiveHello(rejectedChannel);
    assertTrue(rejectedChannel.lastEnvelope().payloadJson().contains("REGISTRATION_REJECTED"));

    Fixture retryLater = new Fixture();
    retryLater.leaseStore.retryLater = true;
    FakeChannel retryChannel = new FakeChannel("channel-retry-later");
    retryLater.server.open(retryChannel);
    retryLater.receiveHello(retryChannel);
    assertTrue(retryChannel.lastEnvelope().payloadJson().contains("RETRY_LATER"));
  }

  /** 测试意图：租约存储不可用时 HELLO 必须 fail-closed 关闭连接。 */
  @Test
  void helloFailsClosedWhenLeaseStoreIsUnavailable() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("channel-hello-db-down");
    fixture.server.open(channel);
    fixture.leaseStore.databaseUnavailable = true;

    fixture.receiveHello(channel);

    assertTrue(channel.closed());
    assertEquals(DaemonMessageType.ERROR, channel.lastEnvelope().messageType());
  }

  /** 测试意图：注册凭据目录抛异常时 HELLO 必须关闭连接，不建立任何绑定。 */
  @Test
  void helloFailsClosedWhenRegistrationDirectoryFails() {
    Fixture fixture = new Fixture();
    fixture.failRegistrationDirectory();
    FakeChannel channel = new FakeChannel("channel-directory-down");
    fixture.server.open(channel);

    fixture.receiveHello(channel);

    assertTrue(channel.closed());
    assertEquals(Set.of(), fixture.server.readyEnvironments());
  }

  /** 测试意图：READY 之前的心跳与 HELLO 之前的 READY 都是协议违规，必须以协议错误关闭连接。 */
  @Test
  void handshakeOrderingViolationsCloseConnection() {
    Fixture readyFirst = new Fixture();
    FakeChannel readyChannel = new FakeChannel("channel-ready-first");
    readyFirst.server.open(readyChannel);
    readyFirst.receiveReady(readyChannel);
    assertTrue(readyChannel.closed());
    assertEquals(DaemonMessageType.ERROR, readyChannel.lastEnvelope().messageType());

    Fixture heartbeatFirst = new Fixture();
    FakeChannel heartbeatChannel = new FakeChannel("channel-heartbeat-first");
    heartbeatFirst.server.open(heartbeatChannel);
    heartbeatFirst.receiveHello(heartbeatChannel);
    heartbeatFirst.receive(heartbeatChannel, DaemonMessageType.HEARTBEAT, null, "{}");
    assertTrue(heartbeatChannel.closed());
  }

  /** 测试意图：重复 HELLO、HELLO 声明 environmentId、协议版本、catalog 版本与实例身份非法都必须关闭连接。 */
  @Test
  void helloValidationRejectsMalformedHandshakes() {
    // 重复 HELLO：第二个 HELLO 是协议违规。
    Fixture duplicate = new Fixture();
    FakeChannel duplicateChannel = duplicate.connectReady("channel-duplicate-hello");
    duplicate.receiveHello(duplicateChannel);
    assertTrue(duplicateChannel.closed());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.ERROR),
        duplicateChannel.messageTypes());

    // 重复 READY 是协议违规：协议没有可重放的序号，READY 只能推进一次会话事实。
    Fixture duplicateReady = new Fixture();
    FakeChannel readyChannel = duplicateReady.connectReady("channel-duplicate-ready");
    duplicateReady.receiveReady(readyChannel);
    assertTrue(readyChannel.closed());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.ERROR), readyChannel.messageTypes());

    assertTrue(
        helloRejected(
            "channel-version-mismatch",
            "{\"protocolVersion\":9,\"registrationToken\":\""
                + TOKEN
                + "\",\"capabilityCatalogVersion\":\""
                + EnvironmentCapabilityCatalog.version()
                + "\",\"daemonInstanceId\":\""
                + INSTANCE_ID
                + "\"}"));
    assertTrue(
        helloRejected(
            "channel-catalog-mismatch",
            "{\"protocolVersion\":"
                + DaemonProtocol.VERSION
                + ",\"registrationToken\":\""
                + TOKEN
                + "\",\"capabilityCatalogVersion\":\"999\",\"daemonInstanceId\":\""
                + INSTANCE_ID
                + "\"}"));
    assertTrue(
        helloRejected(
            "channel-unexpected-field",
            "{\"protocolVersion\":"
                + DaemonProtocol.VERSION
                + ",\"registrationToken\":\""
                + TOKEN
                + "\",\"capabilityCatalogVersion\":\""
                + EnvironmentCapabilityCatalog.version()
                + "\",\"daemonInstanceId\":\""
                + INSTANCE_ID
                + "\",\"extra\":true}"));
    assertTrue(
        helloRejected(
            "channel-missing-token",
            "{\"protocolVersion\":"
                + DaemonProtocol.VERSION
                + ",\"capabilityCatalogVersion\":\""
                + EnvironmentCapabilityCatalog.version()
                + "\",\"daemonInstanceId\":\""
                + INSTANCE_ID
                + "\"}"));
    // 实例身份必须是 canonical UUID：缺失或非规范形态都关闭连接。
    assertTrue(
        helloRejected(
            "channel-missing-instance",
            "{\"protocolVersion\":"
                + DaemonProtocol.VERSION
                + ",\"registrationToken\":\""
                + TOKEN
                + "\",\"capabilityCatalogVersion\":\""
                + EnvironmentCapabilityCatalog.version()
                + "\"}"));
    assertTrue(
        helloRejected(
            "channel-non-canonical-instance",
            helloPayload(TOKEN, "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")));
    assertTrue(
        helloRejected("channel-braced-instance", helloPayload(TOKEN, "{" + INSTANCE_ID + "}")));
  }

  /** 直接发送一个 HELLO 帧并返回该连接是否被协议错误关闭。 */
  private static boolean helloRejected(String connectionId, String payload) {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel(connectionId);
    fixture.server.open(channel);
    fixture.server.receive(
        channel.connectionId(),
        EnvironmentDaemonServerTestSupport.encode(null, DaemonMessageType.HELLO, null, payload));
    return channel.closed();
  }

  /** 测试意图：绑定连接上的 environmentId 不匹配、或者消息类型方向错误，都以协议错误关闭连接。 */
  @Test
  void boundConnectionRejectsMismatchedScopeAndWrongDirection() {
    Fixture mismatched = new Fixture();
    FakeChannel mismatchedChannel = mismatched.connectReady("channel-scope-mismatch");
    mismatched.server.receive(
        mismatchedChannel.connectionId(),
        EnvironmentDaemonServerTestSupport.encode(
            OTHER_ENVIRONMENT_ID, DaemonMessageType.HEARTBEAT, null, "{}"));
    assertTrue(mismatchedChannel.closed());

    // 方向错误：daemon 不得向服务端发送 INVOKE。
    Fixture direction = new Fixture();
    FakeChannel directionChannel = direction.connectReady("channel-wrong-direction");
    direction.receive(directionChannel, DaemonMessageType.INVOKE, CALL_ONE.toString(), "{}");
    assertTrue(directionChannel.closed());
  }

  /** 测试意图：READY/HEARTBEAT payload 必须严格匹配，包含意外字段即协议违规。 */
  @Test
  void readyAndHeartbeatPayloadsAreStrict() {
    Fixture heartbeatExtra = new Fixture();
    FakeChannel extraChannel = heartbeatExtra.connectReady("channel-heartbeat-extra");
    heartbeatExtra.receive(extraChannel, DaemonMessageType.HEARTBEAT, null, "{\"unexpected\":1}");
    assertTrue(extraChannel.closed());

    Fixture readyInvalid = new Fixture();
    FakeChannel readyChannel = new FakeChannel("channel-ready-invalid");
    readyInvalid.server.open(readyChannel);
    readyInvalid.receiveHello(readyChannel);
    readyInvalid.receive(readyChannel, DaemonMessageType.READY, null, "{}");
    assertTrue(readyChannel.closed());

    Fixture readyWithInvocation = new Fixture();
    FakeChannel invocationChannel = readyWithInvocation.connectReady("channel-ready-invocation");
    readyWithInvocation.receive(
        invocationChannel, DaemonMessageType.READY, CALL_ONE.toString(), readyPayload());
    assertTrue(invocationChannel.closed());
  }

  /** 测试意图：ERROR 帧 payload 只接受精确的 message 字段。 */
  @Test
  void errorPayloadsAreStrict() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-error");
    fixture.receive(channel, DaemonMessageType.ERROR, null, "{\"message\":\"daemon oops\"}");
    assertFalse(channel.closed());

    fixture.receive(channel, DaemonMessageType.ERROR, null, "{\"message\":\"\",\"code\":\"X\"}");
    assertTrue(channel.closed());
  }

  /** 测试意图：未知 invocationId 的回调不得被静默接受，必须按协议违规关闭连接。 */
  @Test
  void unknownInvocationCallbackClosesConnection() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-unknown-invocation");
    fixture.receive(channel, DaemonMessageType.PROGRESS, UUID.randomUUID().toString(), "{}");
    assertTrue(channel.closed());
  }

  /** 测试意图：回调只按 invocationId 归属，不按连接代际 —— 同一 Daemon 进程重连后的新连接代际是这些在途调用的合法持有者，其终态必须被派发。 */
  @Test
  void reconnectedSameInstanceOwnsCallbackForReplayedInvocation() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-owner");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    FakeChannel reconnected = fixture.reconnectReady("channel-reconnected");
    fixture.receive(
        reconnected,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "x"));

    assertFalse(reconnected.closed());
    assertEquals("x", listener.completedText());
    assertNull(listener.error);
  }

  /** 测试意图：未绑定 invocation 的 PROGRESS 按协议违规关闭（未知 invocation 且无 tombstone）。 */
  @Test
  void progressBeforeTerminalOwnershipIsProtocolViolation() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-partial-unknown");
    fixture.receive(
        channel, DaemonMessageType.PROGRESS, CALL_ONE.toString(), partialPayload(CALL_ONE, "x"));
    assertTrue(channel.closed());
  }

  /** 测试意图：READY 帧的 daemon 能力必须写入租约存储，且 READY 唤醒会话监听器一次。 */
  @Test
  void readyRegistersCapabilitiesAndNotifiesSessionListener() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("channel-ready");
    fixture.server.open(channel);
    fixture.receiveHello(channel);
    int beforeReady = channel.envelopes().size();
    fixture.receiveReady(channel);

    assertEquals(
        EnvironmentDaemonServerTestSupport.CAPABILITIES, fixture.leaseStore.readyCapabilities);
    assertEquals(List.of(ENVIRONMENT_ID), fixture.readyNotifications);
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
    assertEquals(Set.of(ENVIRONMENT_ID), fixture.server.readyEnvironments());
    assertEquals(beforeReady, channel.envelopes().size());
  }

  /** 测试意图：不同 Environment 并行持有各自调用，各自终态互不干扰。 */
  @Test
  void distinctEnvironmentsStayConcurrentAndRouteByExactId() {
    Fixture fixture = new Fixture();
    FakeChannel first = fixture.connectReady("channel-env-1");
    FakeChannel second = new FakeChannel("channel-env-2");
    fixture.server.open(second);
    fixture.receiveHello(second, OTHER_TOKEN, OTHER_ENVIRONMENT_ID, OTHER_INSTANCE_ID);
    fixture.receiveReady(second);

    RecordingListener firstListener = new RecordingListener();
    RecordingListener secondListener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), firstListener);
    fixture.server.invoke(OTHER_ENVIRONMENT_ID, capabilityRequest(CALL_TWO), secondListener);

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), first.messageTypes());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), second.messageTypes());

    fixture.receive(
        first, DaemonMessageType.COMPLETED, CALL_ONE.toString(), completedPayload(CALL_ONE, "one"));
    assertNotNull(firstListener.completed);
    assertNull(secondListener.completed);
  }

  /** 测试意图：未知 connectionId 的入站帧与关闭请求被安全忽略。 */
  @Test
  void unknownConnectionFramesAreIgnored() {
    Fixture fixture = new Fixture();
    fixture.server.receive(
        "missing-connection",
        EnvironmentDaemonServerTestSupport.encode(
            ENVIRONMENT_ID, DaemonMessageType.HEARTBEAT, null, "{}"));
    fixture.server.close("missing-connection");
    assertEquals(Set.of(), fixture.server.readyEnvironments());
  }

  /** 测试意图：无 HELLO 的连接在关闭时不得触碰租约存储或产生错误帧。 */
  @Test
  void closingUnboundConnectionDoesNotTouchLeaseStore() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("channel-unbound");
    fixture.server.open(channel);

    fixture.server.close(channel.connectionId());

    assertTrue(channel.closed());
    assertFalse(fixture.leaseStore.disconnected);
    assertEquals(Set.of(), fixture.server.readyEnvironments());
  }

  /** 测试意图：协议错误走 close-after-flush 语义，保证已入队的 ERROR 帧先被送出。 */
  @Test
  void protocolErrorUsesCloseAfterFlush() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-error-close");
    fixture.receive(channel, DaemonMessageType.HEARTBEAT, null, "{\"unexpected\":1}");

    assertEquals(1, channel.closeAfterFlushCount());
    assertEquals(DaemonMessageType.ERROR, channel.lastEnvelope().messageType());
  }

  /** 测试意图：协议错误发生在 HELLO 之前时 ERROR 帧 scope 使用入站声明的 environmentId。 */
  @Test
  void protocolErrorBeforeHelloUsesInboundScope() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("channel-error-scope");
    fixture.server.open(channel);
    fixture.receive(channel, DaemonMessageType.HEARTBEAT, null, "{}");

    assertEquals(ENVIRONMENT_ID, channel.lastEnvelope().environmentId());
  }

  /** 测试意图：非法 connectionId 属于调用方错误。 */
  @Test
  void blankConnectionIdIsRejected() {
    Fixture fixture = new Fixture();
    assertThrows(IllegalArgumentException.class, () -> fixture.server.open(new FakeChannel(" ")));
  }

  /** 测试意图：会话设置每次判定现读，心跳超时变更立即生效。 */
  @Test
  void settingsAreReadOnEveryDecision() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-settings");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    fixture.receive(
        channel,
        DaemonMessageType.COMPLETED,
        CALL_ONE.toString(),
        completedPayload(CALL_ONE, "done"));
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /**
   * 测试意图：Platform 目录查询准入使用的 {@code holdsReadyLease} 只在存在本地连接代币时访问租约存储，并以租约存储返回为准 （数据库是 READY
   * 权威事实），存储不可用时 fail-closed 返回 false，绝不向调用方抛出基础设施异常。
   */
  @Test
  void holdsReadyLeaseFailsClosedWithoutLocalConnectionOrHealthyStore() {
    Fixture fixture = new Fixture();
    assertFalse(fixture.server.holdsReadyLease(ENVIRONMENT_ID));

    FakeChannel channel = new FakeChannel("channel-lease-probe");
    fixture.server.open(channel);
    fixture.receiveHello(channel);
    // HELLO 已换发租约代币：该探测按数据库权威判定，而不是本地 READY 标志。
    assertTrue(fixture.server.holdsReadyLease(ENVIRONMENT_ID));

    fixture.leaseStore.holdsReadyLease = false;
    assertFalse(fixture.server.holdsReadyLease(ENVIRONMENT_ID));

    fixture.leaseStore.holdsReadyLease = true;
    fixture.receiveReady(channel);
    assertTrue(fixture.server.holdsReadyLease(ENVIRONMENT_ID));

    fixture.leaseStore.databaseUnavailable = true;
    assertFalse(fixture.server.holdsReadyLease(ENVIRONMENT_ID));
  }

  /** 测试意图：READY 快照只包含当前活跃 READY 代际；连接关闭后立即为空（Platform 用它决定是否本地派发目录查询）。 */
  @Test
  void readyEnvironmentsSnapshotTracksConnectionLifecycle() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-ready-snapshot");
    assertEquals(Set.of(ENVIRONMENT_ID), fixture.server.readyEnvironments());

    fixture.server.close(channel.connectionId());

    assertEquals(Set.of(), fixture.server.readyEnvironments());
  }

  /** 测试意图：非 UUID 或非 canonical 的 call.id 与 invocationId 属于协议/调用方错误，必须在触碰 wire 之前被拒绝。 */
  @Test
  void malformedUuidFieldsAreRejectedBeforeWireSend() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-malformed-uuid");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID,
                new EnvironmentCapabilityExecutionRequest(
                    descriptor(), new EnvironmentCapabilityCall("  ", "{}"), Duration.ofSeconds(5)),
                new RecordingListener()));
    assertEquals(List.of(DaemonMessageType.WELCOME), channel.messageTypes());

    // 非 canonical UUID（大写/带花括号）同样被拒绝，避免同一 invocation 有多个文本形态。
    fixture.receive(channel, DaemonMessageType.PROGRESS, CALL_ONE.toString().toUpperCase(), "{}");
    assertTrue(channel.closed());
  }

  /** 测试意图：invoke 的 binding environmentId 为空或请求为 null 属于调用方错误，必须显式拒绝而不是 NPE 或静默发送。 */
  @Test
  void invokeRejectsNullArguments() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-invoke-null");

    assertThrows(
        NullPointerException.class,
        () -> fixture.server.invoke(null, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertThrows(
        NullPointerException.class,
        () -> fixture.server.invoke(ENVIRONMENT_ID, null, new RecordingListener()));
    assertThrows(
        NullPointerException.class,
        () -> fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), null));
  }

  /** 测试意图：首帧不是 HELLO 的连接必须以协议错误关闭，且不推进任何会话状态。 */
  @Test
  void nonHelloFirstMessageIsProtocolViolation() {
    Fixture fixture = new Fixture();
    FakeChannel unbound = new FakeChannel("channel-ready-never-hello");
    fixture.server.open(unbound);
    fixture.receive(unbound, DaemonMessageType.READY, null, readyPayload());

    assertTrue(unbound.closed());
    assertEquals(DaemonMessageType.ERROR, unbound.lastEnvelope().messageType());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：unknown connectionId 的过期帧在目录查询并发期间被安全忽略，不产生任何状态变化。 */
  @Test
  void receiveIgnoresUnknownConnectionDuringConcurrentWork() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("channel-concurrent-receive");
    fixture.server.receive(
        "stale-connection",
        EnvironmentDaemonServerTestSupport.encode(
            ENVIRONMENT_ID, DaemonMessageType.HEARTBEAT, null, "{}"));

    assertTrue(channel.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
    assertEquals(Set.of(ENVIRONMENT_ID), fixture.server.readyEnvironments());
  }

  /** 测试意图：会话设置对象拒绝非正的心跳超时与资源上限，避免装配期静默使用无效边界。 */
  @Test
  void settingsRejectNonPositiveBounds() {
    assertThrows(
        IllegalArgumentException.class, () -> new EnvironmentServerSettings(Duration.ZERO, 1024L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentServerSettings(Duration.ofSeconds(1), 0L));
    assertThrows(NullPointerException.class, () -> new EnvironmentServerSettings(null, 1024L));
    assertEquals(
        Duration.ofSeconds(1),
        new EnvironmentServerSettings(Duration.ofSeconds(1), 1024L).heartbeatTimeout());
  }

  private static EnvironmentCapabilityDescriptor descriptor() {
    return EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
  }

  /** 以给定 arguments 构造 fs.read 请求；arguments 原样进入执行请求，用于断言发送前 workdir 校验。 */
  private static EnvironmentCapabilityExecutionRequest requestWith(String arguments, UUID callId) {
    return new EnvironmentCapabilityExecutionRequest(
        descriptor(),
        new EnvironmentCapabilityCall(callId.toString(), arguments),
        Duration.ofSeconds(5));
  }

  private static EnvironmentCapabilityExecutionRequest requestWithDescriptor(
      EnvironmentCapabilityDescriptor descriptor, UUID callId) {
    return new EnvironmentCapabilityExecutionRequest(
        descriptor,
        new EnvironmentCapabilityCall(callId.toString(), "{\"path\":\"README.md\"}"),
        Duration.ofSeconds(5));
  }
}
