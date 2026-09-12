package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 验证 {@link EnvironmentOperationDispatcher} 的执行分发与并发契约：
 * 包括同步完成防泄露、回调与关机竞争、关机围栏标记与取消顺序、发送前与发送不确定性异常处理、本地清扫句柄取消及超批次无配额分发。
 */
class EnvironmentOperationDispatcherTest {

  private UUID nodeId;
  private EnvironmentOperationRepository repository;
  private EnvironmentCapabilityTransport transport;
  private EnvironmentOperationCompletionCoordinator coordinator;
  private ExecutorService drainExecutor;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService pollScheduler;
  private EnvironmentOperationDispatcher dispatcher;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID sourceId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    nodeId = UUID.randomUUID();
    repository = mock(EnvironmentOperationRepository.class);
    transport = mock(EnvironmentCapabilityTransport.class);
    coordinator = mock(EnvironmentOperationCompletionCoordinator.class);

    drainExecutor = Executors.newSingleThreadExecutor();
    workerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    pollScheduler = Executors.newSingleThreadScheduledExecutor();

    dispatcher =
        new EnvironmentOperationDispatcher(
            nodeId,
            repository,
            transport,
            coordinator,
            drainExecutor,
            workerExecutor,
            pollScheduler);
  }

  @AfterEach
  void tearDown() {
    dispatcher.stop();
    drainExecutor.shutdownNow();
    workerExecutor.shutdownNow();
    pollScheduler.shutdownNow();
  }

  private ClaimedOperation createClaimedOperation(UUID specificOpId) {
    DaemonSkillSourceConfig config =
        new DaemonSkillSourceConfig(
            sourceId,
            1L,
            2L,
            DaemonSkillSourceType.PATH,
            "/skills/path",
            false,
            null,
            null,
            null,
            null,
            Set.of(sourceId));
    String arguments = new DaemonSkillSourceConfigCodec().encode(config);

    EnvironmentOperation op =
        new EnvironmentOperation(
            specificOpId,
            envId,
            sourceId,
            EnvironmentOperationType.SKILL_REFRESH,
            EnvironmentOperationStatus.RUNNING,
            1L,
            2L,
            arguments,
            "{\"type\":\"PATH\"}",
            Instant.now().plusSeconds(60),
            nodeId,
            leaseToken,
            Instant.now(),
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());
    return new ClaimedOperation(op, Duration.ofSeconds(30));
  }

  /** 测试意图：验证在 invoke 返回句柄前 listener 已同步完成时，句柄被及时取消或移除，绝不泄露在 activeHandles 中。 */
  @Test
  void synchronousCompletionBeforeInvokeReturnsLeavesNoLeakedHandle() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);
    CountDownLatch coordinatedLatch = new CountDownLatch(1);

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              EnvironmentCapabilityExecutionListener listener = invocation.getArgument(2);
              listener.onComplete(EnvironmentCapabilityResult.json(opId.toString(), "{}"));
              return handle;
            });

    when(coordinator.coordinateResult(
            any(), any(), any(), any(), anyLong(), any(), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              coordinatedLatch.countDown();
              return OperationPublishOutcome.APPLIED;
            });

    dispatcher.wake();
    assertTrue(coordinatedLatch.await(5, TimeUnit.SECONDS));

    // 等待异步处理收敛
    Thread.sleep(100);
    assertEquals(0, dispatcher.getActiveHandleCount(), "同步完成后 activeExecutions 必须为空，不得泄露句柄");
    verify(handle).cancel();
  }

  /** 测试意图：验证回调与关机竞争时，关机先行终结 DB 状态并使 ActiveExecution 终态化，后续迟到回调不产生第二条 DB 终态路径。 */
  @Test
  void callbackVsStopLeavesAtMostOneTerminalDbPathAndNoStaleHandle() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    CountDownLatch invokeStarted = new CountDownLatch(1);
    CountDownLatch stopFinished = new CountDownLatch(1);
    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);
    AtomicBoolean callbackRan = new AtomicBoolean(false);

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              EnvironmentCapabilityExecutionListener listener = invocation.getArgument(2);
              Thread.ofVirtual()
                  .start(
                      () -> {
                        try {
                          invokeStarted.countDown();
                          stopFinished.await(5, TimeUnit.SECONDS);
                          listener.onComplete(
                              EnvironmentCapabilityResult.json(opId.toString(), "{}"));
                          callbackRan.set(true);
                        } catch (Exception ignored) {
                        }
                      });
              return handle;
            });

    dispatcher.wake();
    assertTrue(invokeStarted.await(5, TimeUnit.SECONDS));

    // 调用 stop
    dispatcher.stop();
    stopFinished.countDown();

    Thread.sleep(150);

    verify(repository).markRunningUnknownOnShutdown(nodeId);
    // 迟到的回调必须被 ActiveExecution.terminal CAS 拦截，绝不调用 coordinator 写入第二条路径
    verify(coordinator, never())
        .coordinateResult(any(), any(), any(), any(), anyLong(), any(), anyLong(), any());
    verify(handle).cancel();
    assertEquals(0, dispatcher.getActiveHandleCount());
  }

  /** 测试意图：验证 dispatcher stop 之后绝不发起新的 invoke 调用，已认领未执行的操作安全回退为 PENDING。 */
  @Test
  void claimVsStopNoPostStopInvoke() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt())).thenReturn(List.of(claimed));

    dispatcher.stop();
    dispatcher.wake();

    Thread.sleep(100);
    verify(transport, never()).invoke(any(), any(), any());
  }

  /**
   * 测试意图：验证 stop 优先调用 markRunningUnknownOnShutdown 将 DB 中本节点剩余 RUNNING 记录推至 UNKNOWN，随后再取消 handle。
   */
  @Test
  void stopCallsShutdownUnknownBeforeCancel() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    CountDownLatch invokeInvoked = new CountDownLatch(1);
    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invokeInvoked.countDown();
              return handle;
            });

    dispatcher.wake();
    assertTrue(invokeInvoked.await(5, TimeUnit.SECONDS));

    // 此时句柄已挂载
    dispatcher.stop();

    InOrder inOrder = inOrder(repository, handle);
    inOrder.verify(repository).markRunningUnknownOnShutdown(nodeId);
    inOrder.verify(handle).cancel();
  }

  /**
   * 测试意图：验证在 transport invoke 边界发生的未知非受检异常是不确定的，必须固定收敛至 UNKNOWN (TRANSPORT_ERROR)，绝不
   * rescheduleUnsent。
   */
  @Test
  void unexpectedInvokeExceptionNeverReschedules() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenThrow(new RuntimeException("Transport netty pipeline broke unexpectedly"));

    dispatcher.wake();
    Thread.sleep(150);

    verify(repository, never()).rescheduleUnsent(any(), any(), any());
    verify(coordinator).coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken));
  }

  /** 测试意图：验证发送前确定性异常 (Busy / Unavailable) 明确未执行，安全 rescheduleUnsent 回退为 PENDING。 */
  @Test
  void busyOrUnavailableDoReschedule() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenThrow(new EnvironmentCapabilityBusyException("Busy"));

    dispatcher.wake();
    Thread.sleep(150);

    verify(repository).rescheduleUnsent(eq(opId), eq(nodeId), eq(leaseToken));
    verify(coordinator, never()).coordinateTransportUnknown(any(), any(), any());
  }

  /** 测试意图：验证同步抛出 SendUncertainException 时，可能已发送，绝不重试，操作保持 RUNNING 等待超时清扫。 */
  @Test
  void sendUncertainRemainsRunning() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenThrow(new EnvironmentCapabilitySendUncertainException("Send uncertain"));

    dispatcher.wake();
    Thread.sleep(150);

    verify(repository, never()).rescheduleUnsent(any(), any(), any());
    verify(coordinator, never()).coordinateTransportUnknown(any(), any(), any());
    verify(coordinator, never())
        .coordinateExecutionFailure(any(), any(), any(), any(), anyLong(), any(), anyLong());
    assertEquals(0, dispatcher.getActiveHandleCount());
  }

  /** 测试意图：验证 runSweeper 本地超时清扫推进至 UNKNOWN 后，主动取消内存中的活跃句柄。 */
  @Test
  void localSweepCancellation() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    CountDownLatch invokeInvoked = new CountDownLatch(1);
    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invokeInvoked.countDown();
              return handle;
            });

    dispatcher.wake();
    assertTrue(invokeInvoked.await(5, TimeUnit.SECONDS));
    assertEquals(1, dispatcher.getActiveHandleCount());

    when(repository.sweepLocalExpiredRunning(nodeId))
        .thenReturn(List.of(new SweptOperationInfo(opId, nodeId, leaseToken)));

    dispatcher.runSweeper();

    verify(handle).cancel();
    assertEquals(0, dispatcher.getActiveHandleCount());
  }

  /** 测试意图：验证大于单批容量（BATCH_SIZE=50）的操作能够被持续排空认领并全部派发至虚拟线程，无任何人工并发配额限制。 */
  @Test
  void greaterThanBatchClaimsAllDispatchWithoutArtificialInFlightCap() throws Exception {
    List<ClaimedOperation> batch1 = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      batch1.add(createClaimedOperation(UUID.randomUUID()));
    }
    List<ClaimedOperation> batch2 = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      batch2.add(createClaimedOperation(UUID.randomUUID()));
    }

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(batch1)
        .thenReturn(batch2)
        .thenReturn(List.of());

    CountDownLatch allInvoked = new CountDownLatch(65);
    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              allInvoked.countDown();
              return mock(EnvironmentCapabilityExecutionHandle.class);
            });

    dispatcher.wake();

    assertTrue(allInvoked.await(10, TimeUnit.SECONDS), "全部 65 个操作必须无配额地被派发到虚拟线程执行");
    verify(transport, times(65)).invoke(any(), any(), any());
  }
}
