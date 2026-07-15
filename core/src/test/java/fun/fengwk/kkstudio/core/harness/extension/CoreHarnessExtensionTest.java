package fun.fengwk.kkstudio.core.harness.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.adapter.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.adapter.GoogleProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.adapter.OpenAiProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.adapter.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtension;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionException;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry;
import fun.fengwk.kkstudio.harness.runtime.extension.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallResult;
import fun.fengwk.kkstudio.harness.runtime.tool.PermissionBoundaryInterceptor;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreHarnessExtensionTest {
  private static final Path ROOT = Path.of("/tmp/core-harness-extension");

  @Test
  void registersPermissionBoundaryFirstButChainForcesItToTheEnd() {
    PermissionEvaluator permissionEvaluator = permissionEvaluator();
    HarnessExtension customExtension =
        new HarnessExtension() {
          @Override
          public String id() {
            return "custom.modifier";
          }

          @Override
          public int priority() {
            return 1;
          }

          @Override
          public void contribute(HarnessExtensionRegistry registry) {
            registry.addBeforeToolCallInterceptor(
                context ->
                    new BeforeToolCallResult(context.binding(), context.call().argumentsJson()));
          }
        };
    HarnessExtensionHost host =
        new HarnessExtensionHost(
            List.of(new CoreHarnessExtension(permissionEvaluator, List.of()), customExtension));

    try {
      assertEquals(2, host.beforeToolCallInterceptors().size());
      assertTrue(host.beforeToolCallInterceptors().get(0) instanceof PermissionBoundaryInterceptor);
      ToolInterceptorChain chain =
          new ToolInterceptorChain(
              host.beforeToolCallInterceptors(), host.afterToolCallInterceptors());
      assertTrue(chain.hasPermissionBoundary());

      var result =
          chain.before(
              ToolBinding.of(tool("read", "1").descriptor()),
              new ToolCall("call-1", "read", "{}"),
              ToolSettings.DEFAULT,
              false,
              ROOT,
              ROOT);
      assertEquals(PermissionAction.ALLOW, result.permissionAction());
    } finally {
      host.close();
    }
  }

  @Test
  void createsTheAdapterMatchingEachProviderFactoryKey() {
    HarnessExtensionHost host =
        new HarnessExtensionHost(
            List.of(new CoreHarnessExtension(permissionEvaluator(), List.of())));

    try {
      assertAdapter(host, ProviderType.OPENAI, OpenAiProviderAdapter.class);
      assertAdapter(host, ProviderType.OPENAI_RESPONSES, OpenAiResponsesProviderAdapter.class);
      assertAdapter(host, ProviderType.ANTHROPIC, AnthropicProviderAdapter.class);
      assertAdapter(host, ProviderType.GOOGLE, GoogleProviderAdapter.class);
    } finally {
      host.close();
    }
  }

  @Test
  void snapshotsAndSortsToolsWhileHostOwnsLookupAndDuplicateValidation() {
    Tool zeta = tool("zeta", "1");
    Tool alphaV2 = tool("alpha", "2");
    Tool alphaV1 = tool("alpha", "1");
    List<Tool> supplied = new ArrayList<>(List.of(zeta, alphaV2, alphaV1));
    CoreHarnessExtension extension = new CoreHarnessExtension(permissionEvaluator(), supplied);
    supplied.clear();
    HarnessExtensionHost host = new HarnessExtensionHost(List.of(extension));

    try {
      assertEquals(
          List.of("alpha@1", "alpha@2", "zeta@1"),
          host.toolFactories().stream()
              .map(factory -> factory.descriptor().name() + "@" + factory.descriptor().version())
              .toList());
      assertSame(alphaV1, host.createTool("alpha", "1").orElseThrow());
      assertSame(alphaV2, host.createTool("alpha", "2").orElseThrow());
      assertTrue(host.createTool("missing", "1").isEmpty());
    } finally {
      host.close();
    }

    HarnessExtensionException failure =
        assertThrows(
            HarnessExtensionException.class,
            () ->
                new HarnessExtensionHost(
                    List.of(
                        new CoreHarnessExtension(
                            permissionEvaluator(),
                            List.of(tool("duplicate", "1"), tool("duplicate", "1"))))));
    assertEquals(HarnessExtensionException.Phase.CONTRIBUTE, failure.phase());
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
  }

  private static void assertAdapter(
      HarnessExtensionHost host, ProviderType type, Class<?> adapterType) {
    ProviderFactory factory = host.providerFactory(type).orElseThrow();
    assertEquals(type, factory.providerType());
    assertInstanceOf(adapterType, factory.create("credential", null));
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
            ToolExecutionMode.CONTROL,
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
