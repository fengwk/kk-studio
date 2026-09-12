package fun.fengwk.kkstudio.platform.environment.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 验证 {@link EnvironmentOperationDispatcher} 的执行分发契约： 包含无配额虚拟线程调度、发送前确定性异常重调度
 * (rescheduleUnsent)、发送不确定性保持 RUNNING、 关机围栏取消与本地超期句柄取消。
 */
class EnvironmentOperationDispatcherTest {

  private UUID nodeId;
  private EnvironmentOperationRepository repository;
  private EnvironmentDaemonGateway gateway;
  private EnvironmentDaemonServer server;
  private EnvironmentOperationCompletionCoordinator coordinator;
  private EnvironmentOperationDispatcher dispatcher;

  private final UUID envId = UUID.randomUUID();
  private final UUID opId = UUID.randomUUID();
  private final UUID sourceId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    nodeId = UUID.randomUUID();
    repository = mock(EnvironmentOperationRepository.class);
    gateway = mock(EnvironmentDaemonGateway.class);
    server = mock(EnvironmentDaemonServer.class);
    when(gateway.server()).thenReturn(server);
    coordinator = mock(EnvironmentOperationCompletionCoordinator.class);
    dispatcher = new EnvironmentOperationDispatcher(nodeId, repository, gateway, coordinator);
  }

  private ClaimedOperation createClaimedOperation() {
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
            opId,
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

  /** 测试意图：验证当 invoke 抛出发送前确定性异常 (Unavailable / Busy) 时，操作被安全 rescheduleUnsent 回退为 PENDING。 */
  @Test
  void preSendUnavailableReschedulesUnsent() throws Exception {
    ClaimedOperation claimed = createClaimedOperation();
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    when(server.invoke(
            any(EnvironmentId.class),
            any(EnvironmentCapabilityExecutionRequest.class),
            any(EnvironmentCapabilityExecutionListener.class)))
        .thenThrow(new EnvironmentCapabilityUnavailableException("Daemon unavailable"));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              latch.countDown();
              return RescheduleOutcome.RESCHEDULED;
            })
        .when(repository)
        .rescheduleUnsent(opId, nodeId, leaseToken);

    dispatcher.start();
    assertTrue(latch.await(3, TimeUnit.SECONDS), "必须调用 rescheduleUnsent");
    dispatcher.stop();

    verify(repository).rescheduleUnsent(opId, nodeId, leaseToken);
    verify(coordinator, never())
        .coordinateResult(any(), any(), any(), any(), anyLong(), any(), anyLong(), any());
  }

  /** 测试意图：验证当 invoke 发生发送不确定性 (SendUncertainException) 时，严禁重放或退回 PENDING，保持 RUNNING 等待超时收敛。 */
  @Test
  void sendUncertaintyLeavesRunningForTimeoutConvergence() throws Exception {
    ClaimedOperation claimed = createClaimedOperation();
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    when(server.invoke(
            any(EnvironmentId.class),
            any(EnvironmentCapabilityExecutionRequest.class),
            any(EnvironmentCapabilityExecutionListener.class)))
        .thenThrow(
            new EnvironmentCapabilitySendUncertainException("Connection severed during send"));

    dispatcher.start();
    Thread.sleep(300);
    dispatcher.stop();

    // 绝不调用 rescheduleUnsent
    verify(repository, never()).rescheduleUnsent(eq(opId), any(), any());
    // 句柄不存在时不会调用 coordinator
    verify(coordinator, never())
        .coordinateResult(any(), any(), any(), any(), anyLong(), any(), anyLong(), any());
  }

  /** 测试意图：验证正常执行并由监听器回调完成时，协调器收到权威结果。 */
  @Test
  void normalExecutionCoordinatesResultViaListener() throws Exception {
    ClaimedOperation claimed = createClaimedOperation();
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    EnvironmentCapabilityResult result = EnvironmentCapabilityResult.success("call-1", "ok");
    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);

    CountDownLatch coordLatch = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              coordLatch.countDown();
              return null;
            })
        .when(coordinator)
        .coordinateResult(
            eq(envId), eq(opId), eq(nodeId), eq(leaseToken), eq(2L), eq(sourceId), eq(1L), any());

    when(server.invoke(
            any(EnvironmentId.class),
            any(EnvironmentCapabilityExecutionRequest.class),
            any(EnvironmentCapabilityExecutionListener.class)))
        .thenAnswer(
            invocation -> {
              EnvironmentCapabilityExecutionListener listener = invocation.getArgument(2);
              // 异步触发完成
              Thread.ofVirtual().start(() -> listener.onComplete(result));
              return handle;
            });

    dispatcher.start();
    assertTrue(coordLatch.await(3, TimeUnit.SECONDS), "必须调用 coordinator.coordinateResult");
    dispatcher.stop();

    verify(coordinator)
        .coordinateResult(
            eq(envId),
            eq(opId),
            eq(nodeId),
            eq(leaseToken),
            eq(2L),
            eq(sourceId),
            eq(1L),
            eq(result));
  }

  /** 测试意图：验证 stop 会取消所有活跃内存句柄，并在关机后阻止新的调用发起。 */
  @Test
  void stopCancelsActiveHandlesAndPreventsNewInvocation() throws Exception {
    ClaimedOperation claimed = createClaimedOperation();
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              cancelled.set(true);
              return null;
            })
        .when(handle)
        .cancel();

    CountDownLatch invokeLatch = new CountDownLatch(1);
    when(server.invoke(
            any(EnvironmentId.class),
            any(EnvironmentCapabilityExecutionRequest.class),
            any(EnvironmentCapabilityExecutionListener.class)))
        .thenAnswer(
            invocation -> {
              invokeLatch.countDown();
              return handle;
            });

    dispatcher.start();
    assertTrue(invokeLatch.await(3, TimeUnit.SECONDS), "必须发起 invoke");
    assertEquals(1, dispatcher.getActiveHandleCount());

    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
    assertEquals(0, dispatcher.getActiveHandleCount());
    assertTrue(cancelled.get(), "stop 必须调用 handle.cancel()");
  }

  /** 测试意图：验证 runSweeper 本地超时清扫能够取消内存中对应的活跃句柄。 */
  @Test
  void sweeperCancelsActiveHandleForExpiredOperation() throws Exception {
    ClaimedOperation claimed = createClaimedOperation();
    when(repository.claimPendingWithTimeout(eq(nodeId), anyInt()))
        .thenReturn(List.of(claimed))
        .thenReturn(List.of());

    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);
    CountDownLatch invokeLatch = new CountDownLatch(1);
    when(server.invoke(
            any(EnvironmentId.class),
            any(EnvironmentCapabilityExecutionRequest.class),
            any(EnvironmentCapabilityExecutionListener.class)))
        .thenAnswer(
            invocation -> {
              invokeLatch.countDown();
              return handle;
            });

    dispatcher.start();
    assertTrue(invokeLatch.await(3, TimeUnit.SECONDS));
    assertEquals(1, dispatcher.getActiveHandleCount());

    // 模拟本节点清扫发现 opId 已超时
    when(repository.sweepLocalExpiredRunning(nodeId))
        .thenReturn(List.of(new SweptOperationInfo(opId, nodeId, leaseToken)));

    dispatcher.runSweeper();

    verify(handle).cancel();
    assertEquals(0, dispatcher.getActiveHandleCount());

    dispatcher.stop();
  }
}
