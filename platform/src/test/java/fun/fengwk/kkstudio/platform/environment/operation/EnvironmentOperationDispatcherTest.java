package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 验证 {@link EnvironmentOperationDispatcher} 的执行分发与并发契约：
 * 包括生命周期读写围栏、同步完成防泄露且无多余取消、回调与关机竞争、关机围栏标记与取消顺序、发送前与发送不确定性异常处理、 本地清扫句柄取消、并发唤醒合并及超批次无配额分发。
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

  /** 测试意图：验证 dispatcher start() 之前 wake() 是纯 no-op，绝不认领或分发任何操作；start() 之后方可正常分发。 */
  @Test
  void startBeforeWakeNoOpBeforeStart() {
    assertFalse(dispatcher.isRunning());
    dispatcher.wake();
    verify(repository, never()).claimPendingWithTimeout(any(), anyInt());

    dispatcher.start();
    assertTrue(dispatcher.isRunning());
    // start 幂等性
    dispatcher.start();
    assertTrue(dispatcher.isRunning());
  }

  /** 测试意图：验证并发/多次 wake 请求被合并，基于 wakeRequested 与 drainRunning 不会无限积压 drain 任务。 */
  @Test
  void repeatedWakeCoalescing() throws Exception {
    CountDownLatch drainStarted = new CountDownLatch(1);
    CountDownLatch unblockDrain = new CountDownLatch(1);

    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenAnswer(
            inv -> {
              drainStarted.countDown();
              unblockDrain.await(5, TimeUnit.SECONDS);
              return List.of();
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(drainStarted.await(5, TimeUnit.SECONDS));

    // 在 drain 运行期间密集触发多次 wake
    for (int i = 0; i < 10; i++) {
      dispatcher.wake();
    }

    // 释放阻塞
    unblockDrain.countDown();

    // 等待 drainExecutor 屏障任务完成
    drainExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);

    // 验证 claimPendingWithTimeout 仅被执行了必要的合并轮次（通常为 1~2 轮），绝非 10+ 轮
    verify(repository, atMost(3)).claimPendingWithTimeout(eq(nodeId), anyInt());
  }

  /** 测试意图：验证在 invoke 返回句柄前 listener 已同步完成时，状态被及时清除且绝不触发不必要的取消，也不泄露在活跃集合中。 */
  @Test
  void synchronousCompletionBeforeInvokeReturnsLeavesNoLeakedHandle() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);
    CountDownLatch coordinatedLatch = new CountDownLatch(1);
    CountDownLatch invokeFinishedLatch = new CountDownLatch(1);

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              EnvironmentCapabilityExecutionListener listener = invocation.getArgument(2);
              listener.onComplete(EnvironmentCapabilityResult.json(opId.toString(), "{}"));
              invokeFinishedLatch.countDown();
              return handle;
            });

    when(coordinator.coordinateResult(
            any(), any(), any(), any(), anyLong(), any(), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              coordinatedLatch.countDown();
              return OperationPublishOutcome.APPLIED;
            });

    dispatcher.start();
    dispatcher.wake();

    assertTrue(coordinatedLatch.await(5, TimeUnit.SECONDS));
    assertTrue(invokeFinishedLatch.await(5, TimeUnit.SECONDS));

    // 等待 drainExecutor 屏障
    drainExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);

    assertEquals(0, dispatcher.getActiveHandleCount(), "同步完成后 activeExecutions 必须为空，不得泄露句柄");
    verify(handle, never()).cancel();
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
    CountDownLatch callbackDone = new CountDownLatch(1);
    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);

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
                          callbackDone.countDown();
                        } catch (Exception ignored) {
                        }
                      });
              return handle;
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invokeStarted.await(5, TimeUnit.SECONDS));

    // 调用 stop
    dispatcher.stop();
    stopFinished.countDown();
    assertTrue(callbackDone.await(5, TimeUnit.SECONDS));

    verify(repository).markRunningUnknownOnShutdown(nodeId);
    // 迟到的回调必须被 ActiveExecution.terminal CAS 拦截，绝不调用 coordinator 写入第二条路径
    verify(coordinator, never())
        .coordinateResult(any(), any(), any(), any(), anyLong(), any(), anyLong(), any());
    verify(handle).cancel();
    assertEquals(0, dispatcher.getActiveHandleCount());
  }

  /**
   * 测试意图：验证在 claimPendingWithTimeout 持有读锁期间发起 stop，stop 的写围栏等待读锁释放后优先执行， 将本节点 RUNNING 记录推至
   * UNKNOWN，随后 worker 读锁观测到 stopped，安全 rescheduleUnsent 绝不发起 invoke。
   */
  @Test
  void claimVsStopNoPostStopInvoke() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);

    CountDownLatch claimHoldingReadLock = new CountDownLatch(1);
    CountDownLatch stopRequested = new CountDownLatch(1);
    CountDownLatch stopCompleted = new CountDownLatch(1);

    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenAnswer(
            inv -> {
              claimHoldingReadLock.countDown();
              stopRequested.await(5, TimeUnit.SECONDS);
              return List.of(claimed);
            })
        .thenReturn(List.of());

    dispatcher.start();
    dispatcher.wake();

    assertTrue(claimHoldingReadLock.await(5, TimeUnit.SECONDS));

    // 在另一个线程发起 stop，它将阻塞等待 claimPendingWithTimeout 释放读锁
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                stopRequested.countDown();
                dispatcher.stop();
                stopCompleted.countDown();
              } catch (Exception ignored) {
              }
            });

    assertTrue(stopCompleted.await(5, TimeUnit.SECONDS));

    // 验证 stop 的写围栏执行了 markRunningUnknownOnShutdown
    verify(repository).markRunningUnknownOnShutdown(nodeId);
    // 验证停止后绝不发起 invoke
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

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invokeInvoked.await(5, TimeUnit.SECONDS));

    // 此时句柄已挂载
    dispatcher.stop();

    InOrder inOrder = inOrder(repository, handle);
    inOrder.verify(repository).markRunningUnknownOnShutdown(nodeId);
    inOrder.verify(handle).cancel();
  }

  /** 测试意图：验证 invoke 执行期间持有读锁会阻塞 stop 的写围栏；首个操作 invoke 允许完成，stop 完成后后续操作绝不 invoke。 */
  @Test
  void transportInvocationBlockedWhileStopWaits() throws Exception {
    UUID opId1 = UUID.randomUUID();
    UUID opId2 = UUID.randomUUID();
    ClaimedOperation claimed1 = createClaimedOperation(opId1);
    ClaimedOperation claimed2 = createClaimedOperation(opId2);

    CountDownLatch op1InvokeStarted = new CountDownLatch(1);
    CountDownLatch stopInitiated = new CountDownLatch(1);
    CountDownLatch unblockOp1Invoke = new CountDownLatch(1);
    CountDownLatch stopCompleted = new CountDownLatch(1);

    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed1))
        .thenAnswer(
            inv -> {
              op1InvokeStarted.await(5, TimeUnit.SECONDS);
              stopInitiated.await(5, TimeUnit.SECONDS);
              return List.of(claimed2);
            })
        .thenReturn(List.of());

    EnvironmentCapabilityExecutionHandle handle1 = mock(EnvironmentCapabilityExecutionHandle.class);

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              op1InvokeStarted.countDown();
              unblockOp1Invoke.await(5, TimeUnit.SECONDS);
              return handle1;
            });

    dispatcher.start();
    dispatcher.wake();

    assertTrue(op1InvokeStarted.await(5, TimeUnit.SECONDS));

    // 在另一个线程发起 stop
    Thread.ofVirtual()
        .start(
            () -> {
              stopInitiated.countDown();
              dispatcher.stop();
              stopCompleted.countDown();
            });

    // 此时 stop 在等待 op1 释放读锁
    assertFalse(stopCompleted.await(100, TimeUnit.MILLISECONDS));

    // 释放 op1 invoke
    unblockOp1Invoke.countDown();
    assertTrue(stopCompleted.await(5, TimeUnit.SECONDS));

    // 验证 invoke 仅被 op1 调用过 1 次，op2 绝不调用 invoke
    verify(transport, times(1)).invoke(any(), any(), any());
  }

  /** 测试意图：验证 transport invoke 返回 null 句柄是违约异常，必须从活跃状态移除并协调为 UNKNOWN，绝不重试。 */
  @Test
  void nullHandleCoordinatesUnknown() throws Exception {
    ClaimedOperation claimed = createClaimedOperation(opId);
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    CountDownLatch unknownCoordinated = new CountDownLatch(1);
    when(transport.invoke(any(), any(), any())).thenReturn(null);
    when(coordinator.coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken)))
        .thenAnswer(
            inv -> {
              unknownCoordinated.countDown();
              return true;
            });

    dispatcher.start();
    dispatcher.wake();

    assertTrue(unknownCoordinated.await(5, TimeUnit.SECONDS));
    assertEquals(0, dispatcher.getActiveHandleCount());
    verify(repository, never()).rescheduleUnsent(any(), any(), any());
  }

  /**
   * 测试意图：验证当 worker 线程池拒绝执行时（表明调度器关机或不可用），立即将当前操作及该批次中所有剩余已认领未提交的操作原子 rescheduleUnsent， 并退出排空循环；在该
   * wake 周期内绝不执行第二次 repository claim，且绝不发起 transport invoke，避免死循环重认领热点。
   */
  @Test
  void workerRejectionReschedulesBatchAndExitsDrainCycle() throws Exception {
    UUID opId1 = UUID.randomUUID();
    UUID opId2 = UUID.randomUUID();
    ClaimedOperation claimed1 = createClaimedOperation(opId1);
    ClaimedOperation claimed2 = createClaimedOperation(opId2);

    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed1, claimed2))
        .thenReturn(List.of());

    ExecutorService rejectingWorkerExecutor = mock(ExecutorService.class);
    when(rejectingWorkerExecutor.submit(any(Runnable.class)))
        .thenThrow(new RejectedExecutionException("Worker rejected"));

    EnvironmentOperationDispatcher rejectingDispatcher =
        new EnvironmentOperationDispatcher(
            nodeId,
            repository,
            transport,
            coordinator,
            drainExecutor,
            rejectingWorkerExecutor,
            pollScheduler);

    rejectingDispatcher.start();
    rejectingDispatcher.wake();

    // 等待 drainExecutor 屏障任务完成
    drainExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);

    // 验证本 wake 周期内仅 claim 了一次，绝无第二次 claim
    verify(repository, times(1)).claimPendingWithTimeout(eq(nodeId), anyInt());
    // 验证批次中所有行均被 rescheduleUnsent 恰好一次
    verify(repository, times(1)).rescheduleUnsent(eq(opId1), eq(nodeId), any());
    verify(repository, times(1)).rescheduleUnsent(eq(opId2), eq(nodeId), any());
    // 验证绝不发起 transport invoke
    verify(transport, never()).invoke(any(), any(), any());

    rejectingDispatcher.stop();
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

    CountDownLatch unknownLatch = new CountDownLatch(1);
    when(coordinator.coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken)))
        .thenAnswer(
            inv -> {
              unknownLatch.countDown();
              return true;
            });

    when(transport.invoke(any(), any(), any()))
        .thenThrow(new RuntimeException("Transport netty pipeline broke unexpectedly"));

    dispatcher.start();
    dispatcher.wake();

    assertTrue(unknownLatch.await(5, TimeUnit.SECONDS));
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

    CountDownLatch rescheduleLatch = new CountDownLatch(1);
    when(repository.rescheduleUnsent(eq(opId), eq(nodeId), eq(leaseToken)))
        .thenAnswer(
            inv -> {
              rescheduleLatch.countDown();
              return 1;
            });

    when(transport.invoke(any(), any(), any()))
        .thenThrow(new EnvironmentCapabilityBusyException("Busy"));

    dispatcher.start();
    dispatcher.wake();

    assertTrue(rescheduleLatch.await(5, TimeUnit.SECONDS));
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

    CountDownLatch invokeDone = new CountDownLatch(1);
    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            inv -> {
              try {
                throw new EnvironmentCapabilitySendUncertainException("Send uncertain");
              } finally {
                invokeDone.countDown();
              }
            });

    dispatcher.start();
    dispatcher.wake();

    assertTrue(invokeDone.await(5, TimeUnit.SECONDS));

    // 等待 drainExecutor 屏障保证排空任务退出
    drainExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);

    verify(repository, never()).rescheduleUnsent(any(), any(), any());
    verify(coordinator, never()).coordinateTransportUnknown(any(), any(), any());
    verify(coordinator, never())
        .coordinateExecutionFailure(any(), any(), any(), any(), anyLong(), any(), anyLong());
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

    dispatcher.start();
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

    dispatcher.start();
    dispatcher.wake();

    assertTrue(allInvoked.await(10, TimeUnit.SECONDS), "全部 65 个操作必须无配额地被派发到虚拟线程执行");
    verify(transport, times(65)).invoke(any(), any(), any());
  }
}
