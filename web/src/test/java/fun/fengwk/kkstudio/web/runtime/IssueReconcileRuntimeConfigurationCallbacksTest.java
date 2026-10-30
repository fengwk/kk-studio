package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import fun.fengwk.kkstudio.project.reconcile.IssueReconcileProperties;
import fun.fengwk.kkstudio.project.reconcile.IssueReconciler;
import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;
import fun.fengwk.kkstudio.work.DurableWork;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * Issue 调度配置的拒绝归还、异常重排与租约围栏。
 *
 * <p>共享调度骨架已经覆盖容量、wake 合并与 stop；这里只验证配置回调仍按 rejection/retry 延迟重排，且失权时不写入。
 */
class IssueReconcileRuntimeConfigurationCallbacksTest {

  private static final Instant LEASE_UNTIL = Instant.parse("2026-09-27T10:00:30Z");

  /** 工作者拒绝时按 rejectionDelay 归还仍持有的租约，且不再把 claim 交给 reconciler。 */
  @Test
  void workerExecutorRejectionReturnsOwnedClaim() {
    DurableWorkStore workStore = mock(DurableWorkStore.class);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    IssueReconcileProperties properties = properties();
    properties.setRejectionDelay(Duration.ofSeconds(3));
    Executor rejectingWorker =
        task -> {
          throw new RejectedExecutionException("worker pool saturated");
        };
    BoundedWorkDispatcher<WorkClaim> dispatcher =
        dispatcher(workStore, reconciler, properties, Runnable::run, rejectingWorker);

    WorkClaim work = claim();
    when(workStore.claimNext(anyString(), any(Duration.class))).thenReturn(Optional.of(work));
    when(workStore.lockClaimed(work)).thenReturn(Optional.of(mock(DurableWork.class)));

    dispatcher.start();

    verify(workStore, times(1)).claimNext(anyString(), any(Duration.class));
    verify(workStore).reschedule(eq(work), eq(Duration.ofSeconds(3)));
    verifyNoInteractions(reconciler);
    dispatcher.stop();
  }

  /** 租约已失权时拒绝归还必须跳过 reschedule。 */
  @Test
  void workerExecutorRejectionDoesNotReturnALostClaim() {
    DurableWorkStore workStore = mock(DurableWorkStore.class);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    BoundedWorkDispatcher<WorkClaim> dispatcher =
        dispatcher(
            workStore,
            reconciler,
            properties(),
            Runnable::run,
            task -> {
              throw new RejectedExecutionException("worker pool saturated");
            });
    when(workStore.claimNext(anyString(), any(Duration.class))).thenReturn(Optional.of(claim()));
    when(workStore.lockClaimed(any())).thenReturn(Optional.empty());

    dispatcher.start();

    verify(workStore, times(0)).reschedule(any(), any());
    dispatcher.stop();
  }

  /** reconciler 异常按 retryDelay 重排；重排自身失败只留租约自愈，不向外传播。 */
  @Test
  void reconcilerFailureReschedulesWithRetryDelayAndSwallowsRescheduleError() {
    DurableWorkStore workStore = mock(DurableWorkStore.class);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    IssueReconcileProperties properties = properties();
    properties.setRetryDelay(Duration.ofSeconds(5));
    BoundedWorkDispatcher<WorkClaim> dispatcher =
        dispatcher(workStore, reconciler, properties, Runnable::run, Runnable::run);

    WorkClaim work = claim();
    when(workStore.claimNext(anyString(), any(Duration.class)))
        .thenReturn(Optional.of(work), Optional.empty());
    when(workStore.lockClaimed(work)).thenReturn(Optional.of(mock(DurableWork.class)));
    when(reconciler.reconcile(work))
        .thenThrow(new RuntimeException("transient reconciler failure"));
    doThrow(new RuntimeException("store connection down"))
        .when(workStore)
        .reschedule(any(WorkClaim.class), any(Duration.class));

    assertDoesNotThrow(dispatcher::start);

    verify(workStore).reschedule(eq(work), eq(Duration.ofSeconds(5)));
    dispatcher.stop();
  }

