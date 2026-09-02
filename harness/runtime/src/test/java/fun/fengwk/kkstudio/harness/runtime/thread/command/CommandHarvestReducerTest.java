package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
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
import java.util.UUID;

/** 有序 command harvest 归并：YOLO 不经过 mailbox，reducer 只归并 branch settings。 */
class CommandHarvestReducerTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final String ENV_A = "123e4567-e89b-12d3-a456-426614174000";
  private static final String ENV_B = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
  private static final BranchSettings BASE =
      new BranchSettings(
          ENV_A, "coding", new ModelSelection("anthropic", "claude-sonnet", "default"));
  private final CommandHarvestReducer reducer = new CommandHarvestReducer();

  @Test
  void appliesCommandsInSequenceWithLastWriteWins() {
    CommandHarvestResult result =
        reducer.reduce(
            id(7L),
            BASE,
            List.of(
                queued(id(1L), 1L, new UserMessageCommandPayload(user("hello"))),
                queued(id(2L), 2L, new SetAgentCommandPayload("agent-a")),
                queued(id(3L), 3L, new SetAgentCommandPayload("agent-b")),
                queued(
                    id(4L),
                    4L,
                    new SetModelCommandPayload(new ModelSelection("openai", "gpt-4.1", "default"))),
                queued(
                    id(5L),
                    5L,
                    new SetModelCommandPayload(
                        new ModelSelection("anthropic", "claude-opus", "thinking"))),
                queued(id(6L), 6L, new SetEnvironmentCommandPayload(ENV_B)),
                queued(id(7L), 7L, new SetEnvironmentCommandPayload(null)),
                queued(id(8L), 8L, new CustomMessageCommandPayload(system("instruction")))));

    assertEquals(
        new BranchSettings(
            null, "agent-b", new ModelSelection("anthropic", "claude-opus", "thinking")),
        result.branchSettings());
  }

  @Test
  void modelSelectionIsAtomicAndMessagesDoNotChangeSettings() {
    ModelSelection replacement = new ModelSelection("openai", "gpt-4.1", "default");
    CommandHarvestResult result =
        reducer.reduce(
            id(7L),
            BASE,
            List.of(
                queued(id(1L), 1L, new UserMessageCommandPayload(user("hello"))),
                queued(id(2L), 2L, new SetModelCommandPayload(replacement)),
                queued(id(3L), 3L, new CustomMessageCommandPayload(system("system")))));

    assertEquals(ENV_A, result.branchSettings().workspacePath());
    assertEquals("coding", result.branchSettings().agentName());
    assertEquals(replacement, result.branchSettings().model());
  }

  @Test
  void rejectsWrongThreadNonQueuedAndNonMonotonicCommands() {
    assertThrows(NullPointerException.class, () -> reducer.reduce(null, BASE, List.of()));

    ThreadCommand foreign = queued(id(1L), 1L, new SetAgentCommandPayload("coding"), id(8L));
    ThreadCommand applied = applied(id(2L), 2L);
    ThreadCommand cancelled =
        new ThreadCommand(
            id(7L),
            3L,
            new SetAgentCommandPayload("coding"),
            id(3L),
            ThreadCommandPayloadJsonCodec.requestHash(new SetAgentCommandPayload("coding")),
            null,
            id(3L),
            CREATED.plusSeconds(1),
            CREATED);
    assertThrows(
        IllegalArgumentException.class, () -> reducer.reduce(id(7L), BASE, List.of(foreign)));
    assertThrows(
        IllegalArgumentException.class, () -> reducer.reduce(id(7L), BASE, List.of(applied)));
    assertThrows(
        IllegalArgumentException.class, () -> reducer.reduce(id(7L), BASE, List.of(cancelled)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reducer.reduce(
                id(7L),
                BASE,
                List.of(
                    queued(id(4L), 2L, new SetAgentCommandPayload("coding")),
                    queued(id(5L), 1L, new SetAgentCommandPayload("other")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reducer.reduce(
                id(7L),
                BASE,
                List.of(
                    queued(id(6L), 1L, new SetAgentCommandPayload("coding")),
                    queued(id(7L), 1L, new SetAgentCommandPayload("other")))));
  }

  @Test
  void emptyCommandListLeavesBranchSettingsUnchanged() {
    CommandHarvestResult result = reducer.reduce(id(7L), BASE, List.of());

    assertEquals(BASE, result.branchSettings());
  }

  private static ThreadCommand queued(UUID id, long sequence, ThreadCommandPayload payload) {
    return queued(id, sequence, payload, id(7L));
  }

  private static ThreadCommand queued(
      UUID id, long sequence, ThreadCommandPayload payload, UUID threadId) {
    return new ThreadCommand(
        threadId,
        sequence,
        payload,
        id,
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        CREATED);
  }

  private static ThreadCommand applied(UUID id, long sequence) {
    ThreadCommandPayload payload = new SetAgentCommandPayload("coding");
    return new ThreadCommand(
        id(7L),
        sequence,
        payload,
        id,
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        id(99L),
        null,
        null,
        CREATED);
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
