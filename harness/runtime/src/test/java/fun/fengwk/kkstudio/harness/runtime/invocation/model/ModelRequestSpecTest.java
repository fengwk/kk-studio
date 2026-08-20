package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.ENV_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.environment;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.modelDescriptor;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.platform;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

import java.util.ArrayList;
import java.util.List;

/** ModelRequestSpec 的冻结、唯一名称与 route 一致性检查。 */
class ModelRequestSpecTest {

  @Test
  void freezesProviderModelVariantAndOrderedBindings() {
    // 验证 invocation 只冻结实际 Provider 请求契约，不携带 history/compaction 派生事实。
    ModelRequestSpec spec =
        spec(
            List.of(platform("bash"), environment("fs")),
            List.of(new SkillBinding("web", "Web search", ENV_ID)),
            List.of(new SubagentBinding("reviewer", "Review changes")));

    assertEquals(ProviderType.OPENAI, spec.providerType());
    assertEquals("provider", spec.model().providerName());
    assertEquals("v1", spec.variant().id());
    assertEquals(List.of("bash", "fs"), names(spec.toolBindings()));
    assertEquals(List.of("web"), skillNames(spec.skillBindings()));
    assertEquals(List.of("reviewer"), subagentNames(spec.subagentBindings()));
    assertTrue(spec.preambleMessages().isEmpty());
  }

  @Test
  void defensivelyCopiesFrozenLists() {
    // 修改调用方列表不能改变 durable request，返回列表也不可变。
    List<ToolBinding> tools = new ArrayList<>(List.of(platform("bash")));
    List<SkillBinding> skills =
        new ArrayList<>(List.of(new SkillBinding("web", "Web search", null)));
    List<SubagentBinding> subagents =
        new ArrayList<>(List.of(new SubagentBinding("reviewer", "Review changes")));
    List<AgentMessage> preamble = new ArrayList<>(List.of(AgentMessage.system("sys")));
    ModelRequestSpec spec =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            modelDescriptor(),
            variant(),
            preamble,
            tools,
            skills,
            subagents,
            ProviderCacheControl.none());

    tools.add(platform("extra"));
    skills.add(new SkillBinding("extra", "Extra", null));
    subagents.add(new SubagentBinding("extra", "Extra"));
    preamble.add(AgentMessage.system("more"));

    assertEquals(List.of("bash"), names(spec.toolBindings()));
    assertEquals(List.of("web"), skillNames(spec.skillBindings()));
    assertEquals(List.of("reviewer"), subagentNames(spec.subagentBindings()));
    assertEquals(1, spec.preambleMessages().size());
    assertThrows(UnsupportedOperationException.class, () -> spec.toolBindings().add(platform("x")));
  }

  @Test
  void rejectsDuplicateNames() {
    // 每类 binding 名称都是其调用内身份，重复名称必须在构造时失败。
    assertThrows(
        IllegalArgumentException.class,
        () -> spec(List.of(platform("bash"), environment("bash")), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            spec(
                List.of(),
                List.of(
                    new SkillBinding("web", "first", null),
                    new SkillBinding("web", "second", null)),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            spec(
                List.of(),
                List.of(),
                List.of(
                    new SubagentBinding("reviewer", "first"),
                    new SubagentBinding("reviewer", "second"))));
  }

  @Test
  void requiresMatchingEnvironmentRoutes() {
    // Environment tool 与 skill source 必须落在同一路由，避免一次请求混用多个 workspace。
    EnvironmentBinding other = EnvironmentBindings.binding("env-2");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            spec(
                List.of(environment("fs", ENV_ID), environment("other", other)),
                List.of(),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            spec(
                List.of(environment("fs")),
                List.of(new SkillBinding("web", "Web search", other)),
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
                modelDescriptor(),
                variant(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                modelDescriptor(),
                variant(),
                null,
                List.of(),
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelRequestSpec(
                ProviderType.OPENAI,
                modelDescriptor(),
                variant(),
                List.of(),
                null,
                List.of(),
                List.of(),
                ProviderCacheControl.none()));
  }

  private static ModelRequestSpec spec(
      List<ToolBinding> tools, List<SkillBinding> skills, List<SubagentBinding> subagents) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor(),
        variant(),
        List.of(),
        tools,
        skills,
        subagents,
        ProviderCacheControl.none());
  }

  private static ModelVariant variant() {
    return new ModelVariant("v1", null, null, null, null, null, null, List.of(), null);
  }

  private static List<String> names(List<ToolBinding> bindings) {
    return bindings.stream().map(binding -> binding.descriptor().name()).toList();
  }

  private static List<String> skillNames(List<SkillBinding> bindings) {
    return bindings.stream().map(SkillBinding::name).toList();
  }

  private static List<String> subagentNames(List<SubagentBinding> bindings) {
    return bindings.stream().map(SubagentBinding::name).toList();
  }
}
