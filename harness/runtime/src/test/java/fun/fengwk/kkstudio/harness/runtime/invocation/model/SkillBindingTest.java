package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

/** SkillBinding 的 canonical 元数据以及 nullable 的来源 environment。 */
class SkillBindingTest {

  private static final EnvironmentBinding ENV_ID = EnvironmentBindings.binding("env-1");

  @Test
  void acceptsCanonicalMetadataWithNullableEnvironment() {
    SkillBinding withoutEnv = new SkillBinding("web_search", "Search the web", null);
    assertEquals("web_search", withoutEnv.name());
    assertEquals("Search the web", withoutEnv.description());
    assertNull(withoutEnv.sourceEnvironment());

    SkillBinding withEnv = new SkillBinding("web_search", "Search the web", ENV_ID);
    assertEquals(ENV_ID, withEnv.sourceEnvironment());

    SkillBinding longDescription = new SkillBinding("web_search", "d".repeat(1024), null);
    assertEquals(1024, longDescription.description().length());
  }

  @Test
  void rejectsInvalidNameAndDescription() {
    assertThrows(IllegalArgumentException.class, () -> new SkillBinding(null, "desc", null));
    assertThrows(IllegalArgumentException.class, () -> new SkillBinding(" ", "desc", null));
    assertThrows(IllegalArgumentException.class, () -> new SkillBinding(" name", "desc", null));
    assertThrows(IllegalArgumentException.class, () -> new SkillBinding("name ", "desc", null));
    assertThrows(
        IllegalArgumentException.class, () -> new SkillBinding("n".repeat(129), "desc", null));

    assertThrows(IllegalArgumentException.class, () -> new SkillBinding("name", null, null));
    assertThrows(IllegalArgumentException.class, () -> new SkillBinding("name", "", null));
    assertThrows(IllegalArgumentException.class, () -> new SkillBinding("name", " desc", null));
    assertThrows(
        IllegalArgumentException.class, () -> new SkillBinding("name", "d".repeat(1025), null));
  }
}
