package fun.fengwk.kkstudio.harness.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskDomainTest {
  /** Explorer-like names get read-only sharing while explicit policy remains authoritative. */
  @Test
  void defaultsWorkingCopyPolicyAndValidatesArguments() {
    assertEquals(
        WorkingCopyPolicy.SHARE_READ_ONLY,
        new TaskCommand("RepositoryExplorer", "inspect", null, null).workingCopyPolicy());
    assertEquals(
        WorkingCopyPolicy.EXCLUSIVE,
        new TaskCommand("Coder", "write", null, WorkingCopyPolicy.EXCLUSIVE).workingCopyPolicy());
    assertEquals(WorkingCopyPolicy.FORK, WorkingCopyPolicy.defaultFor("Coder"));
    assertEquals(WorkingCopyPolicy.FORK, WorkingCopyPolicy.parse("FORK"));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.parse(null));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.parse(" "));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.parse("fork"));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.defaultFor(null));
    assertThrows(IllegalArgumentException.class, () -> WorkingCopyPolicy.defaultFor(" "));
    assertThrows(IllegalArgumentException.class, () -> new TaskCommand(" ", "prompt", null, null));
    assertThrows(IllegalArgumentException.class, () -> new TaskCommand("Coder", " ", null, null));
  }

  /**
   * Frozen maxDepth and allowlist decide both descriptor injection and deterministic instruction
   * text.
   */
  @Test
  void exposesTaskOnlyBelowFrozenDepth() {
    AgentSnapshot snapshot =
        new AgentSnapshot(
            null,
            "model",
            "variant",
            List.of(),
            List.of(),
            List.of("Coder", "Explorer"),
            "{\"maxDepth\":2}");
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "task",
            "1",
            "task",
            "task",
            new ToolParamsSchema("task", Map.of(), Set.of(), false),
            ToolExecutionMode.CONTROL,
            ToolSideEffect.IDEMPOTENT,
            Duration.ZERO);

    assertTrue(TaskExposure.descriptor(snapshot, 1, descriptor).isPresent());
    assertFalse(TaskExposure.descriptor(snapshot, 2, descriptor).isPresent());
    assertEquals(
        "<available_subagents>\n"
            + "  <subagent name=\"Coder\"/>\n"
            + "  <subagent name=\"Explorer\"/>\n"
            + "</available_subagents>",
        TaskExposure.availableSubagentsInstruction(snapshot, 1));
  }

  /** Strict policy JSON accepts optional limits and rejects each malformed numeric field. */
  @Test
  void decodesFrozenPoliciesStrictly() {
    assertEquals(
        new TaskPolicy(4, 3, 9, 2, Duration.ofMillis(7)),
        TaskPolicyCodec.decode(
            "{\"maxDepth\":4,\"maxDirectSubagents\":3,\"maxTotalSubagents\":9,\"maxTurns\":2,\"idleTimeoutMillis\":7}"));
    assertEquals(TaskPolicy.defaults(), TaskPolicyCodec.decode("{}"));
    assertThrows(IllegalArgumentException.class, () -> TaskPolicyCodec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> TaskPolicyCodec.decode("not-json"));
    for (String field :
        List.of(
            "maxDepth",
            "maxDirectSubagents",
            "maxTotalSubagents",
            "maxTurns",
            "idleTimeoutMillis")) {
      assertThrows(
          IllegalArgumentException.class, () -> TaskPolicyCodec.decode("{\"" + field + "\":0}"));
      assertThrows(
          IllegalArgumentException.class,
          () -> TaskPolicyCodec.decode("{\"" + field + "\":\"1\"}"));
    }
  }

  /** XML instruction escaping and no-eligible cases remain deterministic for frozen snapshots. */
  @Test
  void escapesAvailableSubagentsAndRejectsInvalidDepth() {
    AgentSnapshot snapshot =
        new AgentSnapshot(
            null, "model", "variant", List.of(), List.of(), List.of("A<&\"'"), "{\"maxDepth\":1}");
    assertTrue(
        TaskExposure.availableSubagentsInstruction(snapshot, 0).contains("A&lt;&amp;&quot;&apos;"));
    assertEquals("", TaskExposure.availableSubagentsInstruction(snapshot, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> TaskExposure.availableSubagentsInstruction(snapshot, -1));
  }

  /**
   * Structured reports preserve terminal state and produce standard error text for child failure.
   */
  @Test
  void rendersTerminalReport() {
    TaskReport report =
        new TaskReport(
            9, 10, TaskState.FAILED, "broken", List.of(), 2, 3, WorkingCopyPolicy.FORK, "rev-1");

    assertFalse(report.success());
    assertTrue(TaskResultFormatter.text(report).contains("<task_error>broken</task_error>"));
    assertTrue(TaskResultFormatter.json(report).contains("\"workingCopyRevision\":\"rev-1\""));
    TaskReport succeeded =
        new TaskReport(
            11,
            12,
            TaskState.SUCCEEDED,
            "<&\"'",
            List.of(new ArtifactRef("artifact-1", "text/plain", 3)),
            0,
            0,
            WorkingCopyPolicy.NONE,
            null);
    assertTrue(TaskResultFormatter.text(succeeded).contains("&lt;&amp;&quot;&apos;"));
    assertFalse(TaskResultFormatter.json(succeeded).contains("workingCopyRevision"));
    assertTrue(TaskResultFormatter.json(succeeded).contains("\"artifactId\":\"artifact-1\""));
  }
}
