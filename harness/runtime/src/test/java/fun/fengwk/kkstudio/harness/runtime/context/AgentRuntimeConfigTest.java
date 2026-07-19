package fun.fengwk.kkstudio.harness.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;

import java.util.List;

/** Derived runtime configuration changes fold over the snapshot without losing unrelated fields. */
class AgentRuntimeConfigTest {

  @Test
  void foldsSnapshotAndAllRuntimeOverrides() {
    AgentSnapshot snapshot =
        new AgentSnapshot(
            "system prompt",
            "base-model",
            "base-variant",
            List.of("read"),
            List.of("skill-1"),
            List.of("subagent-1"),
            "{\"policy\":\"safe\"}");

    AgentRuntimeConfig config = AgentRuntimeConfig.from(7L, snapshot);
    assertEquals(7L, config.agentDefinitionId());
    assertEquals("system prompt", config.systemPrompt());
    assertEquals("base-model", config.modelId());
    assertEquals("base-variant", config.variant());
    assertEquals(List.of("read"), config.tools());
    assertEquals(List.of("skill-1"), config.skills());
    assertEquals(List.of("subagent-1"), config.allowedSubagents());
    assertEquals("{\"policy\":\"safe\"}", config.executionPolicyJson());
    assertFalse(config.yoloEnabled());

    AgentRuntimeConfig overridden =
        config
            .withModel("override-model", "override-variant")
            .withTools(List.of("read", "write"))
            .withYolo(true)
            .withAgentDefinitionId(8L);

    assertEquals(8L, overridden.agentDefinitionId());
    assertEquals("system prompt", overridden.systemPrompt());
    assertEquals("override-model", overridden.modelId());
    assertEquals("override-variant", overridden.variant());
    assertEquals(List.of("read", "write"), overridden.tools());
    assertEquals(List.of("skill-1"), overridden.skills());
    assertEquals(List.of("subagent-1"), overridden.allowedSubagents());
    assertEquals("{\"policy\":\"safe\"}", overridden.executionPolicyJson());
    assertTrue(overridden.yoloEnabled());
    assertNull(AgentRuntimeConfig.from(snapshot).agentDefinitionId());
  }

  @Test
  void rejectsInvalidDerivedAgentDefinitionId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentRuntimeConfig(
                0L, "prompt", "model", "variant", List.of(), List.of(), List.of(), "{}", false));
  }
}
