package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared test fixtures for the invocation.model package. */
final class InvocationTestData {

  static final EnvironmentId ENV_ID = new EnvironmentId(UUID.randomUUID().toString());

  private InvocationTestData() {}

  static ToolDescriptor toolDescriptor(String name, ToolType type) {
    return new ToolDescriptor(
        name,
        "1.0",
        type,
        "description of " + name,
        null,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding platform(String name) {
    return new ToolBinding(toolDescriptor(name, ToolType.PLATFORM), ToolType.PLATFORM, null);
  }

  static ToolBinding environment(String name) {
    return environment(name, ENV_ID);
  }

  static ToolBinding environment(String name, EnvironmentId environmentId) {
    return new ToolBinding(
        toolDescriptor(name, ToolType.ENVIRONMENT), ToolType.ENVIRONMENT, environmentId);
  }

  static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
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
        ProviderStopReason.COMPLETED,
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
    return new StreamCheckpoint(attempt, 0L, "partial", null);
  }

  static ModelInvocationRequest request(List<ToolBinding> bindings, boolean yoloEnabled) {
    return new ModelInvocationRequest(
        ENV_ID, providerRequest(bindings), bindings, List.of(), yoloEnabled);
  }

  static ModelInvocationRequest request() {
    return request(List.of(platform("bash")), true);
  }
}
