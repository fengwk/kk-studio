package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** {@link ToolExecutionGateway} 构造：不注入默认目录、unsafe executor 策略（inline / CallerRuns / 静默丢弃）的拒绝。 */
class ToolExecutionGatewayConstructionTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");

  /** 测试意图：gateway 不持有任何默认目录；展开 host 调用时也不得凭空出现 backend 侧默认路径。 */
  @Test
  void gatewayInjectsNoDefaultWorkdir() {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(new ToolGatewayTestSupport.FakeTool(DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.settings(PermissionAction.ASK));
    ToolGateway.Ask ask =
        assertInstanceOf(
            ToolGateway.Ask.class,
            gateway.preflight(ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)));
    assertFalse(ask.reason().contains("(default)"), ask.reason());
    assertFalse(ask.reason().contains(" in "), ask.reason());
  }

  @Test
  void callerRunsPolicyExecutorIsRejectedAtConstruction() {
    ExecutorService executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    try {
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  ToolGatewayTestSupport.gateway(
                      ToolGatewayTestSupport.defaultCatalog(),
                      new ToolGatewayTestSupport.FakeTransport(),
                      new ToolGatewayTestSupport.FakeResourceStager(),
                      executor));
      assertTrue(error.getMessage().contains("non-inline executor"), error.getMessage());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void silentDiscardPolicyExecutorsAreRejectedAtConstruction() {
    List<RejectedExecutionHandler> handlers =
        List.of(
            new ThreadPoolExecutor.DiscardPolicy(), new ThreadPoolExecutor.DiscardOldestPolicy());
    for (RejectedExecutionHandler handler : handlers) {
      ExecutorService executor =
          new ThreadPoolExecutor(
              1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), handler);
      try {
        IllegalStateException error =
            assertThrows(
                IllegalStateException.class,
                () ->
                    ToolGatewayTestSupport.gateway(
                        ToolGatewayTestSupport.defaultCatalog(),
                        new ToolGatewayTestSupport.FakeTransport(),
                        new ToolGatewayTestSupport.FakeResourceStager(),
                        executor));
        assertTrue(error.getMessage().contains("silent discard"), error.getMessage());
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void inlineExecutorIsRejectedAtConstruction() {
    ToolGatewayTestSupport.ImmediateExecutor executor =
        new ToolGatewayTestSupport.ImmediateExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                ToolGatewayTestSupport.gateway(
                    ToolGatewayTestSupport.defaultCatalog(),
                    new ToolGatewayTestSupport.FakeTransport(),
                    new ToolGatewayTestSupport.FakeResourceStager(),
                    executor));
    assertTrue(error.getMessage().contains("non-inline executor"), error.getMessage());
  }

  @Test
  void plainThreadPoolExecutorWithDefaultAbortPolicyIsAccepted() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ExecutorService executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.defaultCatalog(tool),
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStager(),
              executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
      startedResult.handle().activate();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (tool.requests.isEmpty() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertFalse(tool.requests.isEmpty(), "tool must execute on the pool");
    } finally {
      executor.shutdownNow();
    }
  }
}
