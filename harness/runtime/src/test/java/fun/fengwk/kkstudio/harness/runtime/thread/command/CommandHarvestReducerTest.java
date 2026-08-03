package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Instant;
import java.util.List;

/** Ordered command harvest reduction and branch-vs-Thread policy separation. */
class CommandHarvestReducerTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final BranchSettings BASE =
      new BranchSettings(
          "workspace-A",
          "coding",
          new ModelSelection("anthropic", "claude-sonnet", "default"),
          "medium",
          List.of("read"));
  private final CommandHarvestReducer reducer = new CommandHarvestReducer();

  @Test
  void appliesCommandsInSequenceWithLastWriteWins() {
    CommandHarvestResult result =
        reducer.reduce(
            7L,
            BASE,
            false,
            List.of(
                queued(1L, 1L, new UserMessageCommandPayload(user("hello"))),
                queued(2L, 2L, new SetAgentCommandPayload("agent-a")),
                queued(3L, 3L, new SetAgentCommandPayload("agent-b")),
                queued(
                    4L,
                    4L,
                    new SetModelCommandPayload(new ModelSelection("openai", "gpt-4.1", "default"))),
                queued(
                    5L,
                    5L,
                    new SetModelCommandPayload(
                        new ModelSelection("anthropic", "claude-opus", "thinking"))),
                queued(6L, 6L, new SetThinkingLevelCommandPayload("high")),
                queued(7L, 7L, new SetActiveToolsCommandPayload(List.of("grep", "read", "grep"))),
                queued(8L, 8L, new SetEnvironmentCommandPayload("workspace-B")),
                queued(9L, 9L, new SetEnvironmentCommandPayload(null)),
                queued(10L, 10L, new SetYoloCommandPayload(true)),
                queued(11L, 11L, new CustomMessageCommandPayload(system("instruction")))));

    assertEquals(
        new BranchSettings(
            null,
            "agent-b",
            new ModelSelection("anthropic", "claude-opus", "thinking"),
            "high",
            List.of("grep", "read")),
        result.branchSettings());
    assertEquals(true, result.yoloEnabled());
  }

  @Test
  void modelSelectionIsAtomicAndMessagesDoNotChangeSettings() {
    ModelSelection replacement = new ModelSelection("openai", "gpt-4.1", "default");
    CommandHarvestResult result =
        reducer.reduce(
            7L,
            BASE,
            true,
            List.of(
                queued(1L, 1L, new UserMessageCommandPayload(user("hello"))),
                queued(2L, 2L, new SetModelCommandPayload(replacement)),
                queued(3L, 3L, new CustomMessageCommandPayload(system("system")))));

    assertEquals("workspace-A", result.branchSettings().environmentName());
    assertEquals("coding", result.branchSettings().agentName());
    assertEquals(replacement, result.branchSettings().model());
    assertEquals("medium", result.branchSettings().thinkingLevel());
    assertEquals(List.of("read"), result.branchSettings().activeTools());
    assertEquals(true, result.yoloEnabled());
  }

  @Test
  void rejectsWrongThreadNonQueuedAndNonMonotonicCommands() {
    assertThrows(IllegalArgumentException.class, () -> reducer.reduce(0L, BASE, false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> reducer.reduce(-1L, BASE, false, List.of()));

    ThreadCommand foreign = queued(1L, 1L, new SetAgentCommandPayload("coding"), 8L);
    ThreadCommand applied = applied(2L, 2L);
    ThreadCommand cancelled =
        new ThreadCommand(
            3L,
            7L,
            3L,
            new SetAgentCommandPayload("coding"),
            "client-3",
            null,
            CREATED.plusSeconds(1),
            CREATED);
    assertThrows(
        IllegalArgumentException.class, () -> reducer.reduce(7L, BASE, false, List.of(foreign)));
    assertThrows(
        IllegalArgumentException.class, () -> reducer.reduce(7L, BASE, false, List.of(applied)));
    assertThrows(
        IllegalArgumentException.class, () -> reducer.reduce(7L, BASE, false, List.of(cancelled)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reducer.reduce(
                7L,
                BASE,
                false,
                List.of(
                    queued(4L, 2L, new SetAgentCommandPayload("coding")),
                    queued(5L, 1L, new SetYoloCommandPayload(true)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reducer.reduce(
                7L,
                BASE,
                false,
                List.of(
                    queued(6L, 1L, new SetAgentCommandPayload("coding")),
                    queued(7L, 1L, new SetYoloCommandPayload(true)))));
  }

  @Test
  void emptyCommandListLeavesBranchSettingsAndYoloUnchanged() {
    CommandHarvestResult result = reducer.reduce(7L, BASE, true, List.of());

    assertEquals(BASE, result.branchSettings());
    assertEquals(true, result.yoloEnabled());
  }

  private static ThreadCommand queued(long id, long sequence, ThreadCommandPayload payload) {
    return queued(id, sequence, payload, 7L);
  }

  private static ThreadCommand queued(
      long id, long sequence, ThreadCommandPayload payload, long threadId) {
    return new ThreadCommand(id, threadId, sequence, payload, "client-" + id, null, null, CREATED);
  }

  private static ThreadCommand applied(long id, long sequence) {
    return new ThreadCommand(
        id, 7L, sequence, new SetAgentCommandPayload("coding"), "client-" + id, 99L, null, CREATED);
  }

  private static AgentMessage user(String text) {
    return message(AgentMessageRole.USER, text);
  }

  private static AgentMessage system(String text) {
    return message(AgentMessageRole.SYSTEM, text);
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }
}
