package fun.fengwk.kkstudio.platform.daemon.server;

import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.CALL_ONE;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.CALL_TWO;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.DAEMON_VERSION;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.INSTANCE_ID;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.OTHER_ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.OTHER_INSTANCE_ID;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.OTHER_TOKEN;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.TOKEN;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.cancelledEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.capabilityRequest;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.completedEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.completedResourceEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.executionExitedEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.failedEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.heartbeatEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.progressEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.readyEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.recoverNotFoundEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.resourceUploadCommitEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.resourceUploadRequestEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.runtimeStatusEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.startedEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.updateResultEnvelope;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
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
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdatePhase;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.ExecutionExited;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeLeaseBinding;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeOpenMode;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceUploadRequest;
import fun.fengwk.kkstudio.platform.daemon.protocol.Started;
import fun.fengwk.kkstudio.platform.daemon.protocol.TicketState;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpdatePhase;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.FakeControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.Fixture;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.RecordingListener;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.SerialListener;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * {@link EnvironmentDaemonServer} 的会话、并发、重连恢复与终态所有权契约测试。
 *
 * <p>全部测试使用内存 fake channel/lease store：核心对同一 Environment 的多个 invocation 必须并发关联；连接丢失不得终结在途调用，
 * 同实例重连以相同 invocationId 重放；不同实例接管与调用方 expire 才产生终态。
 */
class EnvironmentDaemonServerTest {

  /** 测试用 SHA-256 摘要：{@code "abc"} 的标准摘要。 */
  private static final String SHA_A =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  private static final UUID TRANSFER_ONE = UUID.fromString("22222222-2222-2222-2222-222222222222");

  /** 合成秘密正文：注入的失败携带它，用于证明安全日志绝不回显原始异常内容。 */
  private static final String SYNTHETIC_SECRET = "synthetic-secret-value";

  private static final Charset UTF_8 = StandardCharsets.UTF_8;

  /**
   * 测试意图：同一 Environment 的第二个 invocation 必须立即发送并并发持有（无 Busy、无容量槽位），且两个 invocation 的 COMPLETED
   * 严格归属于各自的 invocationId。
   */
  @Test
  void sameEnvironmentHoldsConcurrentInvocationsWithoutBusyRejection() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-concurrent");

