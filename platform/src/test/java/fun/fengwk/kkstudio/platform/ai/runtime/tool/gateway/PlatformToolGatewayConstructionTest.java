package fun.fengwk.kkstudio.platform.ai.runtime.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.platform.ai.runtime.configuration.HarnessRuntimeProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * {@link PlatformToolGateway} 构造：生产 {@link HarnessRuntimeProperties} 构造器与路径解析、unsafe executor
 * 策略（inline / CallerRuns / 静默丢弃）的拒绝。
 */
class PlatformToolGatewayConstructionTest {

  private static final ToolDescriptor DESCRIPTOR =
      ToolGatewayTestSupport.platformDescriptor("demo");

  @Test
  void productionPropertiesConstructorResolvesAndNormalizesWorkdir() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setEnvironmentRoot(Path.of("/env-root"));
    // 相对 workdir 按 environmentRoot 解析；"./deep" 中间冗余段被 normalize 折叠。
    properties.setWorkdir(Path.of("sub/./deep"));
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            properties,
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.settings(PermissionAction.ASK));
    // ASK preview 的 workdir 暴露了 gateway 实际使用的解析后路径。
    ToolGateway.Ask ask =
        assertInstanceOf(
            ToolGateway.Ask.class,
            gateway.preflight(ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)));
    assertTrue(ask.reason().contains("/env-root/sub/deep"), ask.reason());
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
                      ToolGatewayTestSupport.factories(),
                      new ToolGatewayTestSupport.FakeTransport(),
                      new ToolGatewayTestSupport.FakeResourceStore(),
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
                        ToolGatewayTestSupport.factories(),
                        new ToolGatewayTestSupport.FakeTransport(),
                        new ToolGatewayTestSupport.FakeResourceStore(),
                        executor));
        assertTrue(error.getMessage().contains("silent discard"), error.getMessage());
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void inlineExecutorIsRejectedAtConstruction() {
    ToolGatewayTestSupport.InlineExecutor executor = new ToolGatewayTestSupport.InlineExecutor();
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                ToolGatewayTestSupport.gateway(
                    ToolGatewayTestSupport.factories(),
                    new ToolGatewayTestSupport.FakeTransport(),
                    new ToolGatewayTestSupport.FakeResourceStore(),
                    executor));
    assertTrue(error.getMessage().contains("non-inline executor"), error.getMessage());
  }

  @Test
  void plainThreadPoolExecutorWithDefaultAbortPolicyIsAccepted() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ExecutorService executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      PlatformToolGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.factories(tool),
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStore(),
              executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
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
