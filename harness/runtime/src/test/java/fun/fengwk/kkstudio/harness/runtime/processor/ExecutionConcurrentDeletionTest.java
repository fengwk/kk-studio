package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 回归：持根树锁的合法并发深删除在 Model/Tool 执行取得本 Thread 行锁之前提交时，进程内执行必须映射 {@link
 * ProcessResult#LOST_OWNERSHIP}——不把合法缺失当作结构漂移抛出，不留下 durable 写入或实时事件，并释放本地执行资源。 单 Thread
 * 状态提交不再取执行树锁，因此删除点收敛到 own Thread 行锁。
 */
class ExecutionConcurrentDeletionTest {

  private final List<ScheduledExecutorService> schedulersToClose = new CopyOnWriteArrayList<>();

  @AfterEach
  void tearDown() {
    for (ScheduledExecutorService scheduler : schedulersToClose) {
      scheduler.shutdownNow();
    }
  }

  @Test
  void toolActivationLosesOwnershipWhenThreadDeletedBeforeOwnThreadLock() {
    ToolExecutionFixture fixture = toolExecutionFixture();
    long versionBefore = threadVersion(fixture.rawStore, fixture.threadId);

    fixture.store.armDeletionBeforeNextThreadLock();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.execution.activate(fixture.handle));

    fixture.assertLostWithStatus(ToolInvocationStatus.DISPATCHING, versionBefore);
  }

  @Test
  void toolTerminalCallbackLosesOwnershipWhenThreadDeletedBeforeOwnThreadLock() {
    ToolExecutionFixture fixture = toolExecutionFixture();
    assertEquals(ProcessResult.STARTED, fixture.execution.activate(fixture.handle));
    long versionBefore = threadVersion(fixture.rawStore, fixture.threadId);

    fixture.store.armDeletionBeforeNextThreadLock();
    fixture.execution.onSucceeded(
        ToolSuccess.withoutEffects(
            ToolProcessorTestSupport.successResult("call-1", new TextResultContent("ok"))));

    fixture.assertLostWithStatus(ToolInvocationStatus.RUNNING, versionBefore);
  }

  @Test
  void modelActivationLosesOwnershipWhenThreadDeletedBeforeOwnThreadLock() {
    ModelExecutionFixture fixture = modelExecutionFixture(false);
    long versionBefore = threadVersion(fixture.rawStore, fixture.threadId);

    fixture.store.armDeletionBeforeNextThreadLock();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.execution.activate(fixture.handle));

    fixture.assertLostWithStatus(ModelInvocationStatus.DISPATCHING, versionBefore);
    assertNull(modelResult(fixture.rawStore, fixture.invocationId));
  }

  @Test
  void modelSuccessCallbackLosesOwnershipWhenThreadDeletedBeforeOwnThreadLock() {
    ModelExecutionFixture fixture = modelExecutionFixture(true);
    long versionBefore = threadVersion(fixture.rawStore, fixture.threadId);

    fixture.store.armDeletionBeforeNextThreadLock();
    fixture.execution.onSucceeded(new ProviderCompletion(response("answer")));

    fixture.assertLostWithStatus(ModelInvocationStatus.RUNNING, versionBefore);
    assertNull(modelResult(fixture.rawStore, fixture.invocationId));
  }

  /** DISPATCHING 的 Tool 链 + 未激活的进程内 execution（由测试决定激活或直接投递回调）。 */
  private ToolExecutionFixture toolExecutionFixture() {
    ToolProcessorTestSupport.Fixture support = ToolProcessorTestSupport.fixture();
    schedulersToClose.add(support.scheduler);
    ClaimedWork claim =
        ToolProcessorTestSupport.claim(
            support.store, support.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.toDispatched(
        support.store, support.toolInvocationId, ToolProcessorTestSupport.NOW);
    DeletionBeforeThreadLockStore store =
        new DeletionBeforeThreadLockStore(support.store, support.baseline.threadId());
    AtomicInteger releases = new AtomicInteger();
    ToolExecution execution =
        new ToolExecution(
            store,
            support.sink,
            claim,
            support.baseline.threadId(),
            1,
            support.request,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            support.clock,
            support.scheduler,
            Runnable::run,
            ignored -> releases.incrementAndGet());
    return new ToolExecutionFixture(
        support.store,
        support.sink,
        support.toolInvocationId,
        support.baseline.threadId(),
        store,
        execution,
        new ToolProcessorTestSupport.FakeHandle(),
        releases);
  }

  private record ToolExecutionFixture(
      InMemoryHarnessStore rawStore,
      ToolProcessorTestSupport.RecordingSink sink,
      UUID toolInvocationId,
      UUID threadId,
      DeletionBeforeThreadLockStore store,
      ToolExecution execution,
      ToolProcessorTestSupport.FakeHandle handle,
      AtomicInteger releases) {

    /** LOST 语义：handle 取消、本地槽位释放、无实时事件、durable 状态与 Thread version 不变。 */
    void assertLostWithStatus(ToolInvocationStatus expectedStatus, long versionBefore) {
      store.assertNoTransactionFailure();
      assertTrue(handle.isCancelled(), "lost ownership must cancel the attached handle");
      assertEquals(1, releases.get(), "lost ownership must release the local execution slot");
      assertTrue(sink.events.isEmpty(), "no realtime event may leak");
      assertEquals(
          expectedStatus,
          ToolProcessorTestSupport.tool(rawStore, toolInvocationId).status(),
          "no durable state may be persisted");
      assertEquals(
          versionBefore, threadVersion(rawStore, threadId), "thread version must not advance");
    }
  }

  /** 一条 READY → DISPATCHING 的 Model 链 + 进程内 execution；{@code activated} 决定是否先激活到 RUNNING。 */
  private ModelExecutionFixture modelExecutionFixture(boolean activated) {
    InMemoryHarnessStore rawStore = new InMemoryHarnessStore();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    schedulersToClose.add(scheduler);
    ThreadProcessorTestSupport.OpenTurnBaseline turn =
        ThreadProcessorTestSupport.seedOpenInputTurn(rawStore);
    UUID threadId = turn.threadId();
    UUID invocationId =
        rawStore.transaction(
            tx -> {
              tx.lockThread(threadId);
              UUID id = tx.nextId();
              tx.insertModelInvocation(
                  new ModelInvocation(
                      id,
                      threadId,
                      turn.turnStartEntryId(),
                      turn.userEntryId(),
                      ThreadProcessorTestSupport.plainRequest(),
                      ModelInvocationStatus.READY,
                      0,
                      null,
                      null,
                      null,
                      null,
                      List.of(),
                      ThreadProcessorTestSupport.NOW,
                      ThreadProcessorTestSupport.NOW));
              tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id));
              return id;
            });
    ClaimedWork claim =
        rawStore
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.MODEL, "token-" + invocationId, Duration.ofSeconds(60)))
            .orElseThrow();
    rawStore.transaction(
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
          tx.updateModelInvocation(model.beginDispatch(ThreadProcessorTestSupport.NOW));
          return null;
        });
    ModelExecutionFixture fixture =
        new ModelExecutionFixture(
            rawStore,
            new DeletionBeforeThreadLockStore(rawStore, threadId),
            threadId,
            invocationId,
            claim,
            scheduler);
    if (activated) {
      assertEquals(ProcessResult.STARTED, fixture.execution.activate(fixture.handle));
    }
    return fixture;
  }

  private final class ModelExecutionFixture {

    final InMemoryHarnessStore rawStore;
    final UUID threadId;
    final UUID invocationId;
    final AtomicInteger releases = new AtomicInteger();
    final List<Object> events = new CopyOnWriteArrayList<>();
    final DeletionBeforeThreadLockStore store;
    final ModelExecution execution;
    final ModelHandle handle = new ModelHandle();

    ModelExecutionFixture(
        InMemoryHarnessStore rawStore,
        DeletionBeforeThreadLockStore store,
        UUID threadId,
        UUID invocationId,
        ClaimedWork claim,
        ScheduledExecutorService scheduler) {
      this.rawStore = rawStore;
      this.store = store;
      this.threadId = threadId;
      this.invocationId = invocationId;
      this.execution =
          new ModelExecution(
              store,
              events::add,
              claim,
              threadId,
              1,
              false,
              ThreadProcessorTestSupport.plainRequest().toolBindings(),
              new ModelProcessorConfig(
                  ThreadProcessorTestSupport.LEASE_CONFIG,
                  () -> ToolProcessorTestSupport.NO_RETRY,
                  Duration.ofSeconds(1),
                  new ModelStreamConfig(Duration.ofMillis(1), 1, 1, Duration.ofMinutes(10))),
              "test-provider",
              Clock.fixed(ThreadProcessorTestSupport.NOW, ZoneOffset.UTC),
              scheduler,
              Runnable::run,
              Runnable::run,
              ignored -> releases.incrementAndGet());
    }

    /** LOST 语义：handle 取消、本地槽位释放、无实时事件、durable 状态与 Thread version 不变。 */
    void assertLostWithStatus(ModelInvocationStatus expectedStatus, long versionBefore) {
      store.assertNoTransactionFailure();
      assertTrue(handle.isCancelled(), "lost ownership must cancel the attached handle");
      assertEquals(1, releases.get(), "lost ownership must release the local execution slot");
      assertTrue(events.isEmpty(), "no realtime event may leak");
      assertEquals(
          expectedStatus, modelStatus(rawStore, invocationId), "no durable state may be persisted");
      assertEquals(
          versionBefore, threadVersion(rawStore, threadId), "thread version must not advance");
    }
  }

  private static final class ModelHandle implements ModelGateway.Handle {

    private final AtomicInteger cancels = new AtomicInteger();

    @Override
    public void cancel() {
      cancels.incrementAndGet();
    }

    @Override
    public void activate() {}

    boolean isCancelled() {
      return cancels.get() > 0;
    }
  }

  private static long threadVersion(InMemoryHarnessStore store, UUID threadId) {
    return ToolProcessorTestSupport.thread(store, threadId).version();
  }

  private static ModelInvocationStatus modelStatus(InMemoryHarnessStore store, UUID invocationId) {
    return store.transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow().status();
  }

  private static Object modelResult(InMemoryHarnessStore store, UUID invocationId) {
    return store.transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow().result();
  }

  private static ProviderResponse response(String text) {
    return new ProviderResponse(
        text,
        "",
        List.of(),
        GenerationStopReason.COMPLETE,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        "req-1",
        null,
        "{}");
  }

  /**
   * 在本 Thread 行锁获取点提交并发深删除：第一次 {@code lockThread} 即视为删除事务已先提交，返回空；其后的线程读取一律返回空。 这验证单 Thread 状态提交仅凭
   * own Thread 行锁即可与 delete 线性化。
   */
  private static final class DeletionBeforeThreadLockStore implements HarnessStore {

    private final InMemoryHarnessStore delegate;
    private final UUID deletedThreadId;
    private final AtomicInteger failedTransactions = new AtomicInteger();
    private boolean armed;
    private boolean deleted;

    DeletionBeforeThreadLockStore(InMemoryHarnessStore delegate, UUID deletedThreadId) {
      this.delegate = delegate;
      this.deletedThreadId = deletedThreadId;
    }

    /** 下一次锁定本 Thread 前提交删除；模拟删除先于单实体回写线性化。 */
    void armDeletionBeforeNextThreadLock() {
      armed = true;
    }

    /** 合法并发删除绝不能以事务异常（结构漂移 / 回滚）形式浮出。 */
    void assertNoTransactionFailure() {
      assertEquals(
          0, failedTransactions.get(), "legal concurrent deletion must not fail the transaction");
    }

    @Override
    public void afterCommit(Runnable action) {
      delegate.afterCommit(action);
    }

    @Override
    public void assertNoAmbientTransaction() {
      delegate.assertNoAmbientTransaction();
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      return delegate.transaction(
          tx -> {
            try {
              return callback.apply(
                  (Transaction)
                      Proxy.newProxyInstance(
                          Transaction.class.getClassLoader(),
                          new Class<?>[] {Transaction.class},
                          (proxy, method, args) -> {
                            String name = method.getName();
                            if (armed
                                && name.equals("lockThread")
                                && args != null
                                && deletedThreadId.equals(args[0])) {
                              armed = false;
                              deleted = true;
                              return Optional.empty();
                            }
                            if (deleted && args != null && deletedThreadId.equals(args[0])) {
                              if (name.equals("findAncestorChain")) {
                                return List.of();
                              }
                              if (name.equals("findThread") || name.equals("lockThread")) {
                                return Optional.empty();
                              }
                            }
                            return invoke(tx, method, args);
                          }));
            } catch (RuntimeException | Error error) {
              failedTransactions.incrementAndGet();
              throw error;
            }
          });
    }

    private static Object invoke(Transaction tx, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(tx, args);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }
  }
}
