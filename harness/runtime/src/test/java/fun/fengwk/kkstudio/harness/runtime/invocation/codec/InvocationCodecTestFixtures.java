package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 严格 Invocation codec 测试间共享的小型完整测试值。 */
final class InvocationCodecTestFixtures {

  static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("123e4567-e89b-12d3-a456-426614174000");
  static final EnvironmentId OTHER_ENVIRONMENT_ID =
      EnvironmentId.parse("123e4567-e89b-12d3-a456-426614174001");
  static final UUID SOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  static final String CONTENT_REVISION =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  private InvocationCodecTestFixtures() {}

  /** 冻结的六字段 skill 事实：身份、描述与 contentRevision 全部必填。 */
  static SkillBinding skillBinding(EnvironmentId sourceEnvironmentId) {
    return new SkillBinding(
        sourceEnvironmentId,
        SOURCE_ID,
        "review",
        "Review code",
        "/home/dev/.agents/skills/review",
        CONTENT_REVISION);
  }

  static ToolDescriptor descriptor() {
    return descriptor("bash");
  }

  static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        "Run " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding binding(boolean environmentRequired) {
    return binding(environmentRequired, false);
  }

  static ToolBinding binding(boolean environmentRequired, boolean withStateAccesses) {
    ContributorBinding contributor =
        withStateAccesses
            ? new ContributorBinding(
                "goal",
                "create",
                List.of(new ContributorStateAccess("state", ContributorStateAccessMode.WRITE)))
            : new ContributorBinding("core", "bash", List.of());
    return new ToolBinding(
        definition(environmentRequired ? "fs" : "bash"),
        contributor,
        environmentRequired,
        environmentRequired ? ENVIRONMENT_ID : null);
  }

  private static AgentToolDefinition definition(String name) {
    return new AgentToolDefinition(
        new AgentToolId("test." + name.toLowerCase(Locale.ROOT).replace('_', '-')),
        descriptor(name),
        ToolVisibility.SELECTABLE);
  }

  static ProviderRequest providerRequest(ToolDescriptor... tools) {
    List<ProviderToolDefinition> definitions = new ArrayList<>(tools.length);
    for (ToolDescriptor tool : tools) {
      definitions.add(
          new ProviderToolDefinition(
              tool.name(), tool.description(), SCHEMA_CODEC.encode(tool.inputSchema())));
    }
    return new ProviderRequest(
        new ModelDescriptor(
            "provider",
            "model",
            "model",
            Set.of(ModelInputModality.TEXT),
            tools.length > 0,
            true,
            pricing()),
        new ModelVariant("v1"),
        1024,
        List.of(),
        definitions,
        ProviderCacheControl.none());
  }

  static ModelRequestSpec environmentModelRequest() {
    ToolBinding binding = binding(true);
    ProviderRequest provider = providerRequest(binding.descriptor());
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        provider.model(),
        provider.variant(),
        1024,
        List.of(),
        List.of(binding),
        List.of(skillBinding(ENVIRONMENT_ID)),
        List.of(),
        provider.cacheControl());
  }

  static ModelRequestSpec hostModelRequest() {
    ToolBinding binding = binding(false);
    ProviderRequest provider = providerRequest(binding.descriptor());
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        provider.model(),
        provider.variant(),
        1024,
        List.of(),
        List.of(binding),
        List.of(skillBinding(ENVIRONMENT_ID)),
        List.of(),
        provider.cacheControl());
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
