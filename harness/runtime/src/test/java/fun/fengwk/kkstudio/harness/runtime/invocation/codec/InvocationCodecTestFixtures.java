package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 严格 Invocation codec 测试间共享的小型完整测试值。 */
final class InvocationCodecTestFixtures {

  static final EnvironmentName ENVIRONMENT_ID =
      new EnvironmentName("123e4567-e89b-12d3-a456-426614174000");
  static final EnvironmentName OTHER_ENVIRONMENT_ID =
      new EnvironmentName("123e4567-e89b-12d3-a456-426614174001");

  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();

  private InvocationCodecTestFixtures() {}

  static ToolDescriptor descriptor(ToolType type) {
    return new ToolDescriptor(
        "bash",
        "1.0",
        type,
        "Run a command",
        null,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding binding(ToolType type) {
    return new ToolBinding(
        descriptor(type), type, type == ToolType.ENVIRONMENT ? ENVIRONMENT_ID : null);
  }

  static ProviderRequest providerRequest(ToolDescriptor... tools) {
    List<ProviderToolDefinition> definitions = new ArrayList<>(tools.length);
    for (ToolDescriptor tool : tools) {
      definitions.add(
          new ProviderToolDefinition(
              tool.name(),
              tool.description(),
              TOOL_DESCRIPTOR_CODEC.encodeInputSchema(tool.inputSchema())));
    }
    return new ProviderRequest(
        new ModelDescriptor("provider", "model", tools.length > 0, true, pricing()),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        definitions,
        ProviderCacheControl.none());
  }

  static ModelInvocationRequest environmentModelRequest() {
    ToolBinding binding = binding(ToolType.ENVIRONMENT);
    return new ModelInvocationRequest(
        ENVIRONMENT_ID,
        providerRequest(binding.descriptor()),
        List.of(binding),
        List.of(new SkillBinding("review", "Review code", ENVIRONMENT_ID)),
        true);
  }

  static ModelInvocationRequest platformModelRequest() {
    ToolBinding binding = binding(ToolType.PLATFORM);
    return new ModelInvocationRequest(
        null,
        providerRequest(binding.descriptor()),
        List.of(binding),
        List.of(new SkillBinding("review", "Review code", null)),
        false);
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
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
        BigDecimal.ZERO);
  }
}
