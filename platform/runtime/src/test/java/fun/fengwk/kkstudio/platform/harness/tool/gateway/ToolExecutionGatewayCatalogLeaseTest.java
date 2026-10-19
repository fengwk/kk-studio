package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 目录租用生命周期测试：取消/拒绝与真正开始执行之间不存在 TOCTOU 窗口，且租用只在终态决议、执行体退出与该调用静止全部成立后释放。
 *
 * <p>两个方向都被独立固定：
 *
 * <ul>
 *   <li>取消先赢：{@link ToolExecutionGateway.ToolResourceLease#tryStart()} 拒绝启动，执行体绝不触碰工具与插件资源；
 *   <li>执行先赢：取消只登记决议，释放必须等真实执行线程退出与该调用的 {@link ToolExecutionHandle#quiescence()} 完成。
 * </ul>
 *
 * <p>句柄违反静止契约（抛错或返回 null）时必须转为可观测的 UNKNOWN 终态，绝不能变成 executor 里无人处理的异常让调用永远 RUNNING；
 * 同时绝不假称静止，因此租用保持钉住。
 */
class ToolExecutionGatewayCatalogLeaseTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");

  /** 取消先赢：目录租用立即释放，且后续 tryStart 绝不获准。 */
  @Test
  void cancelBeforeStartReleasesLeaseAndRefusesStart() {
    TestRuntimeToolCatalogSource source = source();
    ToolExecutionGateway.ToolResourceLease resources =
        new ToolExecutionGateway.ToolResourceLease(source.acquire());

    resources.resolve();

    assertFalse(resources.tryStart(), "已决议的调用绝不获准进入执行段");
    assertEquals(1, source.openLeases());
    assertEquals(1, source.closedLeases(), "取消先赢时没有任何在途工作，租用立即释放");
  }

  /** 执行先赢：释放必须同时等到执行体退出与句柄静止，且重复动作绝不重复释放。 */
  @Test
  void startWinsSoReleaseWaitsForRunnerExitAndQuiescence() {
    TestRuntimeToolCatalogSource source = source();
    ToolExecutionGateway.ToolResourceLease resources =
        new ToolExecutionGateway.ToolResourceLease(source.acquire());
    ControllableHandle handle = new ControllableHandle();

    assertTrue(resources.tryStart(), "未决议时执行获准进入");
    resources.attach(handle);
    resources.resolve();
    assertEquals(0, source.closedLeases(), "执行体仍在运行时不得释放目录租用");

    resources.runnerFinished();
    assertEquals(0, source.closedLeases(), "句柄尚未静止时不得释放目录租用");

    handle.completeQuiescence();
    assertEquals(1, source.closedLeases(), "执行体退出且静止成立后恰好释放一次");

    resources.resolve();
    resources.runnerFinished();
    handle.completeQuiescence();
    assertEquals(1, source.closedLeases(), "重复决议与重复静止绝不重复释放");
  }

  /** 没有句柄的调用（同步终结）：释放只需终态决议与执行体退出。 */
  @Test
  void releaseWithoutHandleHappensAtRunnerExit() {
    TestRuntimeToolCatalogSource source = source();
    ToolExecutionGateway.ToolResourceLease resources =
        new ToolExecutionGateway.ToolResourceLease(source.acquire());

    assertTrue(resources.tryStart());
    resources.resolve();
    assertEquals(0, source.closedLeases(), "执行体未退出前不得释放");
    resources.runnerFinished();
    assertEquals(1, source.closedLeases());
  }

  /** 违反静止契约的句柄绝不被当作静止：可观测失败 + 租用保持钉住，避免插件在异步工作仍进行时被释放。 */
  @Test
  void brokenHandleNeverReportsQuiescence() {
    TestRuntimeToolCatalogSource source = source();
    ToolExecutionGateway.ToolResourceLease resources =
        new ToolExecutionGateway.ToolResourceLease(source.acquire());
    ToolExecutionHandle nullStage =
        new ToolExecutionHandle() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }

          @Override
          public CompletionStage<Void> quiescence() {
            return null;
          }
        };

    assertTrue(resources.tryStart());
    assertThrows(IllegalStateException.class, () -> resources.attach(nullStage));

    resources.resolve();
    resources.runnerFinished();
    assertEquals(0, source.closedLeases(), "静止不可知时绝不释放被本次调用钉住的插件实例");
  }

  /** 取消发生在执行体进入之前时，工具绝不被调用，且租用恰好释放一次。 */
  @Test
  void cancellationBeforeWorkerEntryNeverExecutesTool() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    TestRuntimeToolCatalogSource source =
        TestRuntimeToolCatalogSource.of(ToolGatewayTestSupport.defaultCatalog(tool));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            source,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            executor,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            new ConcurrencyAdmission(Integer.MAX_VALUE));

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(hostRequest()),
                new ToolGatewayTestSupport.RecordingListener()));

    // 激活后才取消：worker 尚未被调度执行，取消必须在它进入执行段前赢下裁决。
    started.handle().activate();
    started.handle().cancel();
    executor.drain();

    assertTrue(tool.requests.isEmpty(), "取消先赢时绝不触碰工具");
    assertEquals(1, source.closedLeases(), "没有任何在途工作，租用立即释放");
  }

  /** 执行体进入后取消：必须等真实执行线程退出与句柄静止才释放租用。 */
  @Test
  void cancellationAfterStartWaitsForBodyAndQuiescence() {
    DeferredBodyTool tool = new DeferredBodyTool();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    TestRuntimeToolCatalogSource source =
        TestRuntimeToolCatalogSource.of(ToolGatewayTestSupport.defaultCatalog(tool));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            source,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            executor,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            new ConcurrencyAdmission(Integer.MAX_VALUE));
    try {
      ToolGateway.Started started =
          assertInstanceOf(
              ToolGateway.Started.class,
              gateway.start(
                  ToolGatewayTestSupport.execution(hostRequest()),
                  new ToolGatewayTestSupport.RecordingListener()));
      started.handle().activate();
      tool.awaitBodyEntered();

      started.handle().cancel();
      assertEquals(0, source.closedLeases(), "执行线程仍在访问本地资源，取消不得释放租用");

      tool.releaseBody();
      tool.awaitBodyExited();
      assertEquals(0, source.closedLeases(), "执行体已退出但该调用尚未静止，仍不得释放租用");

      tool.completeQuiescence();
      awaitTrue(() -> source.closedLeases() == 1, "静止成立后才释放租用");
    } finally {
      tool.releaseBody();
      executor.shutdownNow();
    }
  }

  /** 句柄静止事实不可得时，调用仍必须拿到可观测的 UNKNOWN 终态，且绝不因无处理异常卡在 RUNNING。 */
  @Test
  void brokenQuiescenceStageStillDeliversUnknownTerminalAndKeepsLeasePinned() {
    for (boolean throwing : List.of(true, false)) {
      Tool tool = brokenHandleTool(throwing);
      ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
      TestRuntimeToolCatalogSource source =
          TestRuntimeToolCatalogSource.of(ToolGatewayTestSupport.defaultCatalog(tool));
      ToolGatewayTestSupport.RecordingListener listener =
          new ToolGatewayTestSupport.RecordingListener();
      ToolGatewayTestSupport.DirectQueueExecutor executor =
          new ToolGatewayTestSupport.DirectQueueExecutor();
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              source,
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStager(),
              executor,
              ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
              ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
              admission);

      ToolGateway.Started started =
          assertInstanceOf(
              ToolGateway.Started.class,
              gateway.start(ToolGatewayTestSupport.execution(hostRequest()), listener));
      started.handle().activate();
      executor.drain();

      assertEquals(1, listener.terminalInvocations.get(), "违反契约的句柄仍必须有终态，绝不掉回调");
      ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
          assertInstanceOf(
              ToolGatewayTestSupport.RecordingListener.Event.Unknown.class, listener.events.get(0));
      assertEquals(ToolExecutionGateway.EXECUTION_FAILED_KIND, unknown.error().kind());
      assertTrue(
          unknown.error().message().contains("Whether the tool took effect cannot be confirmed."),
          "静止不可知必须保持 UNKNOWN，绝不声称未执行或已静止");
      assertEquals(0, source.closedLeases(), "静止不可知时不释放被本次调用钉住的插件实例");
      assertTrue(admission.tryAcquire().isPresent(), "并发租用仍按终态释放，不因静止不可知而泄漏");
    }
  }

  /** 执行体的致命异常既保留原错误，也先报告 UNKNOWN，不能遗留 RUNNING。 */
  @Test
  void fatalWorkerFailureReportsUnknownBeforePropagating() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    AssertionError fatal = new AssertionError("worker failed");
    tool.handler =
        (request, listener) -> {
          throw fatal;
        };
    TestRuntimeToolCatalogSource source =
        TestRuntimeToolCatalogSource.of(ToolGatewayTestSupport.defaultCatalog(tool));
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            source,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            executor,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            new ConcurrencyAdmission(1));
    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(ToolGatewayTestSupport.execution(hostRequest()), listener));
    started.handle().activate();
    assertEquals(fatal, assertThrows(AssertionError.class, executor::drain));
    assertEquals(1, listener.terminalInvocations.get());
    assertInstanceOf(
        ToolGatewayTestSupport.RecordingListener.Event.Unknown.class, listener.events.get(0));
    assertEquals(1, source.closedLeases());
  }

  private static TestRuntimeToolCatalogSource source() {
    return TestRuntimeToolCatalogSource.of(ToolGatewayTestSupport.defaultCatalog());
  }

  private static ToolInvocationRequest hostRequest() {
    return ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);
  }

  private static Tool brokenHandleTool(boolean throwing) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return DESCRIPTOR;
      }

      @Override
      public ToolRequirements requirements() {
        return ToolRequirements.none();
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return new ToolExecutionHandle() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return false;
          }

          @Override
          public CompletionStage<Void> quiescence() {
            if (throwing) {
              throw new IllegalStateException("contributor cannot report quiescence");
            }
            return null;
          }
        };
      }
    };
  }

  /** 执行体阻塞在本地调用中、静止由测试显式给出的 Tool：真实执行线程仍在访问资源。 */
  private static final class DeferredBodyTool implements Tool {
    private final AtomicBoolean releaseBody = new AtomicBoolean();
    private final AtomicBoolean bodyExited = new AtomicBoolean();
    private final CountDownLatch bodyEntered = new CountDownLatch(1);
    private final CompletableFuture<Void> quiescence = new CompletableFuture<>();

    @Override
    public ToolDescriptor descriptor() {
      return DESCRIPTOR;
    }

    @Override
    public ToolRequirements requirements() {
      return ToolRequirements.none();
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      bodyEntered.countDown();
      try {
        while (!releaseBody.get()) {
          Thread.onSpinWait();
        }
      } finally {
        bodyExited.set(true);
      }
      return new ControllableHandle(quiescence);
    }

    void awaitBodyEntered() {
      awaitLatch(bodyEntered);
    }

    void releaseBody() {
      releaseBody.set(true);
    }

    void awaitBodyExited() {
      awaitTrue(bodyExited::get, "tool body did not exit");
    }

    void completeQuiescence() {
      quiescence.complete(null);
    }
  }

  /** 由测试显式控制静止事实的句柄。 */
  private static final class ControllableHandle implements ToolExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<Void> quiescence;

    ControllableHandle() {
      this(new CompletableFuture<>());
    }

    ControllableHandle(CompletableFuture<Void> quiescence) {
      this.quiescence = quiescence;
    }

    void completeQuiescence() {
      quiescence.complete(null);
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public CompletionStage<Void> quiescence() {
      return quiescence;
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("latch timed out");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private static void awaitTrue(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError(what);
      }
      Thread.onSpinWait();
    }
  }
}
