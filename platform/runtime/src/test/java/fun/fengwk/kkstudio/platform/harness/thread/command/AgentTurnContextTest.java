package fun.fengwk.kkstudio.platform.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.harness.task.CurrentEnvironmentContext;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** {@link AgentTurnContext} 只冻结 Builtin 实际消费的目录事实，构造期拒绝残缺快照。 */
class AgentTurnContextTest {

  @Test
  void rejectsMissingFactsAndCopiesCollections() {
    CurrentEnvironmentContext environment =
        CurrentEnvironmentContext.none(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC);
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentTurnContext(
                null,
                new AgentDefinitionConfigDTO(),
                environment,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "instruction"));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentTurnContext(
                agent(),
                null,
                environment,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "instruction"));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentTurnContext(
                agent(),
                new AgentDefinitionConfigDTO(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "instruction"));

    List<LiveTurnPlan.PlannedSkill> skills = new ArrayList<>();
    AgentTurnContext context =
        new AgentTurnContext(
            agent(),
            new AgentDefinitionConfigDTO(),
            environment,
            skills,
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>(),
            "instruction");
    skills.add(null);
    assertThrows(UnsupportedOperationException.class, () -> context.skills().clear());

    assertEquals(List.of(), context.skills());
    assertEquals(List.of(), context.subagentBindings());
    assertEquals(List.of(), context.toolBindings());
    assertEquals(List.of(), context.candidateTools());
    assertEquals("instruction", context.systemInstruction());
    assertEquals("assistant", context.agent().name());
  }

  private static AgentDefinition agent() {
    return AgentDefinition.builder().name("assistant").type(AgentDefinitionType.USER).build();
  }
}
