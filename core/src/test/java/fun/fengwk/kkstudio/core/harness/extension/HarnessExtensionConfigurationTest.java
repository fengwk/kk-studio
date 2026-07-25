package fun.fengwk.kkstudio.core.harness.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.tool.service.HarnessToolConfiguration;
import fun.fengwk.kkstudio.core.harness.tool.worker.HarnessToolWorkerConfiguration;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtension;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolRegistry;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorkerConfig;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Extension Host 与 Tool worker 装配；Run worker 已删除，改为只验证 Thread 时代保留的扩展点。 */
class HarnessExtensionConfigurationTest {
  @Test
  void loadsCustomExtensionWithCoreAndBuildsLifecycleFromHost() {
    List<String> observed = new ArrayList<>();
    HarnessExtension customExtension = customExtension(observed);
    Tool tool = tool("custom", "1");
    CoreHarnessExtension core = new CoreHarnessExtension(permissionEvaluator(), List.of(tool));
    HarnessExtensionConfiguration configuration = new HarnessExtensionConfiguration();
    HarnessExtensionHost host = configuration.harnessExtensionHost(List.of(customExtension, core));

    try {
      assertEquals(4, host.providerFactories().size());
      assertEquals(1, host.lifecycleObservers().size());
      HarnessLifecycleObservers lifecycleObservers = configuration.harnessLifecycleObservers(host);
      lifecycleObservers.publish(
          new ToolCompleted(
              1L, 2L, InvocationStatus.SUCCEEDED, null, Instant.parse("2026-07-01T00:00:00Z")));
      assertEquals(List.of("custom"), observed);
    } finally {
      host.close();
    }
  }

  @Test
  void allSpringConfigurationsConsumeHostContributions() {
    Tool tool = tool("configured", "1");
    HarnessExtensionHost host =
        new HarnessExtensionConfiguration()
            .harnessExtensionHost(
                List.of(new CoreHarnessExtension(permissionEvaluator(), List.of(tool))));
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    try {
      HarnessToolConfiguration toolConfiguration = new HarnessToolConfiguration();
      ToolInterceptorChain chain = toolConfiguration.toolInterceptorChain(host);
      assertTrue(chain.hasPermissionBoundary());

      HarnessToolWorkerConfiguration toolWorkerConfiguration = new HarnessToolWorkerConfiguration();
      ToolRegistry registry = toolWorkerConfiguration.toolRegistry(host);
      assertSame(tool, registry.find("configured", "1").orElseThrow());
      assertTrue(registry.find("missing", "1").isEmpty());
      ToolWorker toolWorker =
          toolWorkerConfiguration.toolWorker(
              mock(ToolInvocationTransactions.class),
              registry,
              mock(RemoteToolTransport.class),
              chain,
              mock(ArtifactStore.class),
              () -> InvocationRetryPolicy.DEFAULT,
              event -> {},
              target -> {},
              ToolWorkerConfig.DEFAULT,
              Clock.systemUTC(),
              scheduler,
              new HarnessLifecycleObservers(host.lifecycleObservers()));
      assertNotNull(toolWorker);
    } finally {
      scheduler.shutdownNow();
      host.close();
    }
  }

  private static HarnessExtension customExtension(List<String> observed) {
    return new HarnessExtension() {
      @Override
      public String id() {
        return "custom.lifecycle";
      }

      @Override
      public int priority() {
        return 10;
      }

      @Override
      public void contribute(HarnessExtensionRegistry registry) {
        registry.addLifecycleObserver(observation -> observed.add("custom"));
      }
    };
  }

  private static PermissionEvaluator permissionEvaluator() {
    return new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer());
  }

  private static Tool tool(String name, String version) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            version,
            "test tool",
            name,
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return null;
      }
    };
  }
}
