package fun.fengwk.kkstudio.harness.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

class TaskDomainTest {
  @Test
  void defaultsWorkingCopyPolicyAndValidatesArguments() {
    assertEquals(
        WorkingCopyPolicy.SHARE_READ_ONLY,
        new TaskCommand("RepositoryExplorer", "inspect", null).workingCopyPolicy());
    assertEquals(
        WorkingCopyPolicy.EXCLUSIVE,
        new TaskCommand("Coder", "write", WorkingCopyPolicy.EXCLUSIVE).workingCopyPolicy());
    assertEquals(WorkingCopyPolicy.FORK, WorkingCopyPolicy.defaultFor("Coder"));
    assertEquals(WorkingCopyPolicy.FORK, WorkingCopyPolicy.parse("FORK"));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.parse(null));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.parse(" "));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.parse("fork"));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.defaultFor(null));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.defaultFor(" "));
    assertThrows(IllegalArgumentException.class, () -> new TaskCommand(" ", "prompt", null));
    assertThrows(IllegalArgumentException.class, () -> new TaskCommand("Coder", " ", null));
  }

  @Test
  void exposesTaskOnlyBelowRuntimeDepth() {
    AgentRuntimeConfig config =
        new AgentRuntimeConfig(
            1L,
            null,
            "model",
            "variant",
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of("Coder", "Explorer"),
            "{\"maxDepth\":2}",
            false);
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "task",
            "1",
            "task",
            "task",
            new ToolParamsSchema("task", Map.of(), Set.of(), false),
            ToolExecutionLocation.PLATFORM,
            ToolSideEffect.IDEMPOTENT,
            Duration.ZERO);

    assertTrue(TaskExposure.descriptor(config, 1, descriptor).isPresent());
    assertFalse(TaskExposure.descriptor(config, 2, descriptor).isPresent());
    assertEquals(
        "<available_subagents>\n"
            + "  <subagent name=\"Coder\"/>\n"
            + "  <subagent name=\"Explorer\"/>\n"
            + "</available_subagents>",
        TaskExposure.availableSubagentsInstruction(config, 1));
  }

  @Test
  void decodesFrozenPoliciesStrictly() {
    assertEquals(
        new TaskPolicy(4, 3, 9, 2),
        TaskPolicyCodec.decode(
            "{\"maxDepth\":4,\"maxDirectSubagents\":3,\"maxTotalSubagents\":9,\"maxTurns\":2}"));
    assertEquals(TaskPolicy.defaults(), TaskPolicyCodec.decode("{}"));
    assertThrows(IllegalArgumentException.class, () -> TaskPolicyCodec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> TaskPolicyCodec.decode("not-json"));
    for (String field :
        List.of("maxDepth", "maxDirectSubagents", "maxTotalSubagents", "maxTurns")) {
      assertThrows(
          IllegalArgumentException.class, () -> TaskPolicyCodec.decode("{\"" + field + "\":0}"));
      assertThrows(
          IllegalArgumentException.class,
          () -> TaskPolicyCodec.decode("{\"" + field + "\":\"1\"}"));
    }
  }

  @Test
  void escapesAvailableSubagentsAndRejectsInvalidDepth() {
    AgentRuntimeConfig config =
        new AgentRuntimeConfig(
            1L,
            null,
            "model",
            "variant",
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of("A<&\"'"),
            "{\"maxDepth\":1}",
            false);
    assertTrue(
        TaskExposure.availableSubagentsInstruction(config, 0).contains("A&lt;&amp;&quot;&apos;"));
    assertEquals("", TaskExposure.availableSubagentsInstruction(config, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> TaskExposure.availableSubagentsInstruction(config, -1));
  }

  @Test
  void rendersTerminalReport() {
    TaskReport report =
        new TaskReport(
            9, 10, TaskState.FAILED, "broken", List.of(), 2, 3, WorkingCopyPolicy.FORK, "rev-1");
    assertFalse(report.success());
    assertTrue(TaskResultFormatter.text(report).contains("<task_error>broken</task_error>"));
    assertTrue(TaskResultFormatter.json(report).contains("\"workingCopyRevision\":\"rev-1\""));
  }
}
