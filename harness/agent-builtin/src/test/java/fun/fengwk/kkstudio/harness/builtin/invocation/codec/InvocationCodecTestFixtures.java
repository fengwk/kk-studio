package fun.fengwk.kkstudio.harness.builtin.invocation.codec;

import static fun.fengwk.kkstudio.harness.builtin.ModelRequestSpecTestSupport.spec;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 严格 Invocation codec 测试间共享的小型完整测试值。 */
public final class InvocationCodecTestFixtures {

  public static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("123e4567-e89b-12d3-a456-426614174000");
  static final EnvironmentId OTHER_ENVIRONMENT_ID =
      EnvironmentId.parse("123e4567-e89b-12d3-a456-426614174001");
  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  private InvocationCodecTestFixtures() {}

  static ToolDescriptor descriptor() {
    return descriptor("bash");
  }

  static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "Run " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding binding(EnvironmentSupport environmentSupport) {
    ContributorBinding contributor = new ContributorBinding("core", "bash");
    boolean hasEnv = environmentSupport == EnvironmentSupport.REQUIRED;
    return new ToolBinding(
        definition(hasEnv ? "fs" : "bash"),
        contributor,
        environmentSupport,
        hasEnv ? ENVIRONMENT_ID : null,
        hasEnv ? "dev" : null);
  }

  private static AgentToolDefinition definition(String name) {
    return new AgentToolDefinition(descriptor(name), ToolVisibility.SELECTABLE);
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
            "provider", "model", "model", Set.of(ModelInputModality.TEXT), tools.length > 0, true),
        new ModelVariant("v1", null, PromptCacheRetention.NONE, null),
        1024,
        "Test system instruction.",
        List.of(),
        definitions,
        ProviderCacheControl.none());
  }

  public static ModelRequestSpec environmentModelRequest() {
    ToolBinding binding = binding(EnvironmentSupport.REQUIRED);
    ProviderRequest provider = providerRequest(binding.descriptor());
    return spec(
        ProviderType.OPENAI,
        provider.model(),
        provider.variant(),
        1024,
        "Test system instruction.",
        List.of(binding),
        List.of());
  }

  public static ModelRequestSpec hostModelRequest() {
    ToolBinding binding = binding(EnvironmentSupport.NONE);
    ProviderRequest provider = providerRequest(binding.descriptor());
    return spec(
        ProviderType.OPENAI,
        provider.model(),
        provider.variant(),
        1024,
        "Test system instruction.",
        List.of(binding),
        List.of());
  }

  private static ModelPricing pricing() {
    return ModelPricing.ZERO;
  }
}
