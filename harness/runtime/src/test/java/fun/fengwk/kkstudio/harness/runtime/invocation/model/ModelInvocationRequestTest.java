package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.ENV_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.environment;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.platform;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.providerRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.util.ArrayList;
import java.util.List;

/** ModelInvocationRequest 的 freeze、ordering 以及 provider-tool 对应关系检查。 */
class ModelInvocationRequestTest {

  @Test
  void freezesRouteYoloAndOrderedBindings() {
    List<ToolBinding> bindings = List.of(platform("bash"), environment("fs"));

    ModelInvocationRequest request =
        new ModelInvocationRequest(
            ENV_ID, providerRequest(bindings), bindings, List.of(), true, 100_000, null);

    assertEquals(ENV_ID, request.environment());
    assertTrue(request.yoloEnabled());
    assertEquals(List.of("bash", "fs"), names(request.toolBindings()));
    assertEquals(List.of("bash", "fs"), providerNames(request));
  }

  @Test
  void acceptsNullableRouteAndEmptyBindings() {
    ModelInvocationRequest request =
        new ModelInvocationRequest(
            null, providerRequest(List.of()), List.of(), List.of(), false, 100_000, null);

    assertNull(request.environment());
    assertFalse(request.yoloEnabled());
    assertTrue(request.toolBindings().isEmpty());
    assertTrue(request.skillBindings().isEmpty());
  }

  @Test
  void defensivelyCopiesBindingLists() {
    List<ToolBinding> toolBindings = new ArrayList<>(List.of(platform("bash")));
    List<SkillBinding> skillBindings =
        new ArrayList<>(List.of(new SkillBinding("web", "Web search", null)));

    ModelInvocationRequest request =
        new ModelInvocationRequest(
            ENV_ID,
            providerRequest(toolBindings),
            toolBindings,
            skillBindings,
            true,
            100_000,
            null);

    toolBindings.add(platform("extra"));
    skillBindings.add(new SkillBinding("extra", "Extra", null));

    assertEquals(List.of("bash"), names(request.toolBindings()));
    assertEquals(List.of("web"), skillNames(request.skillBindings()));
    assertThrows(
        UnsupportedOperationException.class, () -> request.toolBindings().add(platform("x")));
  }

  @Test
  void rejectsProviderToolMismatch() {
    List<ToolBinding> bindings = List.of(platform("bash"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID, providerRequest(List.of()), bindings, List.of(), true, 100_000, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID,
                providerRequest(List.of(platform("bash"), platform("fs"))),
                bindings,
                List.of(),
                true,
                100_000,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID,
                providerRequest(List.of(platform("other"))),
                bindings,
                List.of(),
                true,
                100_000,
                null));
  }

  @Test
  void rejectsDuplicateToolNames() {
    List<ToolBinding> duplicates = List.of(platform("bash"), environment("bash"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID, providerRequest(duplicates), duplicates, List.of(), true, 100_000, null));
  }

  @Test
  void rejectsDuplicateSkillNames() {
    List<SkillBinding> duplicates =
        List.of(new SkillBinding("web", "first", null), new SkillBinding("web", "second", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID, providerRequest(List.of()), List.of(), duplicates, true, 100_000, null));
  }

  @Test
  void requiresMatchingEnvironmentRoutes() {
    EnvironmentBinding otherEnv = EnvironmentBindings.binding("env-2");
    EnvironmentName otherName = otherEnv.environmentName();
    List<ToolBinding> envMismatch = List.of(environment("fs", otherEnv));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID, providerRequest(envMismatch), envMismatch, List.of(), true, 100_000, null));

    List<SkillBinding> skillMismatch = List.of(new SkillBinding("web", "Web search", otherEnv));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID, providerRequest(List.of()), List.of(), skillMismatch, true, 100_000, null));
  }

  @Test
  void forbidsEnvironmentRoutesWhenRouteIsNull() {
    List<ToolBinding> envBinding = List.of(environment("fs"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                null, providerRequest(envBinding), envBinding, List.of(), true, 100_000, null));

    List<SkillBinding> skillWithRoute = List.of(new SkillBinding("web", "Web search", ENV_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                null, providerRequest(List.of()), List.of(), skillWithRoute, true, 100_000, null));
  }

  @Test
  void acceptsMatchingRouteFacts() {
    List<ToolBinding> bindings = List.of(platform("bash"), environment("fs"));
    List<SkillBinding> skills =
        List.of(
            new SkillBinding("web", "Web search", ENV_ID),
            new SkillBinding("code", "Code search", null));

    ModelInvocationRequest request =
        new ModelInvocationRequest(
            ENV_ID, providerRequest(bindings), bindings, skills, true, 100_000, null);

    assertEquals(List.of("bash", "fs"), names(request.toolBindings()));
    assertEquals(List.of("web", "code"), skillNames(request.skillBindings()));
  }

  @Test
  void rejectsNullFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationRequest(ENV_ID, null, List.of(), List.of(), true, 100_000, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID, providerRequest(List.of()), null, List.of(), true, 100_000, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID,
                providerRequest(List.of()),
                List.of(platform("bash")),
                null,
                true,
                100_000,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocationRequest(
                ENV_ID,
                providerRequest(List.of()),
                List.of((ToolBinding) null),
                List.of(),
                true,
                100_000,
                null));
  }

  private static List<String> names(List<ToolBinding> bindings) {
    return bindings.stream().map(binding -> binding.descriptor().name()).toList();
  }

  private static List<String> skillNames(List<SkillBinding> bindings) {
    return bindings.stream().map(SkillBinding::name).toList();
  }

  private static List<String> providerNames(ModelInvocationRequest request) {
    return request.providerRequest().tools().stream().map(tool -> tool.name()).toList();
  }
}
