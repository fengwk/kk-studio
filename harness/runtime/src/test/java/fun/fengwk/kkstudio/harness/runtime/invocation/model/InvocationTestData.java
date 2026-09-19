package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
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
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** invocation.model 包共享的测试 fixture。 */
final class InvocationTestData {

  static final EnvironmentId ENV_ID = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final String SKILL_CONTENT_REVISION =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private InvocationTestData() {}

  /** 冻结的平台 Skill 包版本 fixture。 */
  static SkillBinding skill(String name, String description, EnvironmentId sourceEnvironmentId) {
    return new SkillBinding(name, "test-package", "1.0.0", SKILL_CONTENT_REVISION, description);
  }

  static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "description of " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding host(String name) {
    return new ToolBinding(
        definition(name), new ContributorBinding("core", name, List.of()), false, null);
  }

  static ToolBinding environment(String name) {
    return environment(name, ENV_ID);
  }

  static ToolBinding environment(String name, EnvironmentId environment) {
    return new ToolBinding(
        definition(name), new ContributorBinding("base", name, List.of()), true, environment);
  }

  private static AgentToolDefinition definition(String name) {
    return new AgentToolDefinition(toolDescriptor(name), ToolVisibility.SELECTABLE);
  }

  static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
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
        new ModelVariant("v1"),
        1024,
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

  static ModelRequestSpec requestSpec(List<ToolBinding> bindings) {
    ProviderRequest provider = providerRequest(bindings);
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        provider.model(),
        provider.variant(),
        1024,
        List.of(),
        bindings,
        List.of(),
        List.of(),
        provider.cacheControl());
  }

  static ModelRequestSpec requestSpec() {
    return requestSpec(List.of(host("bash")));
  }
}