    RecordingListener first = new RecordingListener();
    RecordingListener second = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), first);
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_TWO), second);

    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.INVOKE, MessageType.INVOKE),
        channel.messageTypes());
    List<Envelope> invokes =
        channel.envelopes().stream()
            .filter(envelope -> envelope.getMessageType() == MessageType.INVOKE)
            .toList();
    assertEquals(CALL_ONE.toString(), invokes.get(0).getInvocationId());
    assertEquals(CALL_TWO.toString(), invokes.get(1).getInvocationId());

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "one"));
    fixture.receive(channel, completedEnvelope(CALL_TWO.toString(), "two"));

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
    FakeControlChannel channel = fixture.connectReady("channel-duplicate-id");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), channel.messageTypes());
  }

  /** 测试意图：UUID.fromString 会接受的大写或短段形式不是规范 call.id，必须在任何 wire 发送前拒绝。 */
  @Test
  void nonCanonicalCallIdIsRejectedBeforeAnyWireSend() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-non-canonical-id");
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            descriptor,
            new EnvironmentCapabilityCall(
                CALL_ONE.toString().toUpperCase(Locale.ROOT), "{\"path\":\"/srv/repo/README.md\"}"),
            Duration.ofSeconds(5));

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.server.invoke(ENVIRONMENT_ID, request, new RecordingListener()));
    assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes());
  }

  /** 测试意图：同步终态回调中再次发起同一环境调用必须成功，证明回调不占用环境级排他状态。 */
  @Test
  void synchronousTerminalCallbackCanImmediatelyStartTheNextInvocation() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-sync-terminal");
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

          @Override
          public void onExitConfirmed() {}
        };
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "one"));

    assertTrue(reentered.get());
    List<Envelope> invokes =
        channel.envelopes().stream()
            .filter(envelope -> envelope.getMessageType() == MessageType.INVOKE)
            .toList();
    assertEquals(CALL_TWO.toString(), invokes.get(1).getInvocationId());
  }

  /** 测试意图：listener 回调发生在核心锁外，回调中重入核心 API 不得死锁。 */
  @Test
  void listenerCallbacksRunOutsideCoreLocks() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-no-lock");
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

          @Override
          public void onExitConfirmed() {}
        });

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "one"));

    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertTrue(readyInsideCallback.get());
  }

  /** 测试意图：PROGRESS 事件按序透传，且不改变该 invocation 的所有权。 */
  @Test
  void progressEventsAreForwardedInOrder() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-partials");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(channel, progressEnvelope(CALL_ONE.toString(), "chunk-1"));
    fixture.receive(channel, progressEnvelope(CALL_ONE.toString(), "chunk-2"));
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));

    assertEquals(2, listener.partials.size());
    assertEquals("chunk-1", listener.partialText(0));
    assertEquals("chunk-2", listener.partialText(1));
    assertEquals("done", listener.completedText());
  }

  /**
   * 测试意图（C05）：并发 receive 下回调顺序仍由连接的 gate 决定。首条 PROGRESS 的回调在锁外阻塞时，第二条 receive 的 COMPLETED
   * 只能完成状态推进与批次入队，不得抢先投递终态；释放后观察顺序必须仍是 partial -> terminal。
   */
  @Test
  void inFlightPartialCallbackBlocksLaterTerminalUntilOrdered() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-ordered-callback");
    CountDownLatch partialEntered = new CountDownLatch(1);
    CountDownLatch releasePartial = new CountDownLatch(1);
    SerialListener listener =
        new SerialListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            partialEntered.countDown();
            await(releasePartial);
            super.onPartial(partial);
          }
        };
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    Thread deliveringPartial =
        new Thread(() -> fixture.receive(channel, progressEnvelope(CALL_ONE.toString(), "chunk")));
    deliveringPartial.start();
    await(partialEntered);

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));
    assertEquals(List.of(), listener.events());

    releasePartial.countDown();
    assertTrue(join(deliveringPartial), "drain owner must finish after the callback returns");
    assertEquals(List.of("partial:chunk", "complete:done"), listener.events());
  }

  /** 测试意图（C05）：callback 在核心锁外执行，因此回调内重入同一连接的 receive 不得死锁；重入产生的批次由同一 drainer 依次补投。 */
  @Test
  void reentrantReceiveInsideCallbackDoesNotDeadlock() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-reentrant-receive");
    AtomicBoolean reentered = new AtomicBoolean();
    SerialListener listener =
        new SerialListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            super.onPartial(partial);
            if (reentered.compareAndSet(false, true)) {
              fixture.receive(channel, progressEnvelope(CALL_ONE.toString(), "nested"));
            }
          }
        };
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    Thread driver =
        new Thread(() -> fixture.receive(channel, progressEnvelope(CALL_ONE.toString(), "outer")));
    driver.start();

    assertTrue(join(driver), "reentrant receive must not deadlock the drain owner");
    assertEquals(List.of("partial:outer", "partial:nested"), listener.events());
  }

  /**
   * 测试意图（C05）：单个 listener 抛异常不得阻断同一批次中其余 invocation 的终态投递，也不得让 drain 所有权滞留。
   *
   * <p>接管帧会在同一批次里依次回收两个在途调用：先投递的 listener 抛异常后，第二个调用仍须收到唯一终态；之后的帧仍能正常收敛。
   */
  @Test
  void listenerFailureDoesNotBlockRemainingTerminalCallbacks() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-listener-failure");
    AtomicInteger failures = new AtomicInteger();
    RecordingListener second = new RecordingListener();
    fixture.server.invoke(
        ENVIRONMENT_ID,
        capabilityRequest(CALL_ONE),
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {}

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {}

          @Override
          public void onError(Throwable error) {
            failures.incrementAndGet();
            throw new IllegalStateException("listener failed");
          }

          @Override
          public void onExitConfirmed() {}
        });
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_TWO), second);

    // 身份不同的新进程接管：两个在途调用在同一回调批次里以结果不确定收敛。
    fixture.leaseStore.activeLeaseToken = false;
    FakeControlChannel takeover =
        fixture.connectReady("channel-listener-failure-takeover", OTHER_INSTANCE_ID);

    assertEquals(1, failures.get(), "异常必须传播到测试可观察点而不是被吞掉");
    assertEquals(1, second.errorCount);
    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, second.error);
    assertTrue(takeover.isOpen());

    // drain 所有权已释放：接管后的新调用仍能正常收敛终态。
    UUID next = UUID.randomUUID();
    RecordingListener third = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(next), third);
    fixture.receive(takeover, completedEnvelope(next.toString(), "next"));
    assertEquals("next", third.completedText());
    assertFalse(takeover.closed());
  }

  /**
   * 测试意图（C05 跨代际边界）：新进程接管时，旧连接队列中「尚未开始」的 partial 必须在 drain 时被丢弃，不得在新连接的 terminal 之后投递给同一 listener。
   *
   * <p>已经在旧连接上进入 listener 的 partial（阻塞中的 chunk-1）是既成事实，本测试不对其做任何撤销承诺；它只精确断言旧连接 drain 期间排队的第二个
   * partial 不会在接管终态之后出现。单连接 FIFO 顺序域加上 drain 时刻的活动性复核即可保证这一点，无需跨连接排序机制。
   */
  @Test
  void takeoverDropsQueuedOldConnectionPartialInsteadOfDeliveringItAfterTerminal() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-takeover-partial-first");
    CountDownLatch partialEntered = new CountDownLatch(1);
    CountDownLatch releasePartial = new CountDownLatch(1);
    SerialListener listener =
        new SerialListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            super.onPartial(partial);
            partialEntered.countDown();
            await(releasePartial);
          }
        };
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    Thread oldDrainer =
        new Thread(() -> fixture.receive(first, progressEnvelope(CALL_ONE.toString(), "chunk-1")));
    oldDrainer.start();
    await(partialEntered);

    // 旧连接 drain 被阻塞期间排队第二个 partial：它尚未进入 listener。
    fixture.receive(first, progressEnvelope(CALL_ONE.toString(), "chunk-2"));
    assertEquals(List.of("partial:chunk-1"), listener.events());

    // 身份不同的新进程接管：旧连接的 invocation 恰好一次收敛为结果不确定，终态投递在新连接的顺序域内完成。
    fixture.leaseStore.activeLeaseToken = false;
    FakeControlChannel second =
        fixture.connectReady("channel-takeover-partial-second", OTHER_INSTANCE_ID);
    assertTrue(first.closed());
    assertEquals(
        List.of("partial:chunk-1", "error:EnvironmentCapabilitySendUncertainException"),
        listener.events());

    // 旧 drainer 恢复后必须丢掉排队的旧 partial，而不是把它补投在新 terminal 之后。
    releasePartial.countDown();
    assertTrue(join(oldDrainer));
    assertEquals(
        List.of("partial:chunk-1", "error:EnvironmentCapabilitySendUncertainException"),
        listener.events());
    assertTrue(second.isOpen());
  }

  /**
   * 测试意图（C05）：等待执行的回调批次达到每连接上界时必须 fail-closed —— 关闭连接，并把仍在途的调用一次性回收为结果不确定且不再随重连重放， 而不是让回调积压无界增长。
   */
  @Test
  void callbackBacklogOverflowClosesConnectionAndReclaimsInvocations() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-callback-overflow");
    CountDownLatch partialEntered = new CountDownLatch(1);
    CountDownLatch releasePartial = new CountDownLatch(1);
    SerialListener listener =
        new SerialListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            partialEntered.countDown();
            await(releasePartial);
            super.onPartial(partial);
          }
        };
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    Thread draining =
        new Thread(
            () -> fixture.receive(channel, progressEnvelope(CALL_ONE.toString(), "chunk-0")));
    draining.start();
    await(partialEntered);

    // drain 所有权被阻塞的线程持有：同一线程顺序投递即可把每连接队列推进到上界。
    for (int index = 1; index <= 256 && !channel.closed(); index++) {
      fixture.receive(channel, progressEnvelope(CALL_ONE.toString(), "chunk-" + index));
    }
    assertTrue(channel.closed(), "回调积压超限必须关闭连接");

    releasePartial.countDown();
    assertTrue(join(draining));
    // 未执行的批次不再投递 partial（调用已被回收），回收终态恰好一次且排在已投递的 partial 之后。
    assertEquals(
        List.of("partial:chunk-0", "error:EnvironmentCapabilitySendUncertainException"),
        listener.events());

    // 已回收的调用不再重放 INVOKE；未确认的退出事实只为同实例重放一次 CANCEL。
    FakeControlChannel reconnected = fixture.reconnectReady("channel-callback-overflow-retry");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.CANCEL), reconnected.messageTypes());
  }

  /**
   * 测试意图：本地出站队列拒绝（BUSY）不得被当成传输不确定 —— 调用方必须收到确定性的 busy 异常，连接保持可用，且该调用不残留在途目录 （同一 invocationId
   * 可以立即重新发起并成功）。
   */
  @Test
  void queueRejectionIsDeterministicBusyAndKeepsConnectionUsable() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-busy");

    channel.busyNextOffer = true;
    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));

    assertFalse(channel.closed());
    assertTrue(channel.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
    // 只有 SETTINGS：被拒绝的 INVOKE 没有产生任何 wire 帧。
    assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes());

    // 同一 invocationId 立即重试成功，证明拒绝未留下任何在途残留。
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), channel.messageTypes());
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));
    assertEquals("done", listener.completedText());
  }

  /**
   * 测试意图：物理连接丢失不得终结在途调用。同一 Daemon 进程（相同 daemonInstanceId）重连 READY 后，核心以相同 invocationId 发送不终止执行的
   * RECOVER（不重发 INVOKE、不发 CANCEL），调用保持未取消并继续运行，同一 invocation 的 daemon 终态照常派发。
   */
  @Test
  void sameDaemonInstanceReconnectRecoversActiveInvokeWithoutCancellingIt() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-instance-a");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), first.messageTypes());

    // 物理 socket 丢失：连接代际被清理，但在途调用必须保留。
    fixture.close(first.connectionId());
    assertTrue(first.closed());
    assertNull(listener.error);
    assertNull(listener.completed);
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));

    FakeControlChannel second = fixture.connectReady("channel-instance-b");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.RECOVER), second.messageTypes());
    Envelope recover =
        second.envelopes().stream()
            .filter(envelope -> envelope.getMessageType() == MessageType.RECOVER)
            .findFirst()
            .orElseThrow();
    assertEquals(CALL_ONE.toString(), recover.getInvocationId());
    assertEquals(0, second.countOf(MessageType.INVOKE), "恢复不得重新执行在途调用");
    assertEquals(0, second.countOf(MessageType.CANCEL), "控制连接抖动不得停止仍在运行的调用");
    assertFalse(handle.isCancelled(), "恢复不得替调用表达取消意图");
    assertEquals(0, listener.exitConfirmations, "恢复本身不得声称执行已经退出");
    // 每次 READY 都唤醒一次会话监听器：重连后 Environment 再次被登记为可用。
    assertEquals(List.of(ENVIRONMENT_ID, ENVIRONMENT_ID), fixture.readyNotifications);

    // 终态仍归属同一 invocation：RECOVER 回放 STARTED 后到达的终态只回调一次。
    fixture.receive(second, startedEnvelope(CALL_ONE, true));
    fixture.receive(second, completedEnvelope(CALL_ONE.toString(), "replayed"));
    fixture.receive(second, completedEnvelope(CALL_ONE.toString(), "duplicate"));
    assertEquals("replayed", listener.completedText());
    assertNull(listener.error);
    assertEquals(0, listener.exitConfirmations, "普通终态与重连不得自行确认退出");
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

    fixture.close("channel-old-instance");
    FakeControlChannel replacement =
        fixture.connectReady("channel-new-instance", OTHER_INSTANCE_ID);

    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, first.error);
    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, second.error);
    assertEquals(1, first.errorCount);
    assertEquals(1, second.errorCount);
    assertEquals(List.of(MessageType.SETTINGS), replacement.messageTypes());

    // 旧调用已终结：新连接上的迟到终态被忽略（tombstone），不再是新进程的越权回调。
    fixture.receive(replacement, completedEnvelope(CALL_ONE.toString(), "late"));
    assertNull(first.completed);
    assertEquals(1, first.errorCount);
    assertFalse(replacement.closed());
  }

  /** 测试意图：初次递交结果不确定的调用在同一 Daemon 进程重连后只收到 RECOVER，不重发 INVOKE、不被取消，结果事实仍归属原 listener。 */
  @Test
  void transportFailureKeepsInvocationActiveForSameInstanceRecovery() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-send-failure");
    RecordingListener listener = new RecordingListener();
    channel.failNextOffer = true;

    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    assertNotNull(handle);
    assertNull(listener.error);
    assertNull(listener.completed);
    assertTrue(channel.closed());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));

    // 同一实例重连：初次递交结果不确定，只发送 RECOVER，不重发 INVOKE；调用仍可收敛。
    FakeControlChannel reconnected = fixture.connectReady("channel-send-failure-retry");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.RECOVER), reconnected.messageTypes());
    assertEquals(0, reconnected.countOf(MessageType.INVOKE));
    assertFalse(handle.isCancelled());
    assertEquals(0, listener.exitConfirmations, "初次递交不确定的恢复不得声称执行已经退出");
    fixture.receive(reconnected, completedEnvelope(CALL_ONE.toString(), "done"));
    assertEquals("done", listener.completedText());
    assertNull(listener.error);
    assertEquals(0, listener.exitConfirmations, "普通终态不得自行确认退出");
  }

  /** 测试意图：恢复 RECOVER 被本地队列 BUSY 拒绝时不得记成已送达；关闭当前连接并保留在途调用，下一同实例代际重试 RECOVER，不重发 INVOKE。 */
  @Test
  void recoveryBusyClosesConnectionAndRetriesNextGeneration() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-recovery-busy-first");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    fixture.close(first.connectionId());

    FakeControlChannel second = fixture.openSession("channel-recovery-busy-second");
    second.busyNextOffer = true;
    fixture.receiveReady(second);

    assertTrue(second.closed(), "恢复 RECOVER 的 BUSY 必须关闭当前连接以触发既有重连");
    assertEquals(0, second.countOf(MessageType.RECOVER), "被拒绝的恢复 RECOVER 不得出现在 wire 上");
    assertFalse(handle.isCancelled(), "恢复不得替调用表达取消意图");
    assertNull(listener.error);
    assertNull(listener.completed);
    assertEquals(0, listener.exitConfirmations, "BUSY 拒绝的恢复不得声称执行已经退出");

    FakeControlChannel third = fixture.connectReady("channel-recovery-busy-third");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.RECOVER), third.messageTypes());
    assertEquals(0, third.countOf(MessageType.INVOKE));
    assertEquals(0, listener.exitConfirmations, "下一代 READY 补发 RECOVER 也不得自行确认退出");
  }

  /** 测试意图：恢复 RECOVER 递交抛错属于传输失败；关闭当前连接并保留在途调用，下一同实例代际重试 RECOVER，不重发 INVOKE。 */
  @Test
  void recoveryTransportFailureClosesConnectionAndRetriesNextGeneration() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-recovery-failure-first");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    fixture.close(first.connectionId());

    FakeControlChannel second = fixture.openSession("channel-recovery-failure-second");
    second.failNextOffer = true;
    fixture.receiveReady(second);

    assertTrue(second.closed(), "恢复 RECOVER 的传输失败必须关闭当前连接");
    assertEquals(0, second.countOf(MessageType.RECOVER), "抛错的恢复 RECOVER 不得出现在 wire 上");
    assertFalse(handle.isCancelled());
    assertNull(listener.error);
    assertNull(listener.completed);
    assertEquals(0, listener.exitConfirmations, "传输失败的恢复不得声称执行已经退出");

    FakeControlChannel third = fixture.connectReady("channel-recovery-failure-third");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.RECOVER), third.messageTypes());
    assertEquals(0, third.countOf(MessageType.INVOKE));
    assertEquals(0, listener.exitConfirmations, "下一代 READY 补发 RECOVER 也不得自行确认退出");
  }

  /**
   * 测试意图：FAILED 终态映射为明确异常并终结该 invocation；终态之后同一 invocationId 的迟到终结回调不再回调 listener，而是按 协议 contract
   * 判为越权回调并关闭连接。
   */
  @Test
  void terminalCallbackIsDeliveredOnceAndLateTerminalFrameIsRejected() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-terminal-once");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(channel, failedEnvelope(CALL_ONE.toString(), "boom"));
    assertInstanceOf(EnvironmentCapabilityFailedException.class, listener.error);
    assertEquals("boom", listener.error.getMessage());

    // 终态后 handle 不再发送 CANCEL 帧。
    int envelopesAfterTerminal = channel.envelopes().size();
    handle.cancel();
    assertEquals(envelopesAfterTerminal, channel.envelopes().size());

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "late"));
    assertNull(listener.completed);
    assertEquals(1, listener.errorCount);
    assertFalse(channel.closed());
  }

  /** 测试意图：daemon 主动 CANCELLED 终态映射为取消异常，并释放该 invocation 的关联。 */
  @Test
  void remoteCancelledMapsToCancelledException() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cancelled");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(channel, cancelledEnvelope(CALL_ONE.toString(), "stop"));

    assertInstanceOf(EnvironmentCapabilityCancelledException.class, listener.error);
    assertEquals("stop", listener.error.getMessage());
  }

  /** 测试意图：STARTED 允许空 payload 或 {@code replayed=true}，非法 replayed 值属于协议违规。 */
  @Test
  void startedAcceptsEmptyOrReplayedTrueAndRejectsOtherPayloads() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-started");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(channel, startedEnvelope(CALL_ONE.toString()));
    fixture.receive(channel, startedEnvelope(CALL_ONE, true));
    assertFalse(channel.closed());
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), channel.messageTypes());

    fixture.receive(
        channel,
        startedEnvelope(CALL_ONE).toBuilder()
            .setStarted(Started.newBuilder().setReplayed(false).build())
            .build());
    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
  }

  /**
   * 测试意图：显式 expire（调用方 deadline 路径）是终态收敛点：只发一次 CANCEL、移出在途目录（不再随重连重放），且随后到达的 daemon 终态不再回调
   * listener。
   */
  @Test
  void expireSendsCancelOnceAndSuppressesLaterTerminalCallback() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-expire");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.server.expire(handle);
    fixture.server.expire(handle);

    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.INVOKE, MessageType.CANCEL),
        channel.messageTypes());

    // 迟到终态属于已知 invocation：静默丢弃，不得再回调 listener。
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "late"));
    assertNull(listener.completed);
    assertNull(listener.error);
    assertFalse(channel.closed());

    // 已放弃的调用不再重放 INVOKE（否则会重新执行副作用）；未确认退出事实只为同实例重放 CANCEL。
    fixture.close(channel.connectionId());
    FakeControlChannel reconnected = fixture.connectReady("channel-expire-retry");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.CANCEL), reconnected.messageTypes());
  }

  /**
   * 测试意图：drain 之后本节点不再代表任何 Environment，调用方 expire 只做本地 retire 与 transfer 释放， 绝不向 Daemon 发送 CANCEL
   * 帧，使远端调用留给下一个 owner 重新挂接。
   */
  @Test
  void drainCausesLaterExpireToSuppressCancelFrame() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-drain-expire");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), channel.messageTypes());

    fixture.server.drain();
    assertTrue(channel.drainCount() > 0, "server.drain 必须调用 channel.drain");

    long countBeforeExpire = channel.countOf(MessageType.CANCEL);
    assertEquals(0L, countBeforeExpire, "drain 本身不产生 CANCEL");

    fixture.server.expire(handle);

    assertEquals(
        0L, channel.countOf(MessageType.CANCEL), "drain 之后 expire 绝不得向 Daemon 连接发送 CANCEL 帧");
  }

  /**
   * 测试意图（C03 反证）：`expire` 与 daemon 终态帧在同一 inventory 临界区竞争时，至多产生 0 或 1 个 listener 终态回调； 已经收敛的
   * invocation 其后续重放终态既不得再触发回调，也不得被判为协议错误关闭连接。
   *
   * <p>32 轮真实并发：barrier 同时放行 `expire(handle)`（调用方放弃路径）与 COMPLETED 帧（远端终态路径），覆盖两种胜者。
   */
  @Test
  void expireRacingTerminalFrameYieldsAtMostOneTerminalCallback() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-expire-terminal-race");
    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 32; round++) {
        UUID invocationId = UUID.randomUUID();
        RecordingListener listener = new RecordingListener();
        String completedText = "round-" + round;
        EnvironmentCapabilityExecutionHandle handle =
            fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(invocationId), listener);

        CyclicBarrier start = new CyclicBarrier(2);
        Future<?> expiring =
            racers.submit(
                () -> {
                  awaitStart(start);
                  fixture.server.expire(handle);
                });
        Future<?> terminating =
            racers.submit(
                () -> {
                  awaitStart(start);
                  fixture.receive(
                      channel, completedEnvelope(invocationId.toString(), completedText));
                });
        expiring.get();
        terminating.get();

        int terminals = (listener.completed == null ? 0 : 1) + listener.errorCount;
        assertTrue(terminals <= 1, "expire 与终态竞争只能产生 0 或 1 个终态回调: round=" + round);
        assertEquals(0, listener.partials.size(), "round=" + round);
        if (listener.completed != null) {
          assertEquals(completedText, listener.completedText());
        }

        // 已收敛的 invocation 不接受任何重放终态：没有第二次回调，也不会变成协议错误。
        fixture.receive(channel, failedEnvelope(invocationId.toString(), "late"));
        assertEquals(terminals, (listener.completed == null ? 0 : 1) + listener.errorCount);
        assertFalse(channel.closed());
      }
    } finally {
      racers.shutdownNow();
    }
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

          @Override
          public void terminate(EnvironmentCapabilityTerminationCause cause) {
            Objects.requireNonNull(cause, "cause");
          }

          @Override
          public Duration terminalDeliveryGrace() {
            return Duration.ZERO;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> fixture.server.expire(foreign));
  }

  /**
   * 测试意图：ActiveInvocation 在 invoke 时将 server 的 heartbeatTimeout 冻结为
   * terminalDeliveryGrace，且不受后续设置变化影响。
   */
  @Test
  void activeInvocationFreezesHeartbeatTimeoutAsTerminalDeliveryGraceAtInvokeTime() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-grace-freeze");
    EnvironmentCapabilityExecutionHandle firstHandle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    assertEquals(Duration.ofSeconds(60), firstHandle.terminalDeliveryGrace());

    fixture.settings.set(
        new EnvironmentServerSettings(Duration.ofSeconds(15), 16L * 1024 * 1024, 259200L, 1800L));
    EnvironmentCapabilityExecutionHandle secondHandle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_TWO), new RecordingListener());
    assertEquals(Duration.ofSeconds(15), secondHandle.terminalDeliveryGrace());
    assertEquals(
        Duration.ofSeconds(60),
        firstHandle.terminalDeliveryGrace(),
        "已签发的句柄必须保持 invoke 时冻结的 terminalDeliveryGrace");
  }

  /**
   * 测试意图：句柄 terminate(cause) 复用 expire 语义：向当前连接至多发一次 CANCEL、移出在途目录、登记 tombstone、释放全部
   * transfer；迟到终态帧静默丢弃。
   */
  @Test
  void terminateSendsCancelOnceAndSuppressesLateTerminalFrameAndReleasesTransfers() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-terminate");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    handle.terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
    handle.terminate(EnvironmentCapabilityTerminationCause.CANCELLED);

    assertTrue(handle.isCancelled());
    assertEquals(
        List.of(
            MessageType.SETTINGS,
            MessageType.INVOKE,
            MessageType.RESOURCE_UPLOAD_TICKET,
            MessageType.CANCEL),
        channel.messageTypes(),
        "terminate 必须向连接至多发送一次 CANCEL 帧");
    assertEquals(
        List.of(uploadId), fixture.ticketService.releases, "terminate 必须幂等释放名下 transfers 且恰好一次");

    // 迟到终态属于已知 invocation：静默丢弃，不得再回调 listener，连接不得被关闭。
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "late"));
    assertNull(listener.completed);
    assertNull(listener.error);
    assertFalse(channel.closed());
  }

  /**
   * 测试意图：未确认的 tombstone 只接受发起调用的 daemon 身份。不同实例既不能确认，也不会收到 CANCEL 重放； 原实例重连时收到一次 CANCEL 以拉取 journal
   * 事实，随后真实 ACK 交付一次，确认后不再重放。
   */
  @Test
  void lateExitConfirmationIsDeliveredOnceOnlyForTheOriginalDaemon() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-exit-late");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    handle.terminate(EnvironmentCapabilityTerminationCause.CANCELLED);

    fixture.close(channel.connectionId());
    FakeControlChannel other =
        fixture.connectReady("channel-exit-other-instance", OTHER_INSTANCE_ID);
    assertEquals(
        List.of(MessageType.SETTINGS), other.messageTypes(), "不同 daemon 身份不重放未确认退出确认的 CANCEL");
    fixture.receive(other, executionExitedEnvelope(CALL_ONE.toString()));
    assertEquals(0, listener.exitConfirmations, "其它 daemon 身份不能替原调用确认退出");
    assertNull(listener.error);
    assertFalse(other.closed());
    fixture.close(other.connectionId());

    FakeControlChannel reconnected =
        fixture.connectReady("channel-exit-original-retry", INSTANCE_ID);
    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.CANCEL),
        reconnected.messageTypes(),
        "原实例 READY 时对未确认 tombstone 只重放一次 CANCEL，不重放 INVOKE");
    fixture.receive(reconnected, executionExitedEnvelope(CALL_ONE.toString()));
    fixture.receive(reconnected, executionExitedEnvelope(CALL_ONE.toString()));
    assertEquals(1, listener.exitConfirmations);
    assertNull(listener.completed);
    assertNull(listener.error);
    assertFalse(reconnected.closed());

    fixture.close(reconnected.connectionId());
    FakeControlChannel afterConfirmed =
        fixture.connectReady("channel-exit-after-confirmed", INSTANCE_ID);
    assertEquals(
        List.of(MessageType.SETTINGS),
        afterConfirmed.messageTypes(),
        "已交付的退出确认不再随 READY 重放 CANCEL");
  }

  /** 账本确认抛错仍须留下脱敏诊断；重复 ACK 不重试失败写入，也不阻断后续调用的独立退出确认。 */
  @Test
  void exitConfirmationFailureIsLoggedSafelyWithoutBlockingLaterCallbacks() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-exit-failure");
    AtomicInteger attempts = new AtomicInteger();
    SerialListener failing =
        new SerialListener() {
          @Override
          public void onExitConfirmed() {
            attempts.incrementAndGet();
            throw new IllegalStateException("exit persistence failed: " + SYNTHETIC_SECRET);
          }
        };
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), failing);
    handle.terminate(EnvironmentCapabilityTerminationCause.CANCELLED);
    RecordingListener following = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_TWO), following);

    try (CoreLogCapture logs = new CoreLogCapture()) {
      fixture.receive(channel, executionExitedEnvelope(CALL_ONE.toString()));
      fixture.receive(channel, executionExitedEnvelope(CALL_ONE.toString()));
      fixture.receive(channel, executionExitedEnvelope(CALL_TWO.toString()));

      assertEquals(1, attempts.get(), "failed persistence must not be retried on a duplicate ACK");
      assertTrue(failing.events().isEmpty(), "a failed callback must not fabricate another result");
      assertEquals(1, following.exitConfirmations, "later callbacks must still drain");
      assertNull(following.completed);
      assertNull(following.error);
      assertFalse(channel.closed());
      logs.assertOnlySafeFailures(1, "execution exit confirmation");
      assertEquals(
          "environment daemon execution exit confirmation failed: IllegalStateException",
          logs.records().getFirst().getMessage());
    }
  }

  /**
   * 测试意图：终态帧与独立退出确认并发时，「移除活动调用 + 登记 tombstone」必须原子可见； 两个事实各自至多交付一次，且不能因活动/tombstone 之间的窗口被误判为未知
   * invocation。
   */
  @Test
  void terminalAndExitConfirmationRaceRemainsAtomic() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-terminal-exit-race");
    ExecutorService racers = Executors.newFixedThreadPool(2);
    // 清理失败作为 suppressed 保留，不能覆盖竞态主体的首个失败。
    try (AutoCloseable cleanup =
        () -> {
          racers.shutdownNow();
          assertTrue(
              racers.awaitTermination(5, TimeUnit.SECONDS), "race workers must exit in cleanup");
        }) {
      for (int round = 0; round < 32; round++) {
        UUID invocationId = UUID.randomUUID();
        RecordingListener listener = new RecordingListener();
        String completedText = "exit-race-round-" + round;
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(invocationId), listener);

        CyclicBarrier start = new CyclicBarrier(2);
        Future<?> completing =
            racers.submit(
                () -> {
                  awaitStart(start);
                  fixture.receive(
                      channel, completedEnvelope(invocationId.toString(), completedText));
                });
        Future<?> exiting =
            racers.submit(
                () -> {
                  awaitStart(start);
                  fixture.receive(channel, executionExitedEnvelope(invocationId.toString()));
                });
        completing.get(5, TimeUnit.SECONDS);
        exiting.get(5, TimeUnit.SECONDS);

        assertEquals(completedText, listener.completedText(), "round=" + round);
        assertEquals(1, listener.exitConfirmations, "round=" + round);
        assertNull(listener.error, "round=" + round);
        assertFalse(channel.closed(), "round=" + round);
      }
    }
  }

  /** 测试意图：普通 COMPLETED 不产生退出确认；终态之后真实 ACK 仍独立交付一次。 */
  @Test
  void lateExitConfirmationAfterCompletedIsIndependentFromTerminal() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-exit-after-completed");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));

    assertEquals("done", listener.completedText());
    assertEquals(0, listener.exitConfirmations);
    assertNull(listener.error);
    fixture.receive(channel, executionExitedEnvelope(CALL_ONE.toString()));
    assertEquals(1, listener.exitConfirmations);
    assertEquals(1, listener.completed.contents().size());
  }

  /**
   * 测试意图：有界 tombstone 淘汰最旧条目后，原 daemon 的迟到空 EXECUTION_EXITED 不再交付。不为淘汰身份另建无界表，因此该帧按未知 invocation
   * 关闭连接，但关闭本身不是退出证据。
   */
  @Test
  void evictedTombstoneDropsLateExitConfirmation() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-exit-evicted");
    RecordingListener oldest = new RecordingListener();
    fixture
        .server
        .invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), oldest)
        .terminate(EnvironmentCapabilityTerminationCause.CANCELLED);

    for (int index = 0; index < DaemonInvocationTracker.MAX_INVOCATION_TOMBSTONES; index++) {
      fixture
          .server
          .invoke(
              ENVIRONMENT_ID,
              capabilityRequest(new UUID(0L, 20_000L + index)),
              new RecordingListener())
          .terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
    }

    fixture.close(channel.connectionId());
    FakeControlChannel afterEvict = fixture.connectReady("channel-exit-evicted-retry");
    assertEquals(
        DaemonInvocationTracker.MAX_INVOCATION_TOMBSTONES,
        afterEvict.countOf(MessageType.CANCEL),
        "仍保留的未确认 tombstone 各重放一次 CANCEL");
    assertTrue(
        afterEvict.envelopes().stream()
            .noneMatch(
                envelope ->
                    envelope.getMessageType() == MessageType.CANCEL
                        && CALL_ONE.toString().equals(envelope.getInvocationId())),
        "被容量淘汰的 tombstone 不再重放 CANCEL");
    fixture.receive(afterEvict, executionExitedEnvelope(CALL_ONE.toString()));
    assertEquals(0, oldest.exitConfirmations, "容量淘汰后的迟到确认不再交付");
    assertNull(oldest.completed);
    assertNull(oldest.error);
    assertFalse(afterEvict.closed(), "淘汰后不再保留身份，未知退出确认被忽略且连接保持，且不交付 ACK");
  }

  /** 测试意图：容量淘汰优先已交付退出确认的条目；仍在等待确认的最旧条目被保留，其迟到 EXECUTION_EXITED 照常交付，连接不被关闭。 */
  @Test
  void tombstoneEvictionKeepsEntriesStillAwaitingExitConfirmation() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-exit-retained");
    RecordingListener awaiting = new RecordingListener();
    fixture
        .server
        .invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), awaiting)
        .terminate(EnvironmentCapabilityTerminationCause.CANCELLED);
    for (int index = 0; index < DaemonInvocationTracker.MAX_INVOCATION_TOMBSTONES; index++) {
      UUID callId = new UUID(0L, 30_000L + index);
      fixture
          .server
          .invoke(ENVIRONMENT_ID, capabilityRequest(callId), new RecordingListener())
          .terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
      fixture.receive(channel, executionExitedEnvelope(callId.toString()));
    }

    // 第 1025 个条目触发了淘汰：被淘汰的是退出确认已交付的条目，而不是仍在等待的 CALL_ONE。
    fixture.receive(channel, executionExitedEnvelope(CALL_ONE.toString()));
    assertEquals(1, awaiting.exitConfirmations, "仍在等待的条目在容量淘汰后照常交付退出确认");
    assertFalse(channel.closed());
  }

  /** 测试意图：活动调用的退出确认与结果终态分开，且非空 payload 不能当作确认。 */
  @Test
  void activeExitConfirmationIsSeparateFromTheTerminalResult() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-exit-active");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    // 正常 encoder 已拒绝非空退出 payload；从合法帧只替换消息类型，验证真实入站畸形帧仍关闭连接且不交付。
    Envelope malformed =
        Envelope.newBuilder()
            .setMessageType(MessageType.EXECUTION_EXITED)
            .setEnvironmentId(ENVIRONMENT_ID.value().toString())
            .setExecutionExited(ExecutionExited.getDefaultInstance())
            .build();
    fixture.receive(channel, malformed);
    assertTrue(channel.closed());
    assertEquals(0, listener.exitConfirmations);

    FakeControlChannel retried = fixture.reconnectReady("channel-exit-active-retry");
    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.RECOVER),
        retried.messageTypes(),
        "活动在途调用的恢复只发 RECOVER，不重发 INVOKE");
    assertEquals(0, retried.countOf(MessageType.INVOKE));
    assertEquals(0, listener.exitConfirmations, "恢复后、真实 EXECUTION_EXITED 前不得确认退出");
    fixture.receive(retried, executionExitedEnvelope(CALL_ONE.toString()));
    fixture.receive(retried, executionExitedEnvelope(CALL_ONE.toString()));
    assertEquals(1, listener.exitConfirmations);
    fixture.receive(retried, completedEnvelope(CALL_ONE.toString(), "done"));
    assertEquals("done", listener.completedText());
    assertEquals(1, listener.exitConfirmations);
    assertFalse(retried.closed());
  }

  /** 测试意图：terminate(cause) 终结调用后移出在途目录，late 重连绝不重放 INVOKE；未确认退出事实只重放 CANCEL。 */
  @Test
  void terminateSuppressesLateReconnectReplay() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-terminate-late-reconnect");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    handle.terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);

    fixture.close(channel.connectionId());
    FakeControlChannel reconnected = fixture.connectReady("channel-terminate-late-reconnect-retry");
    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.CANCEL),
        reconnected.messageTypes(),
        "已 terminate 的调用在 late 重连时绝不重放 INVOKE，只为未确认退出事实重放 CANCEL");

    fixture.receive(reconnected, completedEnvelope(CALL_ONE.toString(), "late-after-reconnect"));
    assertNull(listener.completed);
    assertNull(listener.error);
    assertFalse(reconnected.closed());
  }

  /** 测试意图：普通 cancel() 保持协作语义：不移出在途目录、不标记 tombstone，重连时在新连接上重放 CANCEL 帧，直到 daemon 给出明确终态。 */
  @Test
  void cancelPreservesCooperativeSemanticsAndReplaysOnReconnect() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-cancel-coop-first");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        first,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(first));

    handle.cancel();
    assertTrue(handle.isCancelled());
    // 协作式 cancel 暂不提前释放 transfer，等待 Daemon 终态
    assertTrue(fixture.ticketService.releases.isEmpty(), "普通 cancel 保持协作，不提前注销 transfer");

    fixture.close(first.connectionId());
    FakeControlChannel second = fixture.connectReady("channel-cancel-coop-second");
    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.CANCEL),
        second.messageTypes(),
        "重连时新连接必须重放 CANCEL 帧");

    fixture.receive(second, cancelledEnvelope(CALL_ONE.toString(), "cancelled by client"));
    assertInstanceOf(EnvironmentCapabilityCancelledException.class, listener.error);
    assertEquals("cancelled by client", listener.error.getMessage());
    assertEquals(List.of(uploadId), fixture.ticketService.releases, "终态到达时正常释放 transfer");
  }

  /** 测试意图：terminate(cause) 与 daemon 终态帧并发竞争时，至多产生 0 或 1 个 listener 终态回调，且不会引起协议违规关闭连接。 */
  @Test
  void terminateRacingTerminalFrameYieldsAtMostOneTerminalCallback() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-terminate-terminal-race");
    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 32; round++) {
        UUID invocationId = UUID.randomUUID();
        RecordingListener listener = new RecordingListener();
        String completedText = "terminate-round-" + round;
        EnvironmentCapabilityExecutionHandle handle =
            fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(invocationId), listener);

        CyclicBarrier start = new CyclicBarrier(2);
        Future<?> terminating =
            racers.submit(
                () -> {
                  awaitStart(start);
                  handle.terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
                });
        Future<?> completing =
            racers.submit(
                () -> {
                  awaitStart(start);
                  fixture.receive(
                      channel, completedEnvelope(invocationId.toString(), completedText));
                });
        terminating.get();
        completing.get();

        int terminals = (listener.completed == null ? 0 : 1) + listener.errorCount;
        assertTrue(terminals <= 1, "terminate 与终态竞争只能产生 0 或 1 个终态回调: round=" + round);
        assertEquals(0, listener.partials.size(), "round=" + round);
        if (listener.completed != null) {
          assertEquals(completedText, listener.completedText());
        }

        fixture.receive(channel, failedEnvelope(invocationId.toString(), "late"));
        assertEquals(terminals, (listener.completed == null ? 0 : 1) + listener.errorCount);
        assertFalse(channel.closed());
      }
    } finally {
      racers.shutdownNow();
    }
  }

  /** 测试意图：terminate 拒绝 null cause。 */
  @Test
  void terminateRejectsNullCause() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-terminate-null");
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    assertThrows(NullPointerException.class, () -> handle.terminate(null));
  }

  /** 测试意图：先 cancel 后 terminate 时保持幂等，当前代际不重复发送 CANCEL，且随后的迟到终态与重连依然被 tombstone 保护。 */
  @Test
  void cancelFollowedByTerminateSendsCancelOnceAndSuppressesReplay() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cancel-then-terminate");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    handle.cancel();
    assertEquals(1, channel.countOf(MessageType.CANCEL));

    handle.terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
    assertEquals(1, channel.countOf(MessageType.CANCEL), "当前代际已送达 CANCEL 时 terminate 不再重复发送");

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "late"));
    assertNull(listener.completed);
    assertNull(listener.error);

    fixture.close(channel.connectionId());
    FakeControlChannel reconnected = fixture.connectReady("channel-cancel-then-terminate-retry");
    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.CANCEL),
        reconnected.messageTypes(),
        "未确认退出事实只重放 CANCEL，绝不重放 INVOKE");
  }

  /**
   * 测试意图：并发调用 cancel() 与 terminate(cause) / expire() 时，受 active 正确锁保护， 向当前连接至多发送一次 CANCEL
   * 帧，绝不出现并发竞争导致重复发送两帧 CANCEL，且名下 transfer 与目录状态得到正确清理。
   */
  @Test
  void cancelAndTerminateRaceDeliversCancelAtMostOnce() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cancel-terminate-race");
    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 32; round++) {
        UUID invocationId = UUID.randomUUID();
        RecordingListener listener = new RecordingListener();
        EnvironmentCapabilityExecutionHandle handle =
            fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(invocationId), listener);

        UUID transferId = UUID.randomUUID();
        fixture.receive(
            channel,
            resourceUploadRequestEnvelope(
                invocationId.toString(), transferId, "text/plain", "race.txt", 4L, SHA_A));
        UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

        long cancelBefore = channel.countOf(MessageType.CANCEL);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<?> cancelTask =
            racers.submit(
                () -> {
                  awaitStart(barrier);
                  handle.cancel();
                });
        Future<?> terminateTask =
            racers.submit(
                () -> {
                  awaitStart(barrier);
                  handle.terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
                });
        cancelTask.get();
        terminateTask.get();

        long cancelAfter = channel.countOf(MessageType.CANCEL);
        assertEquals(
            1L,
            cancelAfter - cancelBefore,
            "cancel 与 terminate 并发竞争只能向连接发送恰好一次 CANCEL: round=" + round);
        assertTrue(handle.isCancelled(), "round=" + round);
        assertTrue(
            fixture.ticketService.releases.contains(uploadId),
            "名下 transfer 必须已被释放: round=" + round);

        fixture.receive(channel, completedEnvelope(invocationId.toString(), "late-" + round));
        assertNull(listener.completed);
        assertNull(listener.error);
        assertFalse(channel.closed());
      }
    } finally {
      racers.shutdownNow();
    }
  }

  /** 测试意图：并发多次调用 handle.cancel() 时，受 active 锁内原子判定与代际推进保护，向连接恰好递交一次 CANCEL，绝不重复发送。 */
  @Test
  void concurrentCancelsDeliverCancelExactlyOnce() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-concurrent-cancels");
    ExecutorService racers = Executors.newFixedThreadPool(4);
    try {
      for (int round = 0; round < 32; round++) {
        UUID invocationId = UUID.randomUUID();
        RecordingListener listener = new RecordingListener();
        EnvironmentCapabilityExecutionHandle handle =
            fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(invocationId), listener);

        long cancelBefore = channel.countOf(MessageType.CANCEL);
        CyclicBarrier barrier = new CyclicBarrier(4);
        List<Future<?>> tasks = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
          tasks.add(
              racers.submit(
                  () -> {
                    awaitStart(barrier);
                    handle.cancel();
                  }));
        }
        for (Future<?> task : tasks) {
          task.get();
        }

        long cancelAfter = channel.countOf(MessageType.CANCEL);
        assertEquals(
            1L, cancelAfter - cancelBefore, "并发多次 cancel 只能向连接发送恰好一次 CANCEL: round=" + round);
        assertTrue(handle.isCancelled());
      }
    } finally {
      racers.shutdownNow();
    }
  }

  /**
   * 测试意图：当调用线程处于已中断状态时调用 terminate(cause)，server 端清理不受中断干扰： 原子移除在途记录、登记 tombstone、向连接至多发送一次 CANCEL
   * 帧、释放全部 transfer； 调用线程的中断标记（interrupted flag）在调用后依然保持，不抛非预期异常；随后断开重连不重放。
   */
  @Test
  void interruptedThreadTerminateCleansUpSafelyAndPreservesInterruptStatus() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-interrupt-terminate");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "interrupt.txt", 4L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    AtomicBoolean wasInterrupted = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              handle.terminate(EnvironmentCapabilityTerminationCause.CANCELLED);
              wasInterrupted.set(Thread.currentThread().isInterrupted());
            },
            "interrupted-terminator");
    worker.start();
    assertTrue(join(worker), "worker 必须安全退出，不得被非预期异常打断");
    assertTrue(wasInterrupted.get(), "terminate 必须保持调用线程的中断状态");

    assertTrue(handle.isCancelled());
    assertEquals(1, channel.countOf(MessageType.CANCEL), "terminate 必须向连接发送至多一次 CANCEL 帧");
    assertEquals(List.of(uploadId), fixture.ticketService.releases, "名下 transfer 必须已被释放");

    // 迟到终态静默丢弃
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "late-after-interrupt"));
    assertNull(listener.completed);
    assertNull(listener.error);
    assertFalse(channel.closed());

    // 重连不重放 INVOKE；未确认退出事实只重放 CANCEL。
    fixture.close(channel.connectionId());
    FakeControlChannel reconnected = fixture.connectReady("channel-interrupt-terminate-retry");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.CANCEL), reconnected.messageTypes());
  }

  /**
   * 测试意图：当调用线程处于已中断状态时直接调用 server.expire(handle)，方法保持 void 返回且不向 listener 触发任何终态回调； 原子移除在途记录、登记
   * tombstone、向连接至多发送一次 CANCEL，释放 transfer 并保持调用线程的中断状态。
   */
  @Test
  void interruptedThreadExpireCleansUpSafelyAndPreservesInterruptStatus() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-interrupt-expire");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "interrupt-expire.txt", 4L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    AtomicBoolean wasInterrupted = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              fixture.server.expire(handle);
              wasInterrupted.set(Thread.currentThread().isInterrupted());
            },
            "interrupted-expirer");
    worker.start();
    assertTrue(join(worker), "worker 必须安全退出");
    assertTrue(wasInterrupted.get(), "expire 必须保持调用线程的中断状态");

    assertNull(listener.completed, "expire 绝不得向 listener 发送完成回调");
    assertNull(listener.error, "expire 绝不得向 listener 发送错误回调");
    assertEquals(1, channel.countOf(MessageType.CANCEL), "expire 必须向连接至多发送一次 CANCEL");
    assertEquals(List.of(uploadId), fixture.ticketService.releases, "名下 transfer 必须已被释放");

    // 迟到终态静默丢弃
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "late-after-expire"));
    assertNull(listener.completed);
    assertNull(listener.error);
    assertFalse(channel.closed());
  }

  /** 测试意图：CANCEL 被 BUSY 拒绝时不得记成已送达。调用保持取消意图，重连只补发 CANCEL，绝不重发 INVOKE；同一代际内再次 cancel 保持幂等。 */
  @Test
  void busyCancelIsRetriedAsCancelOnlyAfterReconnect() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cancel-busy");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    channel.busyNextOffer = true;
    handle.cancel();
    handle.cancel();

    assertTrue(handle.isCancelled());
    assertTrue(channel.closed(), "BUSY 的 CANCEL 必须关闭当前连接以触发既有重连");
    assertEquals(0, channel.countOf(MessageType.CANCEL), "被拒绝的 CANCEL 不得出现在 wire 上");

    FakeControlChannel reconnected = fixture.connectReady("channel-cancel-busy-retry");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.CANCEL), reconnected.messageTypes());
    assertEquals(0, reconnected.countOf(MessageType.INVOKE));

    handle.cancel();
    assertEquals(1, reconnected.countOf(MessageType.CANCEL), "当代已送达后再次 cancel 必须幂等");
  }

  /**
   * 测试意图：旧代际 CANCEL 在受控 offer 中实际 ACCEPTED 后连接被真实关闭；旧代际的接受不能抹掉新代际的取消意图。
   *
   * <p>closeConnectionState 与受控 offer 共用 state monitor，因此先确认关闭任务已经发起，再释放 offer，并通过有界 Future.get
   * 确认真实完成。isReady=false 只证明旧路由已移除，不是执行退出证据。
   */
  @Test
  void acceptedOldCancelDoesNotSuppressNextGenerationRecovery() throws Exception {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-cancel-generation");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    ExecutorService workers = Executors.newFixedThreadPool(2);
    // 先放行受控 offer 再真实等待线程池退出；清理失败不替换主体异常。
    try (AutoCloseable cleanup =
        () -> {
          first.releaseOffer();
          workers.shutdownNow();
          assertTrue(
              workers.awaitTermination(5, TimeUnit.SECONDS), "race workers must exit in cleanup");
        }) {
      first.holdNextOffer = true;
      Future<?> cancelling = workers.submit(handle::cancel);
      assertTrue(first.awaitOfferEntered(), "CANCEL 必须停在旧代际的递交窗口");

      CountDownLatch closeStarted = new CountDownLatch(1);
      Future<?> closing =
          workers.submit(
              () -> {
                closeStarted.countDown();
                fixture.close(first.connectionId());
              });
      assertTrue(closeStarted.await(5, TimeUnit.SECONDS), "关闭任务必须在有界时间内发起");

      // 关闭任务已发起；释放受控 offer 后分别等待两个任务的真实完成。
      first.releaseOffer();
      closing.get(5, TimeUnit.SECONDS);
      cancelling.get(5, TimeUnit.SECONDS);

      assertFalse(fixture.server.isReady(ENVIRONMENT_ID), "旧路由已移除；该事实不是执行退出证据");
      assertEquals(1, first.countOf(MessageType.CANCEL), "旧递交必须实际ACCEPTED，不能靠hold超时");

      FakeControlChannel second = fixture.connectReady("channel-cancel-generation-next");
      assertEquals(List.of(MessageType.SETTINGS, MessageType.CANCEL), second.messageTypes());
      assertEquals(1, second.countOf(MessageType.CANCEL));
      assertEquals(0, second.countOf(MessageType.INVOKE));
      assertTrue(handle.isCancelled());
      assertEquals(0, listener.exitConfirmations, "旧 CANCEL 接受与新 READY 恢复都不得确认退出");
    }
  }

  /** 测试意图：终态帧与实例接管同时从目录取走同一调用时，listener 恰好收到一次终态。后到者看到的是已经取出的调用，不得再发第二终态。 */
  @Test
  void terminalAndAbandonRaceDeliversExactlyOneOutcome() throws Exception {
    for (int index = 0; index < 32; index++) {
      int attempt = index;
      Fixture fixture = new Fixture();
      FakeControlChannel channel = fixture.connectReady("channel-terminal-race-" + attempt);
      RecordingListener listener = new RecordingListener();
      fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
      CountDownLatch started = new CountDownLatch(2);
      Thread terminal =
          new Thread(
              () -> {
                started.countDown();
                await(started);
                fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));
              },
              "terminal-racer");
      Thread abandon =
          new Thread(
              () -> {
                started.countDown();
                await(started);
                fixture.leaseStore.activeLeaseToken = false;
                fixture.connectReady("channel-terminal-takeover-" + attempt, OTHER_INSTANCE_ID);
              },
              "abandon-racer");
      terminal.start();
      abandon.start();
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertTrue(join(terminal));
      assertTrue(join(abandon));
      int outcomes = (listener.completed == null ? 0 : 1) + listener.errorCount;
      assertEquals(1, outcomes, "第 " + attempt + " 次竞争必须恰好一个终态");
    }
  }

  /** 测试意图：分别确定性覆盖两种先后顺序；竞争测试不依赖线程调度随机产生特定赢家分布。 */
  @Test
  void terminalAndAbandonEachWinWhenDeliveredFirst() {
    for (boolean terminalFirst : List.of(true, false)) {
      Fixture fixture = new Fixture();
      FakeControlChannel channel = fixture.connectReady("channel-ordered-" + terminalFirst);
      RecordingListener listener = new RecordingListener();
      fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
      Runnable terminal =
          () -> fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));
      Runnable abandon =
          () -> {
            fixture.leaseStore.activeLeaseToken = false;
            fixture.connectReady("channel-takeover-" + terminalFirst, OTHER_INSTANCE_ID);
          };
      if (terminalFirst) {
        terminal.run();
        abandon.run();
        assertEquals("done", listener.completedText());
        assertEquals(0, listener.errorCount);
      } else {
        abandon.run();
        terminal.run();
        assertNull(listener.completed);
        assertEquals(1, listener.errorCount);
      }
    }
  }

  /** 测试意图：SETTINGS 递交被拒绝时必须关闭该连接，不能停在已绑定但未完成握手的状态。 */
  @Test
  void rejectedSettingsClosesTheConnection() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = new FakeControlChannel("channel-settings-busy");
    channel.busyNextOffer = true;
    DaemonAdmission admission =
        fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID));
    if (admission instanceof DaemonAdmission.Admitted admitted) {
      fixture.server.open(admitted, channel, null);
    }

    assertTrue(channel.closed(), "SETTINGS 未进入传输时必须关闭连接");
    assertEquals(0, channel.countOf(MessageType.SETTINGS));
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：cancel 幂等，终态后 cancel 不再产生 CANCEL 帧。 */
  @Test
  void cancelIsIdempotentAndSkippedAfterTerminal() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cancel-idempotent");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    handle.cancel();
    handle.cancel();
    assertTrue(handle.isCancelled());
    assertEquals(1, channel.countOf(MessageType.CANCEL));

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));
    int afterTerminal = channel.envelopes().size();
    handle.cancel();
    assertEquals(afterTerminal, channel.envelopes().size());
    assertEquals("done", listener.completedText());
  }

  /** 测试意图：同 connectionId 的新连接代际必须幂等清理旧代际，但不终结其保留的在途调用。 */
  @Test
  void reopeningSameConnectionIdCleansUpPreviousGeneration() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-same-id");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    FakeControlChannel second = fixture.connectReady("channel-same-id");

    assertTrue(first.closed());
    assertNull(listener.error);
    assertEquals(List.of(MessageType.SETTINGS, MessageType.RECOVER), second.messageTypes());
    assertEquals(0, second.countOf(MessageType.INVOKE), "旧代际在途调用只经 RECOVER 取回");
    assertTrue(second.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：旧连接代际被新握手准入抢占后，旧代际的迟到帧不得影响新代际。 */
  @Test
  void staleGenerationFramesAreIgnoredAfterTakeover() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-first");
    RecordingListener firstListener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), firstListener);

    // 旧持有者被判定为已失效（例如租约过期后同一环境重新握手建连）。
    FakeControlChannel second = fixture.reconnectReady("channel-second");

    assertTrue(first.closed());
    assertNull(firstListener.error);
    assertEquals(List.of(MessageType.SETTINGS, MessageType.RECOVER), second.messageTypes());
    assertEquals(0, second.countOf(MessageType.INVOKE), "旧代际在途调用只经 RECOVER 取回");

    // 旧代际已解绑：迟到帧被直接丢弃，不产生协议错误帧，也不影响新代际。
    int secondEnvelopes = second.envelopes().size();
    fixture.receive(first, heartbeatEnvelope());
    assertEquals(secondEnvelopes, second.envelopes().size());
    assertTrue(second.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：READY 之后租约失效（fence lost）必须使后续 HEARTBEAT 以协议错误关闭连接。 */
  @Test
  void heartbeatFenceLossClosesConnection() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-fence-lost");
    fixture.leaseStore.heartbeatResult = false;

    fixture.receive(channel, heartbeatEnvelope());

    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
  }

  /** 测试意图：临时资源策略在心跳上推送，未变化时不产生冗余帧。 */
  @Test
  void heartbeatPushesUpdatedTemporaryResourcePolicyToReadyConnection() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-policy-hot");
    assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes(), "建连只通告一次策略");

    // 设置未变：心跳不得产生策略帧。
    fixture.receive(channel, heartbeatEnvelope());
    assertEquals(0, channel.countOf(MessageType.TEMPORARY_RESOURCE_POLICY));

    // 设置热更后，下一次心跳把当前策略推送给已 READY 连接。
    fixture.updateSettings(1L, 2L);
    fixture.receive(channel, heartbeatEnvelope());

    assertEquals(1, channel.countOf(MessageType.TEMPORARY_RESOURCE_POLICY));
    Envelope policy =
        channel.envelopes().stream()
            .filter(envelope -> envelope.getMessageType() == MessageType.TEMPORARY_RESOURCE_POLICY)
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals(ENVIRONMENT_ID.value().toString(), policy.getEnvironmentId());
    assertEquals(1L, policy.getTemporaryResourcePolicy().getTemporaryResourceTtlSeconds());
    assertEquals(
        2L, policy.getTemporaryResourcePolicy().getTemporaryResourceCleanupIntervalSeconds());

    // 已送达的策略不再重复推送。
    fixture.receive(channel, heartbeatEnvelope());
    assertEquals(1, channel.countOf(MessageType.TEMPORARY_RESOURCE_POLICY));
  }

  /** 覆盖初始下发、通知对账与同版本去重：SETTINGS 之后发送完整 ENVIRONMENT_CONFIG，版本推进才重发。 */
  @Test
  void environmentConfigFollowsInitialAndNotifications() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("config-initial");
    assertEquals(
        List.of(MessageType.SETTINGS, MessageType.ENVIRONMENT_CONFIG), channel.allMessageTypes());
    assertEquals(0L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
    assertEquals(
        "https://studio.example.org",
        channel.lastEnvelope().getEnvironmentConfig().getConfig().getStudioUrl());

    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 3L));
    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertEquals(2, channel.countOf(MessageType.ENVIRONMENT_CONFIG));
    assertEquals(3L, channel.lastEnvelope().getEnvironmentConfig().getVersion());

    fixture.server.resyncEnvironmentConfigs();
    fixture.receive(channel, heartbeatEnvelope());
    assertEquals(2, channel.countOf(MessageType.ENVIRONMENT_CONFIG), "同版本不重复下发");
    assertTrue(channel.isOpen());
  }

  /** 源查询失败保留连接和当前快照；下一心跳恢复后追平权威版本，不扩展重试框架。 */
  @Test
  void environmentConfigSourceFailureKeepsSessionAndHeartbeatRetries() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("config-source-failure");
    long before = channel.countOf(MessageType.ENVIRONMENT_CONFIG);

    fixture.configLookupFailure = new IllegalStateException("config source unavailable");
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 2L));
    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertTrue(channel.isOpen());
    assertEquals(before, channel.countOf(MessageType.ENVIRONMENT_CONFIG));

    fixture.configLookupFailure = null;
    fixture.receive(channel, heartbeatEnvelope());
    assertEquals(before + 1, channel.countOf(MessageType.ENVIRONMENT_CONFIG));
    assertEquals(2L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  /** 卡片或配置缺失时必须明确关闭连接，不能把旧配置当成仍有效。 */
  @Test
  void missingEnvironmentConfigClosesExplicitly() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("config-missing-live");
    fixture.configs.remove(ENVIRONMENT_ID);
    fixture.server.environmentChanged(ENVIRONMENT_ID);

    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());

    Fixture initialMissing = new Fixture();
    initialMissing.configs.remove(ENVIRONMENT_ID);
    FakeControlChannel missing = initialMissing.openSession("config-missing-initial");
    assertTrue(missing.closed());
    assertEquals(List.of(MessageType.SETTINGS, MessageType.ERROR), missing.allMessageTypes());
  }

  /** 入队失败必须关闭连接；正常重连由初始下发读取最新权威版本。 */
  @Test
  void environmentConfigOfferFailureClosesAndReconnectGetsLatest() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("config-offer-failure");
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 4L));
    channel.busyNextOffer = true;

    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertTrue(channel.closed());

    fixture.leaseStore.activeLeaseToken = false;
    FakeControlChannel reconnected = fixture.connectReady("config-reconnected");
    assertTrue(reconnected.isOpen());
    assertEquals(4L, reconnected.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  /** 一个连接的配置下发失败不阻碍其它本机会话对账。 */
  @Test
  void failedEnvironmentConfigDoesNotStopOtherSessions() {
    Fixture fixture = new Fixture();
    FakeControlChannel failed = fixture.openSession("config-failed");
    FakeControlChannel healthy =
        fixture.connectReady(OTHER_ENVIRONMENT_ID, "config-healthy", INSTANCE_ID, OTHER_TOKEN);
    failed.busyNextOffer = true;
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 5L));

    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertTrue(failed.closed());
    assertTrue(healthy.isOpen());
    assertEquals(0L, healthy.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  /** 测试意图：READY 时围栏已失效不得进入 READY，必须按协议错误关闭连接。 */
  @Test
  void readyFenceLossClosesConnection() {
    Fixture fixture = new Fixture();
    fixture.leaseStore.markReadyResult = false;
    FakeControlChannel channel = fixture.openSession("channel-ready-fence");
    fixture.receiveReady(channel);

    assertTrue(channel.closed());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：未 READY 或租约已失效时 INVOKE 必须在发送前失败，且不产生 wire 帧。 */
  @Test
  void invokeRequiresLiveReadyLease() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("channel-not-ready");
    int afterOpen = channel.envelopes().size();
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(afterOpen, channel.envelopes().size());

    fixture.receiveReady(channel);
    fixture.leaseStore.holdsReadyLease = false;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(afterOpen, channel.envelopes().size());
  }

  /** 测试意图：租约存储不可用时 INVOKE 必须 fail-closed 且不发送 wire 帧。 */
  @Test
  void invokeFailsClosedWhenLeaseStoreIsUnavailable() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-db-down");
    fixture.leaseStore.databaseUnavailable = true;

    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
    assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes());
  }

  /** 测试意图：未注册环境的调用立即不可用，descriptor 漂移与非法 callId 都在发送前拒绝。 */
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
            EnvironmentCapabilityIds.FS_READ, descriptor().inputSchema(), Duration.ofSeconds(5));
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

  /** 测试意图：必填 workdir（process.exec）在 frame send 前按该连接 READY 中冻结的 OS 做词法校验。 */
  @Test
  void validatesWorkdirAgainstReadyOperatingSystemBeforeFrameSend() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-workdir-validation");

    // 缺失 workdir / 相对 workdir / Windows drive 形态（该连接冻结的是 LINUX）都在发送前拒绝。
    // 注意 `/srv/../repo` 仍是形状合法的 Unix 绝对路径：词法校验不折叠 `..`，越界事实由 Daemon 自身 Path 与 permission 处理。
    String[] rejectedArguments = {
      "{\"command\":\"ls\"}",
      "{\"command\":\"ls\",\"workdir\":\"relative/dir\"}",
      "{\"command\":\"ls\",\"workdir\":\"C:\\\\repo\"}",
      "{\"command\":\"ls\",\"workdir\":\" /srv/repo\"}"
    };
    for (String arguments : rejectedArguments) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.server.invoke(
                  ENVIRONMENT_ID,
                  processExecRequestWith(arguments, CALL_ONE),
                  new RecordingListener()),
          "必须在发送前拒绝: " + arguments);
    }
    // 全部被拒：通道上除了 SETTINGS 没有任何 INVOKE 帧。
    assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes());

    // 显式绝对 workdir 通过校验并产生 INVOKE 帧。
    fixture.server.invoke(
        ENVIRONMENT_ID,
        processExecRequestWith("{\"command\":\"ls\",\"workdir\":\"/srv/repo\"}", CALL_ONE),
        new RecordingListener());
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), channel.messageTypes());
  }

  /** 文件/LSP 能力的路径约定必须穿透发送前门禁，不能只在 Daemon 直调用时成立。 */
  @Test
  void forwardsAbsolutePathsWithoutWorkdirForEveryPathCapability() {
    for (EnvironmentCapabilityDescriptor descriptor : EnvironmentCapabilityCatalog.descriptors()) {
      if (!descriptor.inputSchema().properties().containsKey("path")) {
        continue;
      }
      Fixture fixture = new Fixture();
      FakeControlChannel channel = fixture.connectReady("path-" + descriptor.id().value());
      String fields =
          switch (descriptor.id().value()) {
            case "fs.write" -> ",\"content\":\"text\"";
            case "fs.edit", "fs.edit.preview" -> ",\"old_string\":\"old\",\"new_string\":\"new\"";
            case "fs.find", "fs.grep" -> ",\"pattern\":\"text\"";
            case "lsp.goto-definition" -> ",\"line\":1";
            case "lsp.workspace-symbols" -> ",\"query\":\"Symbol\"";
            case "lsp.java-decompile" -> ",\"target\":\"jdt://contents/library/Foo.class\"";
            default -> "";
          };
      // 携带未定义的 extra 属性在发送前被 Schema 校验拒绝
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.server.invoke(
                  ENVIRONMENT_ID,
                  new EnvironmentCapabilityExecutionRequest(
                      descriptor,
                      new EnvironmentCapabilityCall(
                          CALL_ONE.toString(),
                          "{\"path\":\"/srv/repo/App.java\",\"extra\":\"unexpected\""
                              + fields
                              + "}"),
                      Duration.ofSeconds(5)),
                  new RecordingListener()),
          descriptor.id().value());
      assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes());

      // 绝对路径通过校验并产生 INVOKE 帧发往 Daemon
      fixture.server.invoke(
          ENVIRONMENT_ID,
          new EnvironmentCapabilityExecutionRequest(
              descriptor,
              new EnvironmentCapabilityCall(
                  CALL_ONE.toString(), "{\"path\":\"/srv/repo/App.java\"" + fields + "}"),
              Duration.ofSeconds(5)),
          new RecordingListener());
      assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), channel.messageTypes());

      // 相对路径不做发送前跨 OS 校验，透传产生 INVOKE 帧发往 Daemon，由 Daemon 自身判定并拒绝
      RecordingListener relativeListener = new RecordingListener();
      fixture.server.invoke(
          ENVIRONMENT_ID,
          new EnvironmentCapabilityExecutionRequest(
              descriptor,
              new EnvironmentCapabilityCall(
                  CALL_TWO.toString(), "{\"path\":\"src/App.java\"" + fields + "}"),
              Duration.ofSeconds(5)),
          relativeListener);
      assertEquals(
          List.of(MessageType.SETTINGS, MessageType.INVOKE, MessageType.INVOKE),
          channel.messageTypes());
      fixture.receive(
          channel,
          failedEnvelope(CALL_TWO.toString(), "path must be an absolute path: src/App.java"));
      assertInstanceOf(EnvironmentCapabilityFailedException.class, relativeListener.error);
      assertEquals(
          "path must be an absolute path: src/App.java", relativeListener.error.getMessage());
    }
  }

  /** 测试意图：并发握手准入抢占同一环境时只有一个连接获得准入并收到 SETTINGS，另一个以 503 Retry-After（暂不可准入）拒绝。 */
  @Test
  void concurrentHandshakeForActiveRouteYieldsRetryLater() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-held");
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));

    DaemonAdmission admission =
        fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, OTHER_INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.RetryLater.class, admission);
    assertTrue(first.isOpen());

    // 旧持有者必须仍然可服务调用。
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), first.messageTypes());
  }

  /** 测试意图：租约存储判定旧 leaseToken 不再活跃时，新握手建连必须直接接管而非 503 Retry-After。 */
  @Test
  void staleHolderTokenIsTakenOverByFreshHandshake() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-stale-holder");
    FakeControlChannel takeover = fixture.reconnectReady("channel-takeover");
    assertTrue(takeover.isOpen());
    assertEquals(List.of(MessageType.SETTINGS), takeover.messageTypes());
  }

  /** 测试意图：未知注册凭据必须以 401 准入拒绝，不占用路由且不回显凭据。 */
  @Test
  void unknownRegistrationTokenIsRejected() {
    Fixture fixture = new Fixture();
    DaemonAdmission admission =
        fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, "wrong-token", INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Rejected.class, admission);
    assertFalse(fixture.leaseStore.acquired);
  }

  /** 测试意图：租约存储判定凭据无效或路由活跃时，分别裁决为准入拒绝（401）与暂不可准入（503）。 */
  @Test
  void leaseStoreRejectionAndRetryLaterMapToAdmissionOutcomes() {
    Fixture rejected = new Fixture();
    rejected.leaseStore.rejected = true;
    DaemonAdmission rejectedAdmission =
        rejected.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Rejected.class, rejectedAdmission);

    Fixture retryLater = new Fixture();
    retryLater.leaseStore.retryLater = true;
    DaemonAdmission retryLaterAdmission =
        retryLater.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.RetryLater.class, retryLaterAdmission);
  }

  /** 测试意图：租约存储不可用时握手准入必须 fail-closed 抛出异常拒绝建连。 */
  @Test
  void handshakeFailsClosedWhenLeaseStoreIsUnavailable() {
    Fixture fixture = new Fixture();
    fixture.leaseStore.databaseUnavailable = true;
    assertThrows(
        IllegalStateException.class,
        () -> fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID)));
  }

  /** 测试意图：注册凭据目录抛异常时握手准入必须 fail-closed 抛出异常，不建立任何绑定。 */
  @Test
  void handshakeFailsClosedWhenRegistrationDirectoryFails() {
    Fixture fixture = new Fixture();
    fixture.failRegistrationDirectory();
    assertThrows(
        IllegalStateException.class,
        () -> fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID)));
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：CONNECTING 心跳只续租并缓存运行状态/配置错误，不触发 READY 回调或执行路由。 */
  @Test
  void heartbeatBeforeReadyRenewsLeaseWithoutEnablingExecution() {
    Fixture heartbeatFirst = new Fixture();
    FakeControlChannel heartbeatChannel = heartbeatFirst.openSession("channel-heartbeat-first");
    heartbeatFirst.receive(
        heartbeatChannel, heartbeatEnvelope(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));

    assertTrue(heartbeatChannel.isOpen());
    assertEquals(1, heartbeatFirst.leaseStore.heartbeatCount);
    assertTrue(heartbeatFirst.heartbeatNotifications.isEmpty());
    assertEquals(1L, heartbeatFirst.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().revision());
    assertFalse(heartbeatFirst.server.isReady(ENVIRONMENT_ID));
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            heartbeatFirst.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));
  }

  /** 测试意图：重复 READY 或握手参数非法（token/实例身份非法）都必须被拒绝。 */
  @Test
  void handshakeValidationRejectsMalformedHandshakes() {
    // 重复 READY 是协议违规：协议没有可重放的序号，READY 只能推进一次会话事实。
    Fixture duplicateReady = new Fixture();
    FakeControlChannel readyChannel = duplicateReady.connectReady("channel-duplicate-ready");
    duplicateReady.receiveReady(readyChannel);

    assertTrue(readyChannel.closed());
    assertEquals(List.of(MessageType.SETTINGS, MessageType.ERROR), readyChannel.messageTypes());

    assertThrows(NullPointerException.class, () -> new DaemonHandshake(null, TOKEN, INSTANCE_ID));
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonHandshake(ENVIRONMENT_ID, "", INSTANCE_ID));
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonHandshake(ENVIRONMENT_ID, TOKEN, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonHandshake(ENVIRONMENT_ID, TOKEN, "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonHandshake(ENVIRONMENT_ID, TOKEN, "{" + INSTANCE_ID + "}"));
  }

  /** 测试意图：注册凭据所属卡片与握手声明的 Environment 不一致时必须拒绝，不能串用其它卡片路由。 */
  @Test
  void handshakeEnvironmentMustMatchRegistrationToken() {
    Fixture fixture = new Fixture();
    DaemonAdmission admission =
        fixture.server.admit(new DaemonHandshake(OTHER_ENVIRONMENT_ID, TOKEN, INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.Rejected.class, admission);
    assertFalse(fixture.leaseStore.acquired);
  }

  /** 测试意图：绑定连接上的 environmentId 不匹配、或者消息类型方向错误，都以协议错误关闭连接。 */
  @Test
  void boundConnectionRejectsMismatchedScopeAndWrongDirection() {
    Fixture mismatched = new Fixture();
    FakeControlChannel mismatchedChannel = mismatched.connectReady("channel-scope-mismatch");
    mismatched.receive(
        mismatchedChannel,
        heartbeatEnvelope(
            OTHER_ENVIRONMENT_ID, 1L, new DaemonRuntimeStatus(DAEMON_VERSION, null), null));
    assertTrue(mismatchedChannel.closed());

    // 方向错误：daemon 不得向服务端发送 INVOKE。
    Fixture direction = new Fixture();
    FakeControlChannel directionChannel = direction.connectReady("channel-wrong-direction");
    direction.receive(
        directionChannel,
        Envelope.newBuilder()
            .setMessageType(MessageType.INVOKE)
            .setEnvironmentId(ENVIRONMENT_ID.value().toString())
            .setInvocationId(CALL_ONE.toString())
            .build());
    assertTrue(directionChannel.closed());
  }

  /** 测试意图：HEARTBEAT 作用域不匹配或重复 READY 属于会话协议违规，立即关闭连接。 */
  @Test
  void heartbeatMismatchAndDuplicateReadyAreRejected() {
    Fixture heartbeatExtra = new Fixture();
    FakeControlChannel extraChannel = heartbeatExtra.connectReady("channel-heartbeat-extra");
    heartbeatExtra.receive(
        extraChannel,
        heartbeatEnvelope(
            OTHER_ENVIRONMENT_ID, 1L, new DaemonRuntimeStatus(DAEMON_VERSION, null), null));
    assertTrue(extraChannel.closed());

    Fixture duplicateReady = new Fixture();
    FakeControlChannel invocationChannel = duplicateReady.connectReady("channel-ready-duplicate");
    duplicateReady.receive(
        invocationChannel, readyEnvelope(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
    assertTrue(invocationChannel.closed());
  }

  /**
   * 测试意图：受管更新期间普通 capability 调用在起点被 busy 拒绝，结束更新后恢复；同一 operation 重复准入幂等、不同 operation 被拒绝，保证「一个
   * Environment 同一时刻至多一次更新」。
   */
  @Test
  void updateAdmissionBlocksOrdinaryInvocationsUntilReleased() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-update-admission");
    String operationId = UUID.randomUUID().toString();

    fixture.server.beginUpdate(ENVIRONMENT_ID, operationId);
    assertTrue(fixture.server.isUpdating(ENVIRONMENT_ID));
    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));

    fixture.server.beginUpdate(ENVIRONMENT_ID, operationId);
    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.beginUpdate(ENVIRONMENT_ID, UUID.randomUUID().toString()));

    fixture.server.endUpdate(ENVIRONMENT_ID, operationId);
    assertFalse(fixture.server.isUpdating(ENVIRONMENT_ID));

    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), channel.messageTypes());
  }

  /** 测试意图：存在在途普通调用时不得开始更新（busy），避免替换二进制打断执行。 */
  @Test
  void updateAdmissionRejectsWhenInvocationInFlight() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-update-inflight");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.beginUpdate(ENVIRONMENT_ID, UUID.randomUUID().toString()));
    assertFalse(fixture.server.isUpdating(ENVIRONMENT_ID));
  }

  /** 测试意图：更新命令只在同一 operation 已准入时下发；下发即写出 UPDATE 帧，非活动 operation 被 busy 拒绝。 */
  @Test
  void sendUpdateRequiresActiveOperationAndWritesUpdateFrame() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-send-update");
    String operationId = UUID.randomUUID().toString();
    DaemonUpdateCommand command = new DaemonUpdateCommand(operationId, "1.0.9");

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.sendUpdate(ENVIRONMENT_ID, command));

    fixture.server.beginUpdate(ENVIRONMENT_ID, operationId);
    fixture.server.sendUpdate(ENVIRONMENT_ID, command);
    assertEquals(List.of(MessageType.SETTINGS, MessageType.UPDATE), channel.messageTypes());
    Envelope update =
        channel.envelopes().stream()
            .filter(envelope -> envelope.getMessageType() == MessageType.UPDATE)
            .findFirst()
            .orElseThrow();
    assertEquals(operationId, update.getUpdate().getOperationId());
    assertEquals("1.0.9", update.getUpdate().getTargetVersion());
    assertEquals(2, update.getUpdate().getAllFields().size());

    channel.busyNextOffer = true;
    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.sendUpdate(ENVIRONMENT_ID, command));

    fixture.close(channel);
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.sendUpdate(ENVIRONMENT_ID, command));
  }

  /** 测试意图：daemon 上报的受管更新阶段回执只做协议校验后转交宿主；daemon 不得反向发送 UPDATE 命令。 */
  @Test
  void updateResultIsDeliveredToHostAndUpdateCommandFromDaemonIsRejected() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-update-result");
    String operationId = UUID.randomUUID().toString();

    fixture.receive(channel, updateResultEnvelope(UUID.fromString(operationId), true, null));
    fixture.receive(
        channel,
        updateResultEnvelope(UUID.fromString(operationId), UpdatePhase.UPDATE_ACCEPTED, null));
    fixture.receive(
        channel,
        updateResultEnvelope(UUID.fromString(operationId), UpdatePhase.UPDATE_FAILED, "failed"));

    assertFalse(channel.closed());
    assertEquals(
        List.of(ENVIRONMENT_ID, ENVIRONMENT_ID, ENVIRONMENT_ID), fixture.updateResultEnvironments);
    assertEquals(DaemonUpdatePhase.PREPARED, fixture.updateResults.get(0).phase());
    assertEquals(DaemonUpdatePhase.ACCEPTED, fixture.updateResults.get(1).phase());
    assertEquals(DaemonUpdatePhase.FAILED, fixture.updateResults.get(2).phase());
    assertEquals("failed", fixture.updateResults.get(2).message());

    fixture.receive(
        channel,
        Envelope.newBuilder()
            .setMessageType(MessageType.UPDATE)
            .setEnvironmentId(ENVIRONMENT_ID.value().toString())
            .build());
    assertTrue(channel.closed());
  }

  /** 测试意图：不认识的调用帧（既不在活动表也不在 tombstone）一律忽略并保持连接，不得判定为协议违规断开。 */
  @Test
  void unknownInvocationFramesAreIgnoredAndConnectionStaysOpen() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-unknown-invocation");
    UUID unknownId = UUID.randomUUID();

    // PROGRESS、终态（COMPLETED/FAILED/CANCELLED）、RECOVER_NOT_FOUND、EXECUTION_EXITED
    fixture.receive(channel, progressEnvelope(unknownId, "chunk"));
    assertFalse(channel.closed(), "未知 PROGRESS 必须被忽略且连接保持");

    fixture.receive(channel, completedEnvelope(unknownId.toString(), "done"));
    assertFalse(channel.closed(), "未知 COMPLETED 必须被忽略且连接保持");

    fixture.receive(channel, failedEnvelope(unknownId.toString(), "err"));
    assertFalse(channel.closed(), "未知 FAILED 必须被忽略且连接保持");

    fixture.receive(channel, cancelledEnvelope(unknownId.toString(), "cancel"));
    assertFalse(channel.closed(), "未知 CANCELLED 必须被忽略且连接保持");

    fixture.receive(channel, recoverNotFoundEnvelope(unknownId.toString()));
    assertFalse(channel.closed(), "未知 RECOVER_NOT_FOUND 必须被忽略且连接保持");

    fixture.receive(channel, executionExitedEnvelope(unknownId.toString()));
    assertFalse(channel.closed(), "未知 EXECUTION_EXITED 必须被忽略且连接保持");
  }

  /**
   * 测试意图：回调只按 invocationId 归属，不按连接代际 —— 同一 Daemon 进程重连以 RECOVER 取回（不重发
   * INVOKE）后，新连接仍是这些在途调用的合法持有者，其终态必须被派发。
   */
  @Test
  void reconnectedSameInstanceOwnsCallbackForRecoveredInvocation() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-owner");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    FakeControlChannel reconnected = fixture.reconnectReady("channel-reconnected");
    assertEquals(List.of(MessageType.SETTINGS, MessageType.RECOVER), reconnected.messageTypes());
    assertEquals(0, reconnected.countOf(MessageType.INVOKE));
    assertEquals(0, listener.exitConfirmations, "恢复本身不得声称执行已经退出");
    fixture.receive(reconnected, completedEnvelope(CALL_ONE.toString(), "x"));

    assertFalse(reconnected.closed());
    assertEquals("x", listener.completedText());
    assertNull(listener.error);
    assertEquals(0, listener.exitConfirmations, "普通终态不得自行确认退出");
  }

  /** 测试意图：READY 帧的 daemon 能力必须写入租约存储，且 READY 唤醒会话监听器一次。 */
  @Test
  void readyRegistersCapabilitiesAndNotifiesSessionListener() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("channel-ready");
    int beforeReady = channel.envelopes().size();
    fixture.receiveReady(channel);

    assertEquals(
        EnvironmentDaemonServerTestSupport.READY_METADATA, fixture.leaseStore.readyMetadata);
    assertEquals(List.of(ENVIRONMENT_ID), fixture.readyNotifications);
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
    assertEquals(beforeReady, channel.envelopes().size());
  }

  /** 测试意图：不同 Environment 并行持有各自调用，各自终态互不干扰。 */
  @Test
  void distinctEnvironmentsStayConcurrentAndRouteByExactId() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-env-1");
    FakeControlChannel second =
        fixture.connectReady(OTHER_ENVIRONMENT_ID, "channel-env-2", OTHER_INSTANCE_ID, OTHER_TOKEN);

    RecordingListener firstListener = new RecordingListener();
    RecordingListener secondListener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), firstListener);
    fixture.server.invoke(OTHER_ENVIRONMENT_ID, capabilityRequest(CALL_TWO), secondListener);

    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), first.messageTypes());
    assertEquals(List.of(MessageType.SETTINGS, MessageType.INVOKE), second.messageTypes());

    fixture.receive(first, completedEnvelope(CALL_ONE.toString(), "one"));
    assertNotNull(firstListener.completed);
    assertNull(secondListener.completed);
  }

  /** 测试意图：未绑定到会话的连接在关闭时不得触碰租约存储或产生错误帧。 */
  @Test
  void closingUnboundConnectionDoesNotTouchLeaseStore() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = new FakeControlChannel("channel-unbound");
    channel.close();

    assertTrue(channel.closed());
    assertFalse(fixture.leaseStore.disconnected);
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：协议错误走 close-after-flush 语义，保证已入队的 ERROR 帧先被送出。 */
  @Test
  void protocolErrorUsesCloseAfterFlush() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-error-close");
    fixture.receive(
        channel,
        heartbeatEnvelope(
            OTHER_ENVIRONMENT_ID, 1L, new DaemonRuntimeStatus(DAEMON_VERSION, null), null));

    assertEquals(1, channel.closeAfterFlushCount());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
  }

  /** 测试意图：协议错误发生在 READY 之前时 ERROR 帧 scope 使用入站声明的 environmentId。 */
  @Test
  void protocolErrorBeforeHelloUsesInboundScope() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("channel-error-scope");
    fixture.receive(channel, heartbeatEnvelope());

    assertEquals(ENVIRONMENT_ID.value().toString(), channel.lastEnvelope().getEnvironmentId());
  }

  /** 测试意图：非法入参属于调用方错误。 */
  @Test
  void blankConnectionIdIsRejected() {
    Fixture fixture = new Fixture();
    DaemonAdmission.Admitted admitted =
        (DaemonAdmission.Admitted)
            fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID));
    assertThrows(
        NullPointerException.class,
        () -> fixture.server.open(null, new FakeControlChannel("1"), null));
    assertThrows(NullPointerException.class, () -> fixture.server.open(admitted, null, null));
  }

  /** 测试意图：在线 Daemon 在系统设置更新后收到新的 SETTINGS。 */
  @Test
  void systemSettingsChangeResendsSettingsToConnectedDaemon() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-live-settings");
    fixture.updateSettings(300L, 30L);

    fixture.server.resyncSettings();

    assertEquals(2L, channel.countOf(MessageType.SETTINGS));
    assertEquals(
        300L,
        channel.lastEnvelope().getSettings().getResourceBudgets().getTemporaryResourceTtlSeconds());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：会话设置每次判定现读，心跳超时变更立即生效。 */
  @Test
  void settingsAreReadOnEveryDecision() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-settings");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));
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

    FakeControlChannel channel = fixture.openSession("channel-lease-probe");
    // Session 打开已换发租约代币：该探测按数据库权威判定，而不是本地 READY 标志。
    assertTrue(fixture.server.holdsReadyLease(ENVIRONMENT_ID));

    fixture.leaseStore.holdsReadyLease = false;
    assertFalse(fixture.server.holdsReadyLease(ENVIRONMENT_ID));

    fixture.leaseStore.holdsReadyLease = true;
    fixture.receiveReady(channel);
    assertTrue(fixture.server.holdsReadyLease(ENVIRONMENT_ID));

    fixture.leaseStore.databaseUnavailable = true;
    assertFalse(fixture.server.holdsReadyLease(ENVIRONMENT_ID));
  }

  /** 测试意图：READY 状态跟随当前活跃连接生命周期；连接关闭后立即为 false。 */
  @Test
  void readyStateTracksConnectionLifecycle() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-ready-snapshot");
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));

    fixture.close(channel.connectionId());

    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：连接收尾的租约解绑与传输关闭互不跳过；首个 Error 保持对象身份抛出、后到的 Error 不遮蔽首个；安全日志只含固定阶段与异常类名。 */
  @Test
  void connectionCleanupKeepsFirstErrorIdentityAndStillClosesTransport() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cleanup-fatal");
    AssertionError leaseFailure =
        new AssertionError("lease disconnect failed: " + SYNTHETIC_SECRET);
    AssertionError transportFailure =
        new AssertionError("transport close failed: " + SYNTHETIC_SECRET);
    fixture.leaseStore.disconnectFailure = leaseFailure;
    channel.closeFailure = transportFailure;

    AssertionError thrown;
    try (CoreLogCapture logs = new CoreLogCapture()) {
      thrown = assertThrows(AssertionError.class, () -> fixture.close(channel.connectionId()));
      logs.assertOnlySafeFailures(2, "connection cleanup (lease disconnect|transport close)");
    }

    assertSame(leaseFailure, thrown, "the first Error must keep its object identity");
    assertArrayEquals(
        new Throwable[] {transportFailure},
        thrown.getSuppressed(),
        "a later Error must be retained without masking the first");
    assertTrue(channel.closed(), "the transport close must run even when the lease release fails");
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：先到的普通异常不吞掉后到的 Error：Error 以原身份传播，收尾不因前序失败而跳过。 */
  @Test
  void connectionCleanupPropagatesLaterErrorAboveEarlierRuntimeFailure() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cleanup-promote");
    fixture.leaseStore.disconnectFailure =
        new IllegalStateException("lease disconnect failed: " + SYNTHETIC_SECRET);
    AssertionError transportFailure =
        new AssertionError("transport close failed: " + SYNTHETIC_SECRET);
    channel.closeFailure = transportFailure;

    try (CoreLogCapture logs = new CoreLogCapture()) {
      AssertionError thrown =
          assertThrows(AssertionError.class, () -> fixture.close(channel.connectionId()));
      assertSame(
          transportFailure,
          thrown,
          "an Error must keep its identity even after an ordinary failure");
      logs.assertOnlySafeFailures(2, "connection cleanup (lease disconnect|transport close)");
    }

    assertTrue(channel.closed());
  }

  /** 测试意图：诊断后端（JUL Handler）自身抛异常时收尾仍完成 transport close，并在全部释放后传播该诊断失败。 */
  @Test
  void connectionCleanupCompletesTransportCloseWhenDiagnosticsHandlerThrows() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cleanup-log-failure");
    fixture.leaseStore.disconnectFailure =
        new IllegalStateException("lease disconnect failed: " + SYNTHETIC_SECRET);
    IllegalStateException diagnosticFailure =
        new IllegalStateException("diagnostics backend failed: " + SYNTHETIC_SECRET);

    IllegalStateException thrown;
    try (ThrowingLogHandler logs = new ThrowingLogHandler(diagnosticFailure)) {
      thrown =
          assertThrows(IllegalStateException.class, () -> fixture.close(channel.connectionId()));
    }

    assertSame(diagnosticFailure, thrown, "the diagnostic failure must keep its identity");
    assertTrue(channel.closed(), "the transport close must run even when diagnostics fail");
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：业务 Error 之后诊断后端再失败时，仍抛同一个业务 Error；诊断失败作为 suppressed 保留而非遮蔽原错误。 */
  @Test
  void connectionCleanupKeepsBusinessErrorIdentityWhenDiagnosticsHandlerThrows() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cleanup-error-log-failure");
    AssertionError leaseFailure =
        new AssertionError("lease disconnect failed: " + SYNTHETIC_SECRET);
    IllegalStateException diagnosticFailure =
        new IllegalStateException("diagnostics backend failed: " + SYNTHETIC_SECRET);
    fixture.leaseStore.disconnectFailure = leaseFailure;

    AssertionError thrown;
    try (ThrowingLogHandler logs = new ThrowingLogHandler(diagnosticFailure)) {
      thrown = assertThrows(AssertionError.class, () -> fixture.close(channel.connectionId()));
    }

    assertSame(leaseFailure, thrown, "the business Error must keep its object identity");
    assertArrayEquals(
        new Throwable[] {diagnosticFailure},
        thrown.getSuppressed(),
        "the diagnostic failure must stay visible without masking the business Error");
    assertTrue(channel.closed(), "the transport close must run even when diagnostics fail");
  }

  /** 测试意图：普通 RuntimeException 收尾失败只记录固定阶段与异常类名后被吞掉，不改变关闭语义，另一项释放仍执行。 */
  @Test
  void connectionCleanupLogsRuntimeFailuresWithoutPropagating() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cleanup-runtime");
    fixture.leaseStore.disconnectFailure =
        new IllegalStateException("lease disconnect failed: " + SYNTHETIC_SECRET);
    channel.closeFailure = new IllegalStateException("transport close failed: " + SYNTHETIC_SECRET);

    try (CoreLogCapture logs = new CoreLogCapture()) {
      fixture.close(channel.connectionId());
      logs.assertOnlySafeFailures(2, "connection cleanup (lease disconnect|transport close)");
    }

    assertTrue(channel.closed(), "a failed lease release must not skip the transport close");
    assertFalse(fixture.leaseStore.disconnected);
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：租约解绑注入 Error 时收尾不跳过——在途 IDE 命令仍按结果不确定终结、Error 以原身份抛出，且只记录固定阶段与异常类名。 */
  @Test
  void leaseErrorStillFailsPendingIde() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cleanup-ide-lease-error");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, OPEN_VIEW_COMMAND);
    AssertionError leaseFailure =
        new AssertionError("lease disconnect failed: " + SYNTHETIC_SECRET);
    fixture.leaseStore.disconnectFailure = leaseFailure;

    AssertionError thrown;
    try (CoreLogCapture logs = new CoreLogCapture()) {
      thrown = assertThrows(AssertionError.class, () -> fixture.close(channel.connectionId()));
      logs.assertOnlySafeFailures(1, "connection cleanup lease disconnect");
    }

    assertSame(leaseFailure, thrown, "the lease Error must keep its object identity");
    assertTrue(channel.closed(), "the transport close must run even when the lease release fails");
    assertEquals(0, fixture.server.activeIdeCount(), "the pending IDE call must be settled");
    assertTrue(request.reply().isCompletedExceptionally());
    assertInstanceOf(
        EnvironmentCapabilitySendUncertainException.class, request.reply().exceptionNow());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：传输关闭注入 Error 时收尾不跳过——租约已解绑、IDE 命令仍终结，Error 以原身份抛出。 */
  @Test
  void transportErrorStillFailsPendingIde() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-cleanup-ide-transport-error");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, OPEN_VIEW_COMMAND);
    AssertionError transportFailure =
        new AssertionError("transport close failed: " + SYNTHETIC_SECRET);
    channel.closeFailure = transportFailure;

    AssertionError thrown;
    try (CoreLogCapture logs = new CoreLogCapture()) {
      thrown = assertThrows(AssertionError.class, () -> fixture.close(channel.connectionId()));
      logs.assertOnlySafeFailures(1, "connection cleanup transport close");
    }

    assertSame(transportFailure, thrown, "the transport Error must keep its object identity");
    assertTrue(
        fixture.leaseStore.disconnected, "the lease release must run before the transport close");
    assertEquals(0, fixture.server.activeIdeCount(), "the pending IDE call must be settled");
    assertTrue(request.reply().isCompletedExceptionally());
    assertInstanceOf(
        EnvironmentCapabilitySendUncertainException.class, request.reply().exceptionNow());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 内核 IDE 通道不解释载荷，任意有效命令即可驱动在途登记与收尾。 */
  private static final IdeCommand OPEN_VIEW_COMMAND =
      IdeCommand.newBuilder()
          .setAction(IdeAction.OPEN_VIEW)
          .setMode(IdeOpenMode.CREATE)
          .setViewId("11111111-1111-1111-1111-111111111111")
          .setBrowserSessionId("22222222-2222-2222-2222-222222222222")
          .setEventNodeId("33333333-3333-3333-3333-333333333333")
          .setConnectionId("44444444-4444-4444-4444-444444444444")
          .setClaimId("55555555-5555-5555-5555-555555555555")
          .setExpectedBindingEpoch(0L)
          .setExpectedDaemonInstanceId("a1a1a1a1-b2b2-4c3c-8d4d-e5e5e5e5e5e5")
          .build();

  /** 测试意图：宿主 READY 与更新回执通知失败只记录固定阶段与异常类名，不改变会话状态，也不阻断后续帧处理。 */
  @Test
  void hostNotificationFailuresAreLoggedSafelyWithoutChangingSessionState() {
    Fixture fixture = new Fixture();
    fixture.readyNotificationFailure =
        new IllegalStateException("ready notification failed: " + SYNTHETIC_SECRET);
    fixture.updateNotificationFailure =
        new IllegalStateException("update notification failed: " + SYNTHETIC_SECRET);
    String operationId = UUID.randomUUID().toString();

    try (CoreLogCapture logs = new CoreLogCapture()) {
      FakeControlChannel channel = fixture.connectReady("channel-notify-failure");
      fixture.receive(channel, updateResultEnvelope(UUID.fromString(operationId), true, null));

      assertEquals(2, logs.records().size(), "each host notification failure must be logged once");
      for (LogRecord record : logs.records()) {
        assertEquals(Level.WARNING, record.getLevel());
        assertNull(record.getThrown(), "a raw throwable must never be logged");
        assertFalse(
            record.getMessage().contains(SYNTHETIC_SECRET),
            "the safe log must not echo the raw failure content");
      }
      assertTrue(
          logs.records().stream()
              .anyMatch(
                  record ->
                      record
                          .getMessage()
                          .matches(
                              "environment daemon environment ready notification failed:"
                                  + " [A-Za-z0-9_$]+")),
          "the READY notification failure must be logged with a fixed stage");
      assertTrue(
          logs.records().stream()
              .anyMatch(
                  record ->
                      record
                          .getMessage()
                          .matches(
                              "environment daemon update result notification failed:"
                                  + " [A-Za-z0-9_$]+")),
          "the update result notification failure must be logged with a fixed stage");
    }

    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
    assertFalse(fixture.readyNotifications.contains(ENVIRONMENT_ID));
  }

  /** 测试意图：非 UUID 或非 canonical 的 call.id 属于调用方错误，必须在触碰 wire 之前被拒绝。 */
  @Test
  void malformedCallIdIsRejectedBeforeWireSend() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-malformed-uuid");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID,
                new EnvironmentCapabilityExecutionRequest(
                    descriptor(), new EnvironmentCapabilityCall("  ", "{}"), Duration.ofSeconds(5)),
                new RecordingListener()));
    assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes());
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

  /** 测试意图：首帧不是 READY 的连接必须以协议错误关闭，且不推进任何会话状态。 */
  @Test
  void nonReadyFirstMessageIsProtocolViolation() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("channel-ready-never-sent");
    fixture.receive(channel, progressEnvelope(CALL_ONE, "chunk"));

    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
    assertFalse(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 测试意图：会话设置对象拒绝非正的心跳超时、资源上限与临时资源策略，避免装配期静默使用无效边界。 */
  @Test
  void settingsRejectNonPositiveBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentServerSettings(Duration.ZERO, 1024L, 259200L, 1800L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentServerSettings(Duration.ofSeconds(1), 0L, 259200L, 1800L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentServerSettings(Duration.ofSeconds(1), 1024L, 0L, 1800L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentServerSettings(Duration.ofSeconds(1), 1024L, 259200L, 0L));
    assertThrows(
        NullPointerException.class,
        () -> new EnvironmentServerSettings(null, 1024L, 259200L, 1800L));
    assertEquals(
        Duration.ofSeconds(1),
        new EnvironmentServerSettings(Duration.ofSeconds(1), 1024L, 259200L, 1800L)
            .heartbeatTimeout());
  }

  /** 测试意图：同一 transfer 的重复申请（含同实例重连后的重发）必须完全幂等——只发生一次 reserve，且回执同一 uploadId。 */
  @Test
  void repeatedUploadRequestIsExactlyIdempotent() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-transfer-idempotent");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    Envelope request =
        resourceUploadRequestEnvelope(CALL_ONE, TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A);
    fixture.receive(channel, request);
    fixture.receive(channel, request);

    assertEquals(1, fixture.ticketService.reserves.size());
    assertEquals(2, channel.countOf(MessageType.RESOURCE_UPLOAD_TICKET));
    // 票据 envelope 的 invocationId 是被调用的活动调用；transferId 只出现在 payload 中。
    for (Envelope envelope : channel.envelopes()) {
      if (envelope.getMessageType() == MessageType.RESOURCE_UPLOAD_TICKET) {
        assertEquals(CALL_ONE.toString(), envelope.getInvocationId());
      }
    }
    assertFalse(channel.closed());
  }

  /** 测试意图：同一 transfer 以不同请求内容重申请属于协议违规，必须关闭连接。 */
  @Test
  void conflictingUploadRequestRebindingIsProtocolFailure() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-transfer-conflict");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "b.txt", 3L, SHA_A));

    assertTrue(channel.closed());
    assertEquals(1, fixture.ticketService.reserves.size());
  }

  /** 测试意图：commit 必须指向已申请的 transfer 且 uploadId 一致；未知 transferId 与错配 uploadId 都是协议违规。 */
  @Test
  void commitRequiresOwnedTransferAndMatchingUploadId() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-transfer-commit");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    fixture.receive(
        channel, resourceUploadCommitEnvelope(CALL_ONE.toString(), TRANSFER_ONE, TRANSFER_ONE));
    assertTrue(channel.closed(), "commit for an unrequested transfer must close the connection");
  }

  /** 测试意图：commit 与申请票据的 uploadId 不一致时属于伪造，必须关闭连接且不触达票据服务。 */
  @Test
  void commitWithForeignUploadIdIsProtocolFailure() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-transfer-forged-commit");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    fixture.receive(
        channel,
        resourceUploadCommitEnvelope(CALL_ONE.toString(), TRANSFER_ONE, UUID.randomUUID()));

    assertTrue(channel.closed());
    assertTrue(fixture.ticketService.commits.isEmpty());
  }

  /** 测试意图：终态 COMPLETED 引用未申请的 uploadId、重复 uploadId 或与申请元数据不符的 uploadId 都属于协议违规。 */
  @Test
  void completedRejectsForgedDuplicateOrMismatchedUploads() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-completed-forged");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    // 未申请：本调用不拥有该 uploadId。
    fixture.receive(
        channel,
        completedResourceEnvelope(
            CALL_ONE, uploadedRef(UUID.randomUUID(), "text/plain", "a.txt", "abc"), null));
    assertTrue(channel.closed());
    assertEquals(0, fixture.ticketService.reserves.size());
  }

  /** 测试意图：终态重复声明同一 uploadId 属于协议违规。 */
  @Test
  void completedRejectsDuplicateUploadIds() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-completed-duplicate");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));
    ResourceRef ref = uploadedRefWithSize(uploadId, "text/plain", "a.txt", 3L, SHA_A);
    fixture.receive(channel, completedResourceEnvelope(CALL_ONE, List.of(ref, ref)));

    assertTrue(channel.closed());
  }

  /** 测试意图：终态引用与申请元数据（name/size/sha/mediaType）不符时拒绝，绝不把不匹配内容交给消费方。 */
  @Test
  void completedRejectsMetadataMismatch() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-completed-mismatch");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    Envelope ticket = ticketEnvelope(channel);
    UUID uploadId = uploadIdOfTicket(ticket);
    // 声明 size 与申请不一致。
    fixture.receive(
        channel,
        completedResourceEnvelope(
            CALL_ONE, uploadedRefWithSize(uploadId, "text/plain", "a.txt", 4L, SHA_A), null));

    assertTrue(channel.closed());
  }

  /** 测试意图：终态在 READY 之前到达（申请票据尚未就绪）时拒绝，避免消费方拿到不可用引用。 */
  @Test
  void completedRequiresReadyUpload() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-completed-not-ready");
    fixture.ticketService.pendingReserve = true;
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));
    fixture.receive(
        channel,
        completedResourceEnvelope(
            CALL_ONE, uploadedRefWithSize(uploadId, "text/plain", "a.txt", 3L, SHA_A), null));

    assertTrue(channel.closed(), "terminal before ready must be a protocol failure");
  }

  /** 测试意图：合法的 READY 上传引用在终态被接受并透传给消费方，未被引用的上传在终态后按 uploadId 释放。 */
  @Test
  void completedAcceptsReadyUploadAndReleasesUnreferenced() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-completed-accept");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    fixture.receive(
        channel,
        completedResourceEnvelope(
            CALL_ONE, uploadedRefWithSize(uploadId, "text/plain", "a.txt", 3L, SHA_A), null));

    assertNull(listener.error);
    assertNotNull(listener.completed);
    assertEquals(
        uploadId,
        ((ResourceResultContent) listener.completed.contents().get(0)).resource().blobUploadId());
    // 被引用的上传必须保留给 history 消费，不得在这里释放。
    assertFalse(fixture.ticketService.releases.contains(uploadId));
    assertFalse(channel.closed());
  }

  /** 测试意图：调用失败终态与调用方 expire 都按已绑定 uploadId 释放上传，绝不泄漏上传行。 */
  @Test
  void failedAndExpiredInvocationsReleaseBoundUploads() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-release-on-terminal");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    fixture.receive(channel, failedEnvelope(CALL_ONE.toString(), "boom"));

    assertEquals(List.of(uploadId), fixture.ticketService.releases);
  }

  /** 测试意图：上传控制帧只能指向本连接当前活动的调用；未知或已终结的 invocationId 属于协议违规。 */
  @Test
  void uploadControlRequiresActiveInvocation() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-upload-scope");

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));

    assertTrue(channel.closed());
    assertTrue(fixture.ticketService.reserves.isEmpty());
  }

  /** 测试意图：并发 transfer 数量有上限，恶意 Daemon 无法靠无限申请无界创建上传行。 */
  @Test
  void concurrentTransfersPerInvocationAreBounded() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-transfer-bound");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    for (int index = 0; index < 16; index++) {
      fixture.receive(
          channel,
          resourceUploadRequestEnvelope(
              CALL_ONE, UUID.randomUUID(), "text/plain", "a.txt", 3L, SHA_A));
    }
    assertFalse(channel.closed());
    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE, UUID.randomUUID(), "text/plain", "a.txt", 3L, SHA_A));

    assertTrue(channel.closed());
    assertEquals(16, fixture.ticketService.reserves.size());
  }

  /** 测试意图：去重命中（申请即 READY）时终态可直接引用该上传，且 reserve 只发生一次。 */
  @Test
  void dedupReadyReserveIsAcceptedByTerminalWithoutCommit() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-dedup-ready");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    // 去重命中：无需任何 COMMIT，终态直接引用即可。
    fixture.receive(
        channel,
        completedResourceEnvelope(
            CALL_ONE, uploadedRefWithSize(uploadId, "text/plain", "a.txt", 3L, SHA_A), null));

    assertNull(listener.error);
    assertNotNull(listener.completed);
    assertTrue(fixture.ticketService.commits.isEmpty(), "dedup 命中不应触发任何 commit");
    assertFalse(channel.closed());
  }

  /** 测试意图：票据服务瞬时 FAILED 不缓存——同一 transfer 的下一次申请必须再次触达票据服务，而不是重放失败结论。 */
  @Test
  void transientReserveFailureIsRetriedInsteadOfCached() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-reserve-retry");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    fixture.ticketService.failNextReserve = true;

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    assertEquals(MessageType.RESOURCE_UPLOAD_TICKET, ticketEnvelope(channel).getMessageType());
    assertEquals(
        TicketState.TICKET_FAILED, ticketEnvelope(channel).getResourceUploadTicket().getState());

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));

    assertEquals(2, fixture.ticketService.reserves.size(), "FAILED 必须允许重试再次触达票据服务");
    assertEquals(MessageType.RESOURCE_UPLOAD_TICKET, ticketEnvelope(channel).getMessageType());
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));
    fixture.receive(
        channel,
        completedResourceEnvelope(
            CALL_ONE, uploadedRefWithSize(uploadId, "text/plain", "a.txt", 3L, SHA_A), null));
    assertFalse(channel.closed());
  }

  /** 测试意图：commit 重复到达时只触达票据服务一次，且回执 READY 票据不再重复 I/O。 */
  @Test
  void repeatedCommitIsAnsweredFromTheCachedTicket() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-commit-repeat");
    fixture.ticketService.pendingReserve = true;
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    Envelope commit = resourceUploadCommitEnvelope(CALL_ONE, TRANSFER_ONE, uploadId);
    fixture.receive(channel, commit);
    fixture.receive(channel, commit);

    assertEquals(List.of(uploadId), fixture.ticketService.commits, "重复 commit 只应触达票据服务一次");
    assertFalse(channel.closed());
  }

  /** 测试意图：调用失败/取消/超时三条清理路径都只按已绑定的 uploadId 释放，且释放恰好一次。 */
  @Test
  void cancelledAndExpiredInvocationsReleaseExactlyOnce() {
    // 取消路径。
    Fixture cancelled = new Fixture();
    FakeControlChannel cancelChannel = cancelled.connectReady("channel-release-cancelled");
    cancelled.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    cancelled.receive(
        cancelChannel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID cancelledUploadId = uploadIdOfTicket(ticketEnvelope(cancelChannel));
    cancelled.receive(cancelChannel, cancelledEnvelope(CALL_ONE.toString()));
    assertEquals(List.of(cancelledUploadId), cancelled.ticketService.releases);
    // 迟到终态被 tombstone 静默忽略，不重复释放。
    cancelled.receive(cancelChannel, failedEnvelope(CALL_ONE.toString(), "late"));
    assertEquals(List.of(cancelledUploadId), cancelled.ticketService.releases);

    // 调用方 expire 路径。
    Fixture expired = new Fixture();
    FakeControlChannel expireChannel = expired.connectReady("channel-release-expired");
    EnvironmentCapabilityExecutionHandle handle =
        expired.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    expired.receive(
        expireChannel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID expiredUploadId = uploadIdOfTicket(ticketEnvelope(expireChannel));
    expired.server.expire(handle);
    expired.server.expire(handle);
    assertEquals(List.of(expiredUploadId), expired.ticketService.releases, "expire 必须幂等释放且恰好一次");
  }

  /** 测试意图：实例接管时取走在途调用并释放其绑定上传；旧连接的迟到控制帧一律忽略，不泄漏也不误判。 */
  @Test
  void takeoverReleasesUploadsAndIgnoresLateControlFrames() {
    Fixture fixture = new Fixture();
    FakeControlChannel first = fixture.connectReady("channel-takeover-first");
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    fixture.receive(
        first,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(first));

    // 旧进程断连；调用为「同实例可重连」保留，其绑定上传此时仍未释放。
    fixture.close("channel-takeover-first");
    assertTrue(fixture.ticketService.releases.isEmpty());

    // 身份不同的新进程接管该 Environment：在途调用被取走并登记 tombstone，绑定上传被释放。
    FakeControlChannel second = fixture.connectReady("channel-takeover-second", OTHER_INSTANCE_ID);

    assertEquals(List.of(uploadId), fixture.ticketService.releases);

    // 旧调用已终结：重连后的迟到终态被 tombstone 静默忽略，不重复释放、不协议报错。
    fixture.receive(second, failedEnvelope(CALL_ONE.toString(), "late"));
    assertFalse(second.closed());
    assertEquals(List.of(uploadId), fixture.ticketService.releases);
  }

  /** 测试意图：未知 invocationId 的上传控制帧是协议违规；已 tombstone 调用的控制帧同样不得触达票据服务。 */
  @Test
  void unknownAndTombstonedControlFramesAreRejected() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-control-unknown");
    // 未知调用：任何上传控制帧都必须是协议错误。
    fixture.receive(
        channel, resourceUploadCommitEnvelope(CALL_ONE.toString(), TRANSFER_ONE, TRANSFER_ONE));
    assertTrue(channel.closed());
    assertTrue(fixture.ticketService.commits.isEmpty());

    // tombstone 调用：终态先行终结该调用，随后同一 invocation 的控制帧不得触达票据服务。
    Fixture tombstoned = new Fixture();
    FakeControlChannel tombstoneChannel = tombstoned.connectReady("channel-control-tombstone");
    tombstoned.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    tombstoned.receive(tombstoneChannel, failedEnvelope(CALL_ONE.toString(), "boom"));
    int reservesBefore = tombstoned.ticketService.reserves.size();
    tombstoned.receive(
        tombstoneChannel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    assertTrue(tombstoneChannel.closed());
    assertEquals(reservesBefore, tombstoned.ticketService.reserves.size());
  }

  private static Envelope uploadRequest(
      UUID transferId, String mediaType, String name, long size, String sha256) {
    return resourceUploadRequestEnvelope(CALL_ONE, transferId, mediaType, name, size, sha256);
  }

  private static Envelope uploadCommit(UUID transferId, UUID uploadId) {
    return resourceUploadCommitEnvelope(CALL_ONE, transferId, uploadId);
  }

  private static ResourceRef uploadedRef(
      UUID uploadId, String mediaType, String name, String bytes) {
    return uploadedRefWithSize(
        uploadId, mediaType, name, bytes.length(), sha256Hex(bytes.getBytes(UTF_8)));
  }

  private static ResourceRef uploadedRefWithSize(
      UUID uploadId, String mediaType, String name, long size, String sha256) {
    return new ResourceRef(ResourceRef.blobUploadUri(uploadId), mediaType, name, size, sha256);
  }

  private static Envelope ticketEnvelope(FakeControlChannel channel) {
    return channel.envelopes().stream()
        .filter(envelope -> envelope.getMessageType() == MessageType.RESOURCE_UPLOAD_TICKET)
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private static UUID uploadIdOfTicket(Envelope ticket) {
    return UUID.fromString(ticket.getResourceUploadTicket().getUploadId());
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static EnvironmentCapabilityDescriptor descriptor() {
    return EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
  }

  private static EnvironmentCapabilityDescriptor processExecDescriptor() {
    return EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC);
  }

  /** 以给定 arguments 构造 process.exec 请求；arguments 原样进入执行请求，用于断言发送前 workdir 校验。 */
  private static EnvironmentCapabilityExecutionRequest processExecRequestWith(
      String arguments, UUID callId) {
    return new EnvironmentCapabilityExecutionRequest(
        processExecDescriptor(),
        new EnvironmentCapabilityCall(callId.toString(), arguments),
        Duration.ofSeconds(5));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("racers did not start together");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("racers interrupted", error);
    }
  }

  private static boolean join(Thread thread) {
    try {
      thread.join(5_000);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    }
    return !thread.isAlive();
  }

  /** 让两个并发 racer 在同一 barrier 上同时放行；超时或 barrier 破坏都视为测试环境失效。 */
  private static void awaitStart(CyclicBarrier start) {
    try {
      start.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("racer interrupted", error);
    } catch (BrokenBarrierException | TimeoutException error) {
      throw new IllegalStateException("racers did not start together", error);
    }
  }

  private static EnvironmentCapabilityExecutionRequest requestWithDescriptor(
      EnvironmentCapabilityDescriptor descriptor, UUID callId) {
    return new EnvironmentCapabilityExecutionRequest(
        descriptor,
        new EnvironmentCapabilityCall(callId.toString(), "{\"path\":\"README.md\"}"),
        Duration.ofSeconds(5));
  }

  /**
   * 注入会抛异常的 JUL 处理器：验证诊断后端自身失败时收尾仍完成，且诊断失败在全部释放后可见。
   *
   * <p>{@link #close()} 恢复级别与父处理器设置，避免影响其它测试。
   */

  /** 意图：IDE_LEASE 只能发往当前 READY 且仍持有租约的连接；payload 严格编码且不携带 invocationId。 */
  @Test
  void boundRuntimeControlsRequireCurrentLeaseAndEncodeStrictPayloads() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-runtime-controls");
    UUID viewId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID browserSessionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    IdeLease ideLease =
        IdeLease.newBuilder()
            .setRound(2L)
            .addBindings(
                IdeLeaseBinding.newBuilder()
                    .setViewId(viewId.toString())
                    .setBrowserSessionId(browserSessionId.toString())
                    .setBindingEpoch(3L)
                    .build())
            .build();

    fixture.server.sendIdeLease(
        ENVIRONMENT_ID, new DaemonConnectionIdentity(ENVIRONMENT_ID, INSTANCE_ID), ideLease);

    Envelope leaseFrame = channel.lastEnvelope();
    assertEquals(MessageType.IDE_LEASE, leaseFrame.getMessageType());
    assertTrue(leaseFrame.getInvocationId().isEmpty());
    assertEquals(3L, leaseFrame.getIdeLease().getBindings(0).getBindingEpoch());

    fixture.leaseStore.holdsReadyLease = false;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.sendIdeLease(
                ENVIRONMENT_ID,
                new DaemonConnectionIdentity(ENVIRONMENT_ID, INSTANCE_ID),
                ideLease));

    // 新实例接管后，旧代际轮次不得落入当前连接；当前代际的轮次仍可发送。
    fixture.leaseStore.holdsReadyLease = true;
    fixture.close("channel-runtime-controls");
    FakeControlChannel replacement =
        fixture.connectReady("channel-runtime-controls-takeover", OTHER_INSTANCE_ID);
    assertEquals(
        OTHER_INSTANCE_ID,
        fixture.server.connectionIdentity(ENVIRONMENT_ID).orElseThrow().daemonInstanceId());
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.sendIdeLease(
                ENVIRONMENT_ID,
                new DaemonConnectionIdentity(ENVIRONMENT_ID, INSTANCE_ID),
                ideLease));
    assertTrue(
        replacement.envelopes().stream()
            .noneMatch(envelope -> envelope.getMessageType() == MessageType.IDE_LEASE));

    fixture.server.sendIdeLease(
        ENVIRONMENT_ID, new DaemonConnectionIdentity(ENVIRONMENT_ID, OTHER_INSTANCE_ID), ideLease);
    assertEquals(MessageType.IDE_LEASE, replacement.lastEnvelope().getMessageType());
  }

  /** 意图：READY 缓存当前运行快照；RUNTIME_STATUS 仅在更高 revision 时替换，同 revision 同内容幂等。 */
  @Test
  void runtimeStatusIsCachedPerGenerationAndRejectsConflictingRevision() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-runtime-status");

    DaemonRuntimeSnapshot ready = fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow();
    assertEquals(1L, ready.revision());
    assertEquals(DAEMON_VERSION, ready.status().daemonVersion());
    assertNull(ready.status().update());

    fixture.receive(channel, runtimeStatusEnvelope(2L, DAEMON_VERSION, "1.0.10", 10L));
    assertEquals(
        new DaemonUpdateProgress("1.0.10", 10L, null),
        fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().status().update());

    // 同 revision 完全相同内容重复到达不改变状态。
    fixture.receive(channel, runtimeStatusEnvelope(2L, DAEMON_VERSION, "1.0.10", 10L));
    assertTrue(channel.isOpen());

    // 更低 revision 是迟到快照，忽略而不关闭连接。
    fixture.receive(channel, runtimeStatusEnvelope(1L, DAEMON_VERSION, "1.0.8", 1L));
    assertTrue(channel.isOpen());
    assertEquals(
        new DaemonUpdateProgress("1.0.10", 10L, null),
        fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().status().update());

    // 同 revision 内容不同是协议错误。
    fixture.receive(channel, runtimeStatusEnvelope(2L, DAEMON_VERSION, "1.0.10", 11L));
    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
  }

  /** 意图：HEARTBEAT 的 runtimeStatus 同样推进缓存；租约失效后 runtimeStatus 立即 unavailable。 */
  @Test
  void heartbeatRuntimeStatusIsCachedAndLeaseLossMakesSnapshotUnavailable() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-runtime-heartbeat");

    fixture.receive(channel, heartbeatEnvelope(5L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
    assertEquals(5L, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().revision());

    fixture.leaseStore.holdsReadyLease = false;
    fixture.leaseStore.activeLeaseToken = false;
    assertTrue(fixture.server.runtimeStatus(ENVIRONMENT_ID).isEmpty());
  }

  /** 意图：仅被接受的实质运行态变化发布；重复、旧快照和只有 revision 变化均静默。 */
  @Test
  void runtimeStatusNotificationsFollowActualChangesFromBothMessages() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-runtime-notifications");
    DaemonRuntimeStatus running = new DaemonRuntimeStatus(DAEMON_VERSION, null);
    DaemonRuntimeStatus updating =
        new DaemonRuntimeStatus(DAEMON_VERSION, new DaemonUpdateProgress("1.0.10", 10L, null));

    fixture.receive(channel, heartbeatEnvelope(1L, running));
    fixture.receive(channel, heartbeatEnvelope(2L, running));
    assertEquals(2L, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().revision());
    assertTrue(fixture.leaseStore.runtimeStatusChanges.isEmpty());

    fixture.receive(channel, heartbeatEnvelope(3L, updating));
    assertEquals(List.of(ENVIRONMENT_ID), fixture.leaseStore.runtimeStatusChanges);
    fixture.receive(channel, runtimeStatusEnvelope(3L, updating));
    fixture.receive(channel, runtimeStatusEnvelope(2L, running));
    assertEquals(List.of(ENVIRONMENT_ID), fixture.leaseStore.runtimeStatusChanges);

    fixture.receive(channel, runtimeStatusEnvelope(4L, running));
    assertEquals(List.of(ENVIRONMENT_ID, ENVIRONMENT_ID), fixture.leaseStore.runtimeStatusChanges);
    assertEquals(running, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().status());
  }

  /** 意图：运行态发布时围栏失效或存储失败不能被吞掉，会话必须关闭。 */
  @Test
  void runtimeStatusNotificationFailureClosesTheSession() {
    for (boolean fenceLost : List.of(true, false)) {
      Fixture fixture = new Fixture();
      FakeControlChannel channel = fixture.connectReady("channel-runtime-failure");
      fixture.leaseStore.runtimeStatusFenceLost = fenceLost;
      fixture.leaseStore.runtimeStatusNotificationFails = !fenceLost;

      fixture.receive(channel, runtimeStatusEnvelope(2L, DAEMON_VERSION, "1.0.10", 10L));
      assertTrue(channel.closed());
      assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
      assertTrue(fixture.server.runtimeStatus(ENVIRONMENT_ID).isEmpty());
      assertTrue(fixture.leaseStore.runtimeStatusChanges.isEmpty());
    }
  }

  private static String runtimeStatusPayload(
      long revision, String daemonVersion, String targetVersion, long downloadedBytes) {
    return "{\"revision\":\""
        + revision
        + "\",\"status\":{\"daemonVersion\":\""
        + daemonVersion
        + "\",\"update\":"
        + (targetVersion == null
            ? "null"
            : "{\"targetVersion\":\""
                + targetVersion
                + "\",\"downloadedBytes\":"
                + downloadedBytes
                + ",\"totalBytes\":null}")
        + "}}";
  }

  /** 测试意图：RECOVER_NOT_FOUND 终结在途调用（SendUncertainException）并释放已绑定上传。 */
  @Test
  void recoverNotFoundTerminatesInFlightInvocationAndReleasesTransfers() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-recover-not-found");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);

    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(CALL_ONE, TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    UUID uploadId = uploadIdOfTicket(ticketEnvelope(channel));

    fixture.receive(channel, recoverNotFoundEnvelope(CALL_ONE));

    assertNotNull(listener.error);
    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, listener.error);
    assertEquals(List.of(uploadId), fixture.ticketService.releases);
    assertFalse(channel.closed());
  }

  @Test
  void abandonAdmissionReleasesLease() {
    Fixture fixture = new Fixture();
    DaemonAdmission.Admitted admitted =
        (DaemonAdmission.Admitted)
            fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID));
    fixture.server.abandon(admitted);
    FakeControlChannel channel = new FakeControlChannel("c1");
    assertThrows(IllegalStateException.class, () -> fixture.server.open(admitted, channel, null));
  }

  @Test
  void localReturnsTunnelWhenSessionReady() {
    Fixture fixture = new Fixture();
    assertTrue(fixture.server.local(ENVIRONMENT_ID).isEmpty());
    FakeControlChannel channel = fixture.connectReady("c-tunnel");
    assertTrue(fixture.server.local(ENVIRONMENT_ID).isPresent());
    fixture.close(channel);
    assertTrue(fixture.server.local(ENVIRONMENT_ID).isEmpty());
  }

  @Test
  void invokeWithExpectedFileIncludesExpectedFileInEnvelope() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-expected-file");
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT);
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            descriptor,
            new EnvironmentCapabilityCall(
                CALL_ONE.toString(),
                "{\"path\":\"/srv/repo/README.md\",\"old_string\":\"a\",\"new_string\":\"b\"}"),
            Duration.ofSeconds(5),
            new ExpectedFile("/srv/repo/README.md", "a".repeat(64)));
    fixture.server.invoke(ENVIRONMENT_ID, request, new RecordingListener());
    assertEquals(1, channel.countOf(MessageType.INVOKE));
    assertEquals(
        fixture.settings.get().maxOutputBytes(),
        channel.lastEnvelope().getInvoke().getMaxOutputBytes());
    assertTrue(channel.lastEnvelope().getInvoke().hasExpectedFile());
    assertEquals(
        "/srv/repo/README.md", channel.lastEnvelope().getInvoke().getExpectedFile().getPath());
    assertEquals("a".repeat(64), channel.lastEnvelope().getInvoke().getExpectedFile().getSha256());
  }

  @Test
  void resourceTransferExceedingLimitFailsOnlyItsInvocation() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-transfer-limit");
    RecordingListener listener = new RecordingListener();
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), listener);
    long actualSize = fixture.settings.get().maxOutputBytes() + 1;
    Envelope request =
        Envelope.newBuilder()
            .setMessageType(MessageType.RESOURCE_UPLOAD_REQUEST)
            .setEnvironmentId(ENVIRONMENT_ID.value().toString())
            .setInvocationId(CALL_ONE.toString())
            .setResourceUploadRequest(
                ResourceUploadRequest.newBuilder()
                    .setTransferId(UUID.randomUUID().toString())
                    .setMediaType("text/plain")
                    .setName("big.txt")
                    .setSize(actualSize)
                    .setSha256("a".repeat(64))
                    .build())
            .build();
    fixture.receive(channel, request);

    Envelope ticket = ticketEnvelope(channel);
    assertEquals(TicketState.TICKET_FAILED, ticket.getResourceUploadTicket().getState());
    assertEquals(
        "Tool output is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.",
        ticket.getResourceUploadTicket().getMessage());
    fixture.receive(
        channel,
        failedEnvelope(CALL_ONE.toString(), ticket.getResourceUploadTicket().getMessage()));
    assertEquals(ticket.getResourceUploadTicket().getMessage(), listener.error.getMessage());
    assertFalse(channel.closed());
  }

  @Test
  void attachCompletesWhenTerminalCompletedReceived() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-attach-complete");
    UUID invocationId = UUID.randomUUID();
    RecordingListener listener = new RecordingListener();

    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.attach(ENVIRONMENT_ID, invocationId, listener);
    assertNotNull(handle);
    assertEquals(1, channel.countOf(MessageType.RECOVER));
    assertEquals(0, channel.countOf(MessageType.INVOKE));

    fixture.receive(channel, completedEnvelope(invocationId, "done"));
    assertNotNull(listener.completed);
    assertEquals("done", ((TextResultContent) listener.completed.contents().getFirst()).text());
  }

  @Test
  void attachSucceedsAfterRetireAndDeliversToNewListener() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-attach-retire-reattach");
    UUID invocationId = UUID.randomUUID();
    RecordingListener oldListener = new RecordingListener();

    EnvironmentCapabilityExecutionHandle oldHandle =
        fixture.server.attach(ENVIRONMENT_ID, invocationId, oldListener);
    assertNotNull(oldHandle);
    assertEquals(1, channel.countOf(MessageType.RECOVER));

    fixture.server.expire(oldHandle);

    RecordingListener newListener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle newHandle =
        fixture.server.attach(ENVIRONMENT_ID, invocationId, newListener);
    assertNotNull(newHandle);
    assertEquals(2, channel.countOf(MessageType.RECOVER));

    fixture.receive(channel, completedEnvelope(invocationId, "done"));
    assertNull(oldListener.completed);
    assertNotNull(newListener.completed);
    assertEquals("done", ((TextResultContent) newListener.completed.contents().getFirst()).text());

    fixture.receive(channel, executionExitedEnvelope(invocationId.toString()));
    assertEquals(0, oldListener.exitConfirmations);
    assertEquals(1, newListener.exitConfirmations);
  }

  @Test
  void attachFailsWhenRecoverNotFoundReceived() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-attach-not-found");
    UUID invocationId = UUID.randomUUID();
    RecordingListener listener = new RecordingListener();

    fixture.server.attach(ENVIRONMENT_ID, invocationId, listener);
    assertEquals(1, channel.countOf(MessageType.RECOVER));

    fixture.receive(channel, recoverNotFoundEnvelope(invocationId));
    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, listener.error);
  }

  @Test
  void attachCanBeCancelledByHandle() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-attach-cancel");
    UUID invocationId = UUID.randomUUID();
    RecordingListener listener = new RecordingListener();

    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.attach(ENVIRONMENT_ID, invocationId, listener);
    handle.cancel();
    assertEquals(1, channel.countOf(MessageType.CANCEL));

    fixture.receive(channel, cancelledEnvelope(invocationId, "user cancel"));
    assertInstanceOf(EnvironmentCapabilityCancelledException.class, listener.error);
  }

  @Test
  void attachUnavailableWhenSessionNotReadyOrNoLease() {
    Fixture fixture = new Fixture();
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.attach(ENVIRONMENT_ID, UUID.randomUUID(), new RecordingListener()));

    FakeControlChannel channel = fixture.openSession("channel-attach-not-ready");
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.attach(ENVIRONMENT_ID, UUID.randomUUID(), new RecordingListener()));

    fixture.receiveReady(channel);
    fixture.leaseStore.holdsReadyLease = false;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.attach(ENVIRONMENT_ID, UUID.randomUUID(), new RecordingListener()));
  }

  @Test
  void attachReplaysRecoverOnSessionReconnect() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel1 = fixture.connectReady("channel-attach-reconnect-1");
    UUID invocationId = UUID.randomUUID();
    RecordingListener listener = new RecordingListener();

    fixture.server.attach(ENVIRONMENT_ID, invocationId, listener);
    assertEquals(1, channel1.countOf(MessageType.RECOVER));
    assertEquals(0, channel1.countOf(MessageType.INVOKE));

    fixture.close(channel1);
    FakeControlChannel channel2 = fixture.connectReady("channel-attach-reconnect-2");
    assertEquals(1, channel2.countOf(MessageType.RECOVER));
    assertEquals(0, channel2.countOf(MessageType.INVOKE));

    fixture.receive(channel2, completedEnvelope(invocationId, "recovered-done"));
    assertNotNull(listener.completed);
    assertEquals(
        "recovered-done", ((TextResultContent) listener.completed.contents().getFirst()).text());
  }

  @Test
  void attachTerminatesWithSendUncertainWhenDaemonInstanceReplaced() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-attach-replace-1");
    UUID invocationId = UUID.randomUUID();
    RecordingListener listener = new RecordingListener();

    fixture.server.attach(ENVIRONMENT_ID, invocationId, listener);

    fixture.close("channel-attach-replace-1");
    fixture.connectReady("channel-attach-replace-2", OTHER_INSTANCE_ID);
    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, listener.error);
  }

  @Test
  void drainSendsGoawayReleasesLeasesAndSubsequentAdmitReturnsRetryLater() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-drain-1");

    fixture.server.drain();
    assertEquals(1, channel.drainCount());
    assertTrue(fixture.leaseStore.released);

    DaemonAdmission admission =
        fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID));
    assertInstanceOf(DaemonAdmission.RetryLater.class, admission);

    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.attach(ENVIRONMENT_ID, UUID.randomUUID(), new RecordingListener()));
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.server.invoke(
                ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener()));

    assertDoesNotThrow(() -> fixture.server.drain());
  }

  /** 准入时查询现有租约失败必须 fail-closed 为协议错误，不得误判为可接管。 */
  @Test
  void admitFailsClosedWhenActiveLeaseLookupFails() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-admit-active-lease-failure");
    fixture.leaseStore.databaseUnavailable = true;

    ControlProtocolException error =
        assertThrows(
            ControlProtocolException.class,
            () ->
                fixture.server.admit(
                    new DaemonHandshake(ENVIRONMENT_ID, TOKEN, OTHER_INSTANCE_ID)));
    assertTrue(error.getMessage().contains("database unavailable"));
  }

  /** abandon 的租约断开是清理动作：失败只记录诊断，不向准入调用方传播。 */
  @Test
  void abandonIsolatesLeaseDisconnectFailure() {
    Fixture fixture = new Fixture();
    DaemonAdmission.Admitted admitted =
        assertInstanceOf(
            DaemonAdmission.Admitted.class,
            fixture.server.admit(new DaemonHandshake(ENVIRONMENT_ID, TOKEN, INSTANCE_ID)));
    fixture.leaseStore.disconnectFailure = new IllegalStateException("disconnect failed");

    assertDoesNotThrow(() -> fixture.server.abandon(admitted));
    assertFalse(fixture.leaseStore.disconnected);
  }

  /** 配置通知读取源失败只影响该次对账，连接保持可用并由下一心跳收敛。 */
  @Test
  void environmentChangedIsolatesConfigSourceFailure() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("config-source-isolation");
    fixture.configLookupFailure = new IllegalStateException("config source unavailable");

    assertDoesNotThrow(() -> fixture.server.environmentChanged(ENVIRONMENT_ID));
    assertFalse(channel.closed());

    fixture.configLookupFailure = null;
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 6L));
    fixture.receive(channel, heartbeatEnvelope());
    assertEquals(6L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  /** drain 对传输 drain 与租约释放的失败都保持隔离，继续完成其余清理。 */
  @Test
  void drainIsolatesTransportAndLeaseReleaseFailures() {
    Fixture transportFailure = new Fixture();
    FakeControlChannel transportChannel =
        transportFailure.connectReady("channel-drain-transport-failure");
    transportChannel.closeFailure = new IllegalStateException("drain failed");
    assertDoesNotThrow(transportFailure.server::drain);
    assertTrue(transportFailure.leaseStore.released);

    Fixture releaseFailure = new Fixture();
    releaseFailure.connectReady("channel-drain-release-failure");
    releaseFailure.leaseStore.databaseUnavailable = true;
    assertDoesNotThrow(releaseFailure.server::drain);
    assertFalse(releaseFailure.leaseStore.released);
  }

  /** RECOVER 发送抛异常或返回 BUSY 时 attach 关闭连接并返回句柄，调用方无需重试同一连接。 */
  @Test
  void attachClosesSessionWhenRecoverCannotBeDelivered() {
    Fixture failed = new Fixture();
    FakeControlChannel failedChannel = failed.connectReady("channel-attach-send-failure");
    failedChannel.failNextOffer = true;

    assertNotNull(failed.server.attach(ENVIRONMENT_ID, UUID.randomUUID(), new RecordingListener()));
    assertTrue(failedChannel.closed());

    Fixture busy = new Fixture();
    FakeControlChannel busyChannel = busy.connectReady("channel-attach-send-busy");
    busyChannel.busyNextOffer = true;

    assertNotNull(busy.server.attach(ENVIRONMENT_ID, UUID.randomUUID(), new RecordingListener()));
    assertTrue(busyChannel.closed());
  }

  /** attach 存活租约查询失败必须 fail-closed，不发送 RECOVER。 */
  @Test
  void attachFailsClosedWhenLeaseStoreIsUnavailable() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-attach-db-down");
    fixture.leaseStore.databaseUnavailable = true;

    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.attach(ENVIRONMENT_ID, UUID.randomUUID(), new RecordingListener()));
    assertEquals(List.of(MessageType.SETTINGS), channel.messageTypes());
  }

  /** 计数器只反映当前在途调用，终态后归零。 */
  @Test
  void activeInvocationCountTracksTrackedInvocations() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-active-count");
    assertEquals(0, fixture.server.activeInvocationCount(ENVIRONMENT_ID));

    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    assertEquals(1, fixture.server.activeInvocationCount(ENVIRONMENT_ID));

    fixture.receive(channel, completedEnvelope(CALL_ONE.toString(), "done"));
    assertEquals(0, fixture.server.activeInvocationCount(ENVIRONMENT_ID));
  }

  /** 实例接管时个别 listener 抛异常只记录诊断，不得阻断其余在途调用的终结与新连接建立。 */
  @Test
  void takeoverIsolatesAbandonedListenerFailure() {
    Fixture fixture = new Fixture();
    fixture.connectReady("channel-takeover-listener-old");
    EnvironmentCapabilityExecutionListener throwingListener =
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {}

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {}

          @Override
          public void onError(Throwable error) {
            throw new IllegalStateException("listener failed");
          }

          @Override
          public void onExitConfirmed() {}
        };
    fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), throwingListener);
    fixture.close("channel-takeover-listener-old");

    FakeControlChannel replacement =
        fixture.connectReady("channel-takeover-listener-new", OTHER_INSTANCE_ID);
    assertTrue(replacement.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));
  }

  /** 票据释放失败属于 best-effort 清理，不阻断调用终态收敛。 */
  @Test
  void transferReleaseFailureIsIsolated() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-transfer-release-failure");
    EnvironmentCapabilityExecutionHandle handle =
        fixture.server.invoke(ENVIRONMENT_ID, capabilityRequest(CALL_ONE), new RecordingListener());
    fixture.receive(
        channel,
        resourceUploadRequestEnvelope(
            CALL_ONE.toString(), TRANSFER_ONE, "text/plain", "a.txt", 3L, SHA_A));
    uploadIdOfTicket(ticketEnvelope(channel));
    fixture.ticketService.releaseFailure = new IllegalStateException("release failed");

    assertDoesNotThrow(() -> fixture.server.expire(handle));
    assertTrue(fixture.ticketService.releases.isEmpty());
  }

  /** 协议错误 ERROR 帧发送失败时仍必须关闭连接，避免半开连接继续接收。 */
  @Test
  void protocolErrorOfferFailureStillCloses() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("channel-error-offer-failure");
    channel.failNextOffer = true;

    fixture.receive(
        channel,
        heartbeatEnvelope(
            OTHER_ENVIRONMENT_ID, 1L, new DaemonRuntimeStatus(DAEMON_VERSION, null), null));

    assertTrue(channel.closed());
  }

  private static final class ThrowingLogHandler implements AutoCloseable {

    private final List<Logger> loggers =
        List.of(
            Logger.getLogger(EnvironmentDaemonServer.class.getName()),
            Logger.getLogger(DaemonConnectionSession.class.getName()));
    private final List<Level> previousLevels = new ArrayList<>();
    private final List<Boolean> previousUseParentHandlers = new ArrayList<>();
    private final RuntimeException failure;
    private final Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            throw failure;
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    private ThrowingLogHandler(RuntimeException failure) {
      this.failure = failure;
      handler.setLevel(Level.ALL);
      for (Logger logger : loggers) {
        previousLevels.add(logger.getLevel());
        previousUseParentHandlers.add(logger.getUseParentHandlers());
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
      }
    }

    @Override
    public void close() {
      for (int i = 0; i < loggers.size(); i++) {
        Logger logger = loggers.get(i);
        logger.removeHandler(handler);
        logger.setLevel(previousLevels.get(i));
        logger.setUseParentHandlers(previousUseParentHandlers.get(i));
      }
    }
  }

  /**
   * 捕获核心收尾/通知诊断日志的 JUL 端口：安全失败日志是普通 {@code System.Logger} 记录，只有挂接处理器才能断言其内容。
   *
   * <p>{@link #close()} 恢复接管前的级别与父处理器设置，避免影响其它测试。
   */
  private static final class CoreLogCapture implements AutoCloseable {

    private final List<Logger> loggers =
        List.of(
            Logger.getLogger(EnvironmentDaemonServer.class.getName()),
            Logger.getLogger(DaemonConnectionSession.class.getName()));
    private final List<Level> previousLevels = new ArrayList<>();
    private final List<Boolean> previousUseParentHandlers = new ArrayList<>();
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    private CoreLogCapture() {
      handler.setLevel(Level.ALL);
      for (Logger logger : loggers) {
        previousLevels.add(logger.getLevel());
        previousUseParentHandlers.add(logger.getUseParentHandlers());
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
      }
    }

    List<LogRecord> records() {
      return List.copyOf(records);
    }

    /** 每条失败恰好一条 WARNING，且只含固定阶段与异常类名：没有原始 throwable，也不回显合成秘密正文。 */
    void assertOnlySafeFailures(int expectedRecords, String stagePattern) {
      assertEquals(expectedRecords, records.size(), "every cleanup failure must be logged once");
      for (LogRecord record : records) {
        assertEquals(Level.WARNING, record.getLevel());
        assertNull(record.getThrown(), "a raw throwable must never be logged");
        assertTrue(
            record
                .getMessage()
                .matches("environment daemon " + stagePattern + " failed: [A-Za-z0-9_$]+"),
            record.getMessage());
        assertFalse(
            record.getMessage().contains(SYNTHETIC_SECRET),
            "the safe log must not echo the raw failure content");
      }
    }

    @Override
    public void close() {
      for (int i = 0; i < loggers.size(); i++) {
        Logger logger = loggers.get(i);
        logger.removeHandler(handler);
        logger.setLevel(previousLevels.get(i));
        logger.setUseParentHandlers(previousUseParentHandlers.get(i));
      }
    }
  }
}