  /** stop 后尚未 handoff 的 claim 按 rejectionDelay 归还，且不会进入 reconciler。 */
  @Test
  void stopReturnsUnhandedOffClaim() {
    DurableWorkStore workStore = mock(DurableWorkStore.class);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    IssueReconcileProperties properties = properties();
    properties.setRejectionDelay(Duration.ofSeconds(1));
    BoundedWorkDispatcher<WorkClaim> dispatcher =
        dispatcher(workStore, reconciler, properties, Runnable::run, Runnable::run);

    WorkClaim work = claim();
    when(workStore.claimNext(anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              dispatcher.stop();
              return Optional.of(work);
            });
    when(workStore.lockClaimed(work)).thenReturn(Optional.of(mock(DurableWork.class)));

    dispatcher.start();

    verify(workStore).reschedule(eq(work), eq(Duration.ofSeconds(1)));
    verifyNoInteractions(reconciler);
  }

  /** worker executor 在接收前抛出的 Error 必须归还 claim，并把 Error 原样传播。 */
  @Test
  void workerExecutorErrorReturnsClaim() {
    DurableWorkStore workStore = mock(DurableWorkStore.class);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    BoundedWorkDispatcher<WorkClaim> dispatcher =
        dispatcher(
            workStore,
            reconciler,
            properties(),
            Runnable::run,
            task -> {
              throw new AssertionError("worker executor assertion error");
            });
    WorkClaim work = claim();
    when(workStore.claimNext(anyString(), any(Duration.class))).thenReturn(Optional.of(work));
    when(workStore.lockClaimed(work)).thenReturn(Optional.of(mock(DurableWork.class)));

    assertThrows(AssertionError.class, dispatcher::start);
    verify(workStore).reschedule(eq(work), any(Duration.class));
  }

  /** 归还路径自身失败不得打断 stop，后续仍可关闭调度器。 */
  @Test
  void returnFailureDoesNotEscapeTheDispatcher() {
    DurableWorkStore workStore = mock(DurableWorkStore.class);
    IssueReconciler reconciler = mock(IssueReconciler.class);
    BoundedWorkDispatcher<WorkClaim> dispatcher =
        dispatcher(
            workStore,
            reconciler,
            properties(),
            Runnable::run,
            task -> {
              throw new RejectedExecutionException("worker pool saturated");
            });
    when(workStore.claimNext(anyString(), any(Duration.class))).thenReturn(Optional.of(claim()));
    when(workStore.lockClaimed(any())).thenThrow(new RuntimeException("lock failed"));

    assertDoesNotThrow(dispatcher::start);
    assertEquals(0, dispatcher.inFlightCount());
    dispatcher.stop();
  }

  private static BoundedWorkDispatcher<WorkClaim> dispatcher(
      DurableWorkStore workStore,
      IssueReconciler reconciler,
      IssueReconcileProperties properties,
      Executor drainExecutor,
      Executor workerExecutor) {
    ScheduledExecutorService pollScheduler = mock(ScheduledExecutorService.class);
    when(pollScheduler.scheduleWithFixedDelay(any(), any(Long.class), any(Long.class), any()))
        .thenReturn(mock(ScheduledFuture.class));
    return new IssueReconcileRuntimeConfiguration()
        .issueReconcileDispatcher(
            workStore,
            reconciler,
            properties,
            drainExecutor,
            workerExecutor,
            pollScheduler,
            new CallbackTransactionManager());
  }

  /** 只执行 TransactionTemplate 回调，不引入真实数据库事务。 */
  private static final class CallbackTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() throws TransactionException {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition)
        throws TransactionException {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {}
  }

  private static IssueReconcileProperties properties() {
    return new IssueReconcileProperties();
  }

  private static WorkClaim claim() {
    return new WorkClaim(UUID.randomUUID(), 1L, "lease", LEASE_UNTIL);
  }
}
