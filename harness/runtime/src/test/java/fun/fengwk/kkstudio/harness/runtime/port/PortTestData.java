package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** port 契约测试使用的最小化 frozen request fixture。 */
final class PortTestData {

  private static final EnvironmentBinding ENV_ID =
      EnvironmentBindings.binding("11111111-1111-1111-1111-111111111111");

  private PortTestData() {}

  static ModelRequestSpec modelRequest() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  static ProviderRequest providerRequest() {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  static ToolInvocationRequest toolRequest() {
    return new ToolInvocationRequest(new ToolCall("call-1", "bash", "{}"), toolBinding());
  }

  private static ToolBinding toolBinding() {
    return new ToolBinding(
        new AgentToolDefinition(
            new AgentToolId("test.bash"), toolDescriptor("bash"), ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "bash", List.of()),
        false,
        null);
  }

  private static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        "description of " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }
}
