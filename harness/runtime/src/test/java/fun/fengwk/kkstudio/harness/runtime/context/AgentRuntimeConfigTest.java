package fun.fengwk.kkstudio.harness.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

class AgentRuntimeConfigTest {
  @Test
  void copiesImmutableListsAndSupportsModelYoloOverrides() {
    AgentRuntimeConfig config =
        new AgentRuntimeConfig(
            1L,
            "system",
            "model-1",
            "default",
            "local-dev",
            List.of("read"),
            List.of("skill"),
            List.of(new SelectedSkillMetadata("skill", "desc", "platform")),
            List.of("Coder"),
            "{}",
            false);

    assertEquals(List.of("read"), config.tools());
    assertEquals("local-dev", config.environmentName());
    assertEquals(1, config.selectedSkills().size());
    assertFalse(config.yoloEnabled());
    assertEquals("model-2", config.withModel("model-2", "fast").modelId());
    assertEquals("fast", config.withModel("model-2", "fast").variant());
    assertTrue(config.withYolo(true).yoloEnabled());
    assertEquals(List.of("Coder"), config.allowedSubagents());
  }
}
