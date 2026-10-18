package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.ENV_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.environment;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.host;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.modelDescriptor;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ModelRequestSpec 的冻结、唯一名称/ID 与 environment 一致性检查。 */
class ModelRequestSpecTest {

  private static final UUID CONNECTION_GENERATION_ID = new UUID(0L, 1L);

  @Test
  void freezesProviderModelVariantAndOrderedBindings() {
    // 验证 invocation 只冻结实际 Provider 请求契约，不接受 skill 事实，不携带 history/compaction 派生事实。
    ModelRequestSpec spec =
        spec(
            List.of(host("bash"), environment("fs")),
            List.of(new SubagentBinding("reviewer", "Review changes")));

    assertEquals(ProviderType.OPENAI, spec.providerType());
    assertEquals(CONNECTION_GENERATION_ID, spec.providerConnectionGenerationId());
    assertEquals("provider", spec.model().providerName());
    assertEquals("v1", spec.variant().id());
    assertEquals(List.of("bash", "fs"), names(spec.toolBindings()));
    assertEquals(List.of("reviewer"), subagentNames(spec.subagentBindings()));
    assertEquals("Test system instruction.", spec.systemInstruction());
  }

  @Test
  void defensivelyCopiesFrozenLists() {
    // 修改调用方列表不能改变 durable request，返回列表也不可变。
    List<ToolBinding> tools = new ArrayList<>(List.of(host("bash")));
    List<SubagentBinding> subagents =
        new ArrayList<>(List.of(new SubagentBinding("reviewer", "Review changes")));
    ModelRequestSpec spec =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            CONNECTION_GENERATION_ID,
            modelDescriptor(),
            variant(),
            1024,
            "Test system instruction.",
            tools,
            subagents,
            ProviderCacheControl.none());

    tools.add(host("extra"));
    subagents.add(new SubagentBinding("extra", "Extra"));

    assertEquals(List.of("bash"), names(spec.toolBindings()));
    assertEquals(List.of("reviewer"), subagentNames(spec.subagentBindings()));
    assertThrows(UnsupportedOperationException.class, () -> spec.toolBindings().add(host("x")));
  }

  @Test
  void rejectsBlankSystemInstruction() {
    // systemInstruction 必须非空且非空白字符串。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                modelDescriptor(),
                variant(),
                1024,
                "",
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                modelDescriptor(),
                variant(),
                1024,
                "   ",
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                modelDescriptor(),
                variant(),
                1024,
                null,
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
  }

  @Test
  void rejectsDuplicateBindingNames() {
    // 每类 binding 名称都是其调用内身份，重复名称必须在构造时失败。
    assertThrows(
        IllegalArgumentException.class,
        () -> spec(List.of(host("bash"), environment("bash")), List.of()));
    // 不同 contributor 提供的同名工具同样拒绝：模型可见 tool name 唯一。
    ToolBinding tool1 =
        new ToolBinding(
            new AgentToolDefinition(toolDescriptor("bash"), ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "bash", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);
    ToolBinding tool2 =
        new ToolBinding(
            new AgentToolDefinition(toolDescriptor("bash"), ToolVisibility.SELECTABLE),
            new ContributorBinding("other", "bash", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);
    assertThrows(IllegalArgumentException.class, () -> spec(List.of(tool1, tool2), List.of()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            spec(
                List.of(),
                List.of(
                    new SubagentBinding("reviewer", "first"),
                    new SubagentBinding("reviewer", "second"))));
  }

  /** 测试意图：不同 environmentId 的 carrying binding 混用必须被严格拒绝。 */
  @Test
  void requiresMatchingEnvironmentToolRoutes() {
    // Environment tool 必须落在同一 environment，避免一次请求混用多个 workspace。
    EnvironmentId other = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
    assertThrows(
        IllegalArgumentException.class,
        () -> spec(List.of(environment("fs", ENV_ID), environment("other", other)), List.of()));

    // REQUIRED 与携带不同环境的 OPTIONAL 混用必须被拒绝
    ToolBinding optionalWithDifferentEnv =
        new ToolBinding(
            new AgentToolDefinition(toolDescriptor("optional_tool"), ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "optional", List.of()),
            EnvironmentSupport.OPTIONAL,
            other,
            "other-env");
    assertThrows(
        IllegalArgumentException.class,
        () -> spec(List.of(environment("fs", ENV_ID), optionalWithDifferentEnv), List.of()));

    // 两个携带不同环境的 OPTIONAL 混用也必须被拒绝
    ToolBinding optionalWithEnv1 =
        new ToolBinding(
            new AgentToolDefinition(toolDescriptor("optional1"), ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "opt1", List.of()),
            EnvironmentSupport.OPTIONAL,
            ENV_ID,
            "dev");
    assertThrows(
        IllegalArgumentException.class,
        () -> spec(List.of(optionalWithEnv1, optionalWithDifferentEnv), List.of()));
  }

  /** 测试意图：OPTIONAL 带环境与 REQUIRED 共享同一环境合法，无环境的 NONE 和未带环境的 OPTIONAL 同样可以共存。 */
  @Test
  void permitsConsistentEnvironmentCombinationsAcrossSupportTypes() {
    ToolBinding optionalWithSameEnv =
        new ToolBinding(
            new AgentToolDefinition(toolDescriptor("optional_tool"), ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "optional", List.of()),
            EnvironmentSupport.OPTIONAL,
            ENV_ID,
            "dev");
    ToolBinding optionalWithoutEnv =
        new ToolBinding(
            new AgentToolDefinition(toolDescriptor("optional_unbound"), ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "unbound", List.of()),
            EnvironmentSupport.OPTIONAL,
            null,
            null);

    // REQUIRED + OPTIONAL(同一环境) + OPTIONAL(无环境) + NONE 全部合法共存
    assertDoesNotThrow(
        () ->
            spec(
                List.of(
                    host("bash"),
                    environment("fs", ENV_ID),
                    optionalWithSameEnv,
                    optionalWithoutEnv),
                List.of()));
  }

  @Test
  void rejectsNullFacts() {
    // 所有冻结事实必须显式存在；空能力用空列表表达。
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelRequestSpec(
                null,
                CONNECTION_GENERATION_ID,
                modelDescriptor(),
                variant(),
                1024,
                "Test system instruction.",
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                null,
                modelDescriptor(),
                variant(),
                1024,
                "Test system instruction.",
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                null,
                variant(),
                1024,
                "Test system instruction.",
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                modelDescriptor(),
                variant(),
                1024,
                "Test system instruction.",
                null,
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                CONNECTION_GENERATION_ID,
                modelDescriptor(),
                variant(),
                1024,
                "Test system instruction.",
                List.of(),
                null,
                ProviderCacheControl.none()));
  }

  private static ModelRequestSpec spec(List<ToolBinding> tools, List<SubagentBinding> subagents) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        CONNECTION_GENERATION_ID,
        modelDescriptor(),
        variant(),
        1024,
        "Test system instruction.",
        tools,
        subagents,
        ProviderCacheControl.none());
  }

  private static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "desc",
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static ModelVariant variant() {
    return new ModelVariant("v1");
  }

  private static List<String> names(List<ToolBinding> bindings) {
    return bindings.stream().map(binding -> binding.descriptor().name()).toList();
  }

  private static List<String> subagentNames(List<SubagentBinding> bindings) {
    return bindings.stream().map(SubagentBinding::name).toList();
  }
}
