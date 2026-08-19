package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.ENV_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.environment;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.modelDescriptor;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.platform;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

import java.util.ArrayList;
import java.util.List;

/** ModelRequestSpec 的 freeze、唯一名称与 compaction 形状检查。 */
class ModelRequestSpecTest {

  @Test
  void freezesProviderModelVariantAndOrderedBindings() {
    List<ToolBinding> bindings = List.of(platform("bash"), environment("fs"));
    ModelRequestSpec spec = spec(bindings, List.of(), null);

    assertEquals(ProviderType.OPENAI, spec.providerType());
    assertEquals("provider", spec.model().providerName());
    assertEquals("v1", spec.variant().id());
    assertEquals(List.of("bash", "fs"), names(spec.toolBindings()));
    assertTrue(spec.preambleMessages().isEmpty());
    assertNull(spec.compaction());
  }

  @Test
  void acceptsEmptyBindings() {
    ModelRequestSpec spec = spec(List.of(), List.of(), null);
    assertTrue(spec.toolBindings().isEmpty());
    assertTrue(spec.skillBindings().isEmpty());
    assertTrue(spec.subagentBindings().isEmpty());
  }

  @Test
  void defensivelyCopiesBindingAndPreambleLists() {
    List<ToolBinding> toolBindings = new ArrayList<>(List.of(platform("bash")));
    List<SkillBinding> skillBindings =
        new ArrayList<>(List.of(new SkillBinding("web", "Web search", null)));
    List<AgentMessage> preamble = new ArrayList<>(List.of(AgentMessage.system("sys")));

    ModelRequestSpec spec =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            modelDescriptor(),
            variant(),
            preamble,
            toolBindings,
            skillBindings,
            List.of(),
            ProviderCacheControl.none(),
            null);

    toolBindings.add(platform("extra"));
    skillBindings.add(new SkillBinding("extra", "Extra", null));
    preamble.add(AgentMessage.system("more"));

    assertEquals(List.of("bash"), names(spec.toolBindings()));
    assertEquals(List.of("web"), skillNames(spec.skillBindings()));
    assertEquals(1, spec.preambleMessages().size());
    assertThrows(UnsupportedOperationException.class, () -> spec.toolBindings().add(platform("x")));
  }

  @Test
  void rejectsDuplicateToolNames() {
    List<ToolBinding> duplicates = List.of(platform("bash"), environment("bash"));
    assertThrows(IllegalArgumentException.class, () -> spec(duplicates, List.of(), null));
  }

  @Test
  void rejectsDuplicateSkillNames() {
    List<SkillBinding> duplicates =
        List.of(new SkillBinding("web", "first", null), new SkillBinding("web", "second", null));
    assertThrows(IllegalArgumentException.class, () -> spec(List.of(), duplicates, null));
  }

  @Test
  void requiresMatchingEnvironmentRoutesAmongBindings() {
    EnvironmentBinding otherEnv = EnvironmentBindings.binding("env-2");
    List<ToolBinding> envMismatch =
        List.of(environment("fs", ENV_ID), environment("other", otherEnv));
    assertThrows(IllegalArgumentException.class, () -> spec(envMismatch, List.of(), null));

    List<SkillBinding> skillMismatch = List.of(new SkillBinding("web", "Web search", otherEnv));
    assertThrows(
        IllegalArgumentException.class,
        () -> spec(List.of(environment("fs")), skillMismatch, null));
  }

  @Test
  void acceptsMatchingRouteFacts() {
    List<ToolBinding> bindings = List.of(platform("bash"), environment("fs"));
    List<SkillBinding> skills =
        List.of(
            new SkillBinding("web", "Web search", ENV_ID),
            new SkillBinding("code", "Code search", null));
    ModelRequestSpec spec = spec(bindings, skills, null);
    assertEquals(List.of("bash", "fs"), names(spec.toolBindings()));
    assertEquals(List.of("web", "code"), skillNames(spec.skillBindings()));
  }

  @Test
  void rejectsNullFacts() {
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
                ProviderCacheControl.none(),
                null));
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
                ProviderCacheControl.none(),
                null));
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
                ProviderCacheControl.none(),
                null));
  }

  @Test
  void compactionRequestsMustNotCarryBindings() {
    CompactionRequest compaction =
        new CompactionRequest(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            10L,
            TestIds.id(2L),
            TestIds.id(4L),
            null);
    assertThrows(
        IllegalArgumentException.class,
        () -> spec(List.of(platform("bash")), List.of(), compaction));
  }

  private static ModelRequestSpec spec(
      List<ToolBinding> bindings, List<SkillBinding> skills, CompactionRequest compaction) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor(),
        variant(),
        List.of(),
        bindings,
        skills,
        List.of(),
        ProviderCacheControl.none(),
        compaction);
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
}
