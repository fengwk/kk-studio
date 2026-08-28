package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** invocation.model 包共享的测试 fixture。 */
final class InvocationTestData {

  static final EnvironmentBinding ENV_ID = EnvironmentBindings.binding("env-1");

  private InvocationTestData() {}

  static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        "description of " + name,
        name,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding host(String name) {
    return new ToolBinding(
        definition(name, AgentToolBackend.HOST),
        new ContributorBinding("core", name, List.of()),
        null);
  }

  static ToolBinding environment(String name) {
    return environment(name, ENV_ID);
  }

  static ToolBinding environment(String name, EnvironmentBinding environment) {
    return new ToolBinding(
        definition(name, AgentToolBackend.ENVIRONMENT_CAPABILITY),
        new ContributorBinding("base", name, List.of()),
        environment);
  }

  private static AgentToolDefinition definition(String name, AgentToolBackend backend) {
    return new AgentToolDefinition(
        new AgentToolId("test." + name.replace('_', '-').toLowerCase(Locale.ROOT)),
        toolDescriptor(name),
        ToolVisibility.SELECTABLE,
        backend);
  }

  static ModelDescriptor modelDescriptor() {
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

  static ProviderRequest providerRequest(List<ToolBinding> bindings) {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        bindings.stream()
            .map(
                binding ->
                    new ProviderToolDefinition(
                        binding.descriptor().name(), binding.descriptor().description(), "{}"))
            .toList(),
        ProviderCacheControl.none());
  }

  static ProviderResponse response() {
    return new ProviderResponse(
        "hello",
        null,
        List.of(),
        GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-1",
        null,
        null);
  }

  static ModelInvocationError error() {
    return new ModelInvocationError(ProviderErrorKind.TRANSIENT, "model boom");
  }

  static StreamCheckpoint checkpoint(int attempt) {
    return new StreamCheckpoint(attempt, 0L, "partial", "");
  }

  static ModelRequestSpec request(List<ToolBinding> bindings) {
    ProviderRequest provider = providerRequest(bindings);
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        provider.model(),
        provider.variant(),
        List.of(),
        bindings,
        List.of(),
        List.of(),
        provider.cacheControl());
  }

  static ModelRequestSpec request() {
    return request(List.of(host("bash")));
  }
}
