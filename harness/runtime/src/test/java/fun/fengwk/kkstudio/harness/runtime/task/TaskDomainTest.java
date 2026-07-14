package fun.fengwk.kkstudio.harness.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
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
  void defaultsWorkspacePolicyAndValidatesArguments() {
    assertEquals(
        WorkspacePolicy.SHARE_READ_ONLY,
        new TaskCommand("RepositoryExplorer", "inspect", null, null).workspacePolicy());
    assertEquals(
        WorkspacePolicy.EXCLUSIVE,
        new TaskCommand("Coder", "write", null, WorkspacePolicy.EXCLUSIVE).workspacePolicy());
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

  /**
   * Structured reports preserve terminal state and produce standard error text for child failure.
   */
  @Test
  void rendersTerminalReport() {
    TaskReport report =
        new TaskReport(
            9, 10, TaskState.FAILED, "broken", List.of(), 2, 3, WorkspacePolicy.FORK, "rev-1");

    assertFalse(report.success());
    assertTrue(TaskResultFormatter.text(report).contains("<task_error>broken</task_error>"));
    assertTrue(TaskResultFormatter.json(report).contains("\"workspaceRevision\":\"rev-1\""));
  }
}
