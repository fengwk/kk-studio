package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
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
    return createClaimedOperation(
        specificOpId, EnvironmentOperationType.SKILL_REFRESH, null, Duration.ofSeconds(30));
  }

  private ClaimedOperation createClaimedOperation(
      UUID specificOpId,
      EnvironmentOperationType type,
      String customArguments,
      Duration remainingTimeout) {
    String arguments = customArguments;
    if (arguments == null) {
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
      arguments = new DaemonSkillSourceConfigCodec().encode(config);
    }
    Duration timeout = remainingTimeout != null ? remainingTimeout : Duration.ofSeconds(30);

    EnvironmentOperation op =
        new EnvironmentOperation(
            specificOpId,
            envId,
            sourceId,
            type,
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
    return new ClaimedOperation(op, timeout);
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

  /** 测试意图：验证重复 start() 是幂等 no-op，调度器不会重复安排轮询任务。 */
  @Test
  void start_alreadyStarted_isNoOp() {
    dispatcher.start();
    assertTrue(dispatcher.isRunning());
    dispatcher.start();
    assertTrue(dispatcher.isRunning());
  }

  /** 测试意图：验证 dispatcher stop() 之后再次调用 start() 抛出 IllegalStateException。 */
  @Test
  void start_afterStopped_throwsIllegalStateException() {
    dispatcher.stop();
    assertThrows(IllegalStateException.class, () -> dispatcher.start());
  }

  /** 测试意图：验证调度器 schedule 抛出异常时 start() 记录告警并向上抛出异常。 */
  @Test
  void start_pollSchedulerThrows_propagatesException() {
    ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
    when(mockScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenThrow(new RejectedExecutionException("scheduler rejected"));

    EnvironmentOperationDispatcher customDispatcher =
        new EnvironmentOperationDispatcher(
            nodeId,
            repository,
            transport,
            coordinator,
            drainExecutor,
            workerExecutor,
            mockScheduler);

    assertThrows(RejectedExecutionException.class, customDispatcher::start);
  }

  /** 测试意图：验证重复 stop() 是安全幂等的。 */
  @Test
  void stop_alreadyStopped_isNoOp() {
    dispatcher.start();
    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
  }

  /** 测试意图：验证 stop() 期间如果仓库 markRunningUnknownOnShutdown 抛异常，仍能完成句柄取消并正常停止。 */
  @Test
  void stop_whenRepositoryThrows_logsAndContinuesCleanly() {
    when(repository.markRunningUnknownOnShutdown(any()))
        .thenThrow(new RuntimeException("db connection reset"));
    dispatcher.start();
    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
  }

  /** 测试意图：验证 stop() 时若句柄 cancel() 抛出异常，dispatcher 安全吞掉异常不阻断后续句柄。 */
  @Test
  void stop_handleCancelThrows_suppressesException() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);
    EnvironmentCapabilityExecutionHandle faultyHandle =
        mock(EnvironmentCapabilityExecutionHandle.class);
    doThrow(new RuntimeException("cancel failed")).when(faultyHandle).cancel();

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              return faultyHandle;
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
    verify(faultyHandle).cancel();
  }

  /** 测试意图：验证未启动时 wake() 不触发任何认领。 */
  @Test
  void wake_whenNotRunning_isNoOp() {
    dispatcher.wake();
    verify(repository, never()).claimPendingWithTimeout(any(), anyInt());
  }

  /** 测试意图：验证 runSweeper 中若仓库 sweep 抛出异常，捕获日志并不向外抛出。 */
  @Test
  void runSweeper_whenRepositoryThrows_logsAndSuppresses() {
    when(repository.sweepLocalExpiredRunning(any())).thenThrow(new RuntimeException("db timeout"));
    dispatcher.runSweeper();
    verify(repository).sweepLocalExpiredRunning(eq(nodeId));
  }

  /** 测试意图：验证 runSweeper 中若句柄 cancel() 抛出异常，安全捕获抑制。 */
  @Test
  void runSweeper_handleCancelThrows_suppressesException() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);
    EnvironmentCapabilityExecutionHandle faultyHandle =
        mock(EnvironmentCapabilityExecutionHandle.class);
    doThrow(new RuntimeException("cancel failed")).when(faultyHandle).cancel();

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              return faultyHandle;
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    when(repository.sweepLocalExpiredRunning(eq(nodeId)))
        .thenReturn(List.of(new SweptOperationInfo(opId, nodeId, leaseToken)));

    dispatcher.runSweeper();
    verify(faultyHandle).cancel();
  }

  /** 测试意图：验证排空执行器提交失败时安全重置 drainRunning 标志。 */
  @Test
  void submitDrain_drainExecutorRejects_handlesGracefully() {
    ExecutorService mockDrain = mock(ExecutorService.class);
    doThrow(new RejectedExecutionException("drain full")).when(mockDrain).execute(any());

    EnvironmentOperationDispatcher customDispatcher =
        new EnvironmentOperationDispatcher(
            nodeId, repository, transport, coordinator, mockDrain, workerExecutor, pollScheduler);

    customDispatcher.start();
    customDispatcher.wake();
    verify(mockDrain).execute(any());
  }

  /** 测试意图：验证 worker 线程池拒绝任务时，将本批未执行的操作安全回滚并重新调度（rescheduleUnsent）。 */
  @Test
  void drainPendingOperations_workerExecutorRejects_reschedulesRemainingAndReturnsFalse()
      throws Exception {
    ExecutorService mockWorker = mock(ExecutorService.class);
    when(mockWorker.submit(any(Runnable.class)))
        .thenThrow(new RejectedExecutionException("worker pool full"));

    UUID opId1 = UUID.randomUUID();
    UUID opId2 = UUID.randomUUID();
    ClaimedOperation claimed1 = createClaimedOperation(opId1);
    ClaimedOperation claimed2 = createClaimedOperation(opId2);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(claimed1, claimed2))
        .thenReturn(List.of());

    EnvironmentOperationDispatcher customDispatcher =
        new EnvironmentOperationDispatcher(
            nodeId, repository, transport, coordinator, drainExecutor, mockWorker, pollScheduler);

    customDispatcher.start();
    customDispatcher.wake();

    drainExecutor.shutdown();
    assertTrue(drainExecutor.awaitTermination(5, TimeUnit.SECONDS));

    verify(repository).rescheduleUnsent(eq(opId1), eq(nodeId), any());
    verify(repository).rescheduleUnsent(eq(opId2), eq(nodeId), any());
  }

  /** 测试意图：验证重新调度剩余操作时若仓库抛异常，日志记录并继续处理下一项。 */
  @Test
  void rescheduleRemaining_whenRepositoryThrows_suppressesException() throws Exception {
    ExecutorService mockWorker = mock(ExecutorService.class);
    when(mockWorker.submit(any(Runnable.class)))
        .thenThrow(new RejectedExecutionException("worker pool full"));

    doThrow(new RuntimeException("db error"))
        .when(repository)
        .rescheduleUnsent(any(), any(), any());

    UUID opId1 = UUID.randomUUID();
    ClaimedOperation claimed1 = createClaimedOperation(opId1);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(claimed1))
        .thenReturn(List.of());

    EnvironmentOperationDispatcher customDispatcher =
        new EnvironmentOperationDispatcher(
            nodeId, repository, transport, coordinator, drainExecutor, mockWorker, pollScheduler);

    customDispatcher.start();
    customDispatcher.wake();

    drainExecutor.shutdown();
    assertTrue(drainExecutor.awaitTermination(5, TimeUnit.SECONDS));

    verify(repository).rescheduleUnsent(eq(opId1), eq(nodeId), any());
  }

  /** 测试意图：验证操作剩余超时时间大于描述符自身有界超时时间时，安全截断为描述符超时。 */
  @Test
  void executeOperation_clampsTimeoutToDescriptorTimeout() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);
    ArgumentCaptor<EnvironmentCapabilityExecutionRequest> requestCaptor =
        ArgumentCaptor.forClass(EnvironmentCapabilityExecutionRequest.class);

    // 传入超大超时（100天），而 SKILL_REFRESH 描述符有界超时通常远小于该值
    ClaimedOperation op =
        createClaimedOperation(
            opId, EnvironmentOperationType.SKILL_REFRESH, null, Duration.ofDays(100));

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(op))
        .thenReturn(List.of());

    when(transport.invoke(any(), requestCaptor.capture(), any()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              return mock(EnvironmentCapabilityExecutionHandle.class);
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    EnvironmentCapabilityExecutionRequest captured = requestCaptor.getValue();
    assertNotNull(captured);
    assertTrue(captured.timeout().compareTo(Duration.ofDays(1)) < 0, "请求超时必须被截断在描述符界限内");
  }

  /** 测试意图：验证 SKILL_INSTALL 与 SKILL_UPDATE 操作类型正确映射到底层能力标识。 */
  @Test
  void executeOperation_skillInstallAndSkillUpdate_mapsCorrectCapabilityIds() throws Exception {
    CountDownLatch invoked = new CountDownLatch(2);
    ArgumentCaptor<EnvironmentCapabilityExecutionRequest> requestCaptor =
        ArgumentCaptor.forClass(EnvironmentCapabilityExecutionRequest.class);

    ClaimedOperation installOp =
        createClaimedOperation(
            UUID.randomUUID(),
            EnvironmentOperationType.SKILL_INSTALL,
            null,
            Duration.ofSeconds(30));
    ClaimedOperation updateOp =
        createClaimedOperation(
            UUID.randomUUID(), EnvironmentOperationType.SKILL_UPDATE, null, Duration.ofSeconds(30));

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(installOp, updateOp))
        .thenReturn(List.of());

    when(transport.invoke(any(), requestCaptor.capture(), any()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              return mock(EnvironmentCapabilityExecutionHandle.class);
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    List<EnvironmentCapabilityExecutionRequest> allRequests = requestCaptor.getAllValues();
    assertEquals(2, allRequests.size());
    Set<EnvironmentCapabilityId> capIds =
        Set.of(allRequests.get(0).descriptor().id(), allRequests.get(1).descriptor().id());
    assertTrue(capIds.contains(EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL));
    assertTrue(capIds.contains(EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE));
  }

  /** 测试意图：验证操作参数确定性构建失败时，安全推进至 coordinateExecutionFailure。 */
  @Test
  void executeOperation_argumentsMalformed_coordinatesExecutionFailure() throws Exception {
    CountDownLatch coordinated = new CountDownLatch(1);

    ClaimedOperation badOp =
        createClaimedOperation(
            opId, EnvironmentOperationType.SKILL_REFRESH, "not-valid-json", Duration.ofSeconds(30));

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(badOp))
        .thenReturn(List.of());

    when(coordinator.coordinateExecutionFailure(
            any(), any(), any(), any(), anyLong(), any(), anyLong()))
        .thenAnswer(
            invocation -> {
              coordinated.countDown();
              return null;
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(coordinated.await(5, TimeUnit.SECONDS));

    verify(coordinator)
        .coordinateExecutionFailure(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), anyLong(), eq(sourceId), anyLong());
  }

  /** 测试意图：验证 listener.onPartial 对管理操作为 safe no-op，不影响执行状态与协调器。 */
  @Test
  void listener_onPartial_isIgnored() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);
    ArgumentCaptor<EnvironmentCapabilityExecutionListener> listenerCaptor =
        ArgumentCaptor.forClass(EnvironmentCapabilityExecutionListener.class);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), listenerCaptor.capture()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              return mock(EnvironmentCapabilityExecutionHandle.class);
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    EnvironmentCapabilityExecutionListener listener = listenerCaptor.getValue();
    assertNotNull(listener);
    listener.onPartial(mock(EnvironmentCapabilityResult.class));

    verify(coordinator, never())
        .coordinateResult(any(), any(), any(), any(), anyLong(), any(), anyLong(), any());
    verify(coordinator, never())
        .coordinateExecutionFailure(any(), any(), any(), any(), anyLong(), any(), anyLong());
  }

  /**
   * 测试意图：验证 listener.onError 接收到 EnvironmentCapabilitySendUncertainException 时转入
   * coordinateTransportUnknown。
   */
  @Test
  void listener_onError_sendUncertain_coordinatesTransportUnknown() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);
    ArgumentCaptor<EnvironmentCapabilityExecutionListener> listenerCaptor =
        ArgumentCaptor.forClass(EnvironmentCapabilityExecutionListener.class);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), listenerCaptor.capture()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              return mock(EnvironmentCapabilityExecutionHandle.class);
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    EnvironmentCapabilityExecutionListener listener = listenerCaptor.getValue();
    listener.onError(new EnvironmentCapabilitySendUncertainException("send status uncertain"));

    verify(coordinator).coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken));
  }

  /** 测试意图：验证 listener.onError 接收到常规异常时转入 coordinateExecutionFailure。 */
  @Test
  void listener_onError_otherException_coordinatesExecutionFailure() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);
    ArgumentCaptor<EnvironmentCapabilityExecutionListener> listenerCaptor =
        ArgumentCaptor.forClass(EnvironmentCapabilityExecutionListener.class);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), listenerCaptor.capture()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              return mock(EnvironmentCapabilityExecutionHandle.class);
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    EnvironmentCapabilityExecutionListener listener = listenerCaptor.getValue();
    listener.onError(new RuntimeException("generic execution failure"));

    verify(coordinator)
        .coordinateExecutionFailure(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), anyLong(), eq(sourceId), anyLong());
  }

  /** 测试意图：验证 transport.invoke 抛出 EnvironmentCapabilityUnavailableException 时安全重新调度操作。 */
  @Test
  void invoke_environmentCapabilityUnavailableException_reschedules() throws Exception {
    CountDownLatch rescheduled = new CountDownLatch(1);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              throw new EnvironmentCapabilityUnavailableException("session unavailable");
            });

    when(repository.rescheduleUnsent(eq(opId), eq(nodeId), eq(leaseToken)))
        .thenAnswer(
            invocation -> {
              rescheduled.countDown();
              return RescheduleOutcome.RESCHEDULED;
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(rescheduled.await(5, TimeUnit.SECONDS));

    verify(repository).rescheduleUnsent(eq(opId), eq(nodeId), eq(leaseToken));
  }

  /** 测试意图：验证 transport.invoke 抛出 EnvironmentCapabilitySendUncertainException 时不重新调度（静默保留）。 */
  @Test
  void invoke_environmentCapabilitySendUncertainException_leavesUntouched() throws Exception {
    CountDownLatch invoked = new CountDownLatch(1);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invoked.countDown();
              throw new EnvironmentCapabilitySendUncertainException("send uncertain");
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(invoked.await(5, TimeUnit.SECONDS));

    verify(repository, never()).rescheduleUnsent(any(), any(), any());
    verify(coordinator, never()).coordinateTransportUnknown(any(), any(), any());
  }

  /** 测试意图：验证 transport.invoke 抛出常规 RuntimeException 时协调为 UNKNOWN。 */
  @Test
  void invoke_runtimeException_coordinatesTransportUnknown() throws Exception {
    CountDownLatch coordinated = new CountDownLatch(1);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              throw new RuntimeException("unexpected network drop");
            });

    when(coordinator.coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken)))
        .thenAnswer(
            invocation -> {
              coordinated.countDown();
              return null;
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(coordinated.await(5, TimeUnit.SECONDS));

    verify(coordinator).coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken));
  }

  /** 测试意图：验证 transport.invoke 返回 null 句柄时协调为 UNKNOWN。 */
  @Test
  void invoke_returnsNullHandle_coordinatesTransportUnknown() throws Exception {
    CountDownLatch coordinated = new CountDownLatch(1);

    when(repository.claimPendingWithTimeout(eq(nodeId), eq(50)))
        .thenReturn(List.of(createClaimedOperation(opId)))
        .thenReturn(List.of());

    when(transport.invoke(any(), any(), any())).thenAnswer(invocation -> null);

    when(coordinator.coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken)))
        .thenAnswer(
            invocation -> {
              coordinated.countDown();
              return null;
            });

    dispatcher.start();
    dispatcher.wake();
    assertTrue(coordinated.await(5, TimeUnit.SECONDS));

    verify(coordinator).coordinateTransportUnknown(eq(opId), eq(nodeId), eq(leaseToken));
  }

  /** 测试意图：验证 nodeId 与活跃句柄数获取接口行为。 */
  @Test
  void getNodeId_and_getActiveHandleCount() {
    assertEquals(nodeId, dispatcher.getNodeId());
    assertEquals(0, dispatcher.getActiveHandleCount());
  }
}
