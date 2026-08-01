package fun.fengwk.kkstudio.harness.runtime.model.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.AssistantAbortedEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Response debt is a pure semantic-entry calculation and never needs a live resolver. */
class ResponseDebtDetectorTest {

  private static final TurnSettings SETTINGS = new TurnSettings("agent", "environment", false);
  private final ResponseDebtDetector detector = new ResponseDebtDetector();

  @Test
  void rootAndSystemPathsHaveNoDebt() {
    assertFalse(detector.hasDebt(path(new RootEntryPayload())));
    assertFalse(
        detector.hasDebt(
            path(
                new RootEntryPayload(),
                new CustomMessageEntryPayload(system("context"), SETTINGS))));
  }

  @Test
  void userAndToolMessagesCreateDebtAtTheirLatestIndex() {
    assertEquals(
        1, detector.findDebtIndex(path(new RootEntryPayload(), user("question"))).orElseThrow());
    assertEquals(
        2,
        detector
            .findDebtIndex(path(new RootEntryPayload(), user("question"), toolResult("call-1")))
            .orElseThrow());
  }

  @Test
  void assistantResponseClosesEarlierUserOrToolDebt() {
    assertFalse(
        detector.hasDebt(path(new RootEntryPayload(), user("question"), assistant("answer"))));
    assertFalse(
        detector.hasDebt(
            path(
                new RootEntryPayload(),
                user("question"),
                toolResult("call-1"),
                assistant("answer"))));
  }

  @Test
  void errorAndAbortedEntriesAreTerminalBarriers() {
    AssistantErrorEntryPayload error =
        new AssistantErrorEntryPayload(
            new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "invalid"));
    assertFalse(detector.hasDebt(path(new RootEntryPayload(), user("question"), error)));

    assertFalse(
        detector.hasDebt(
            path(
                new RootEntryPayload(),
                user("question"),
                AssistantAbortedEntryPayload.ofTextAndThinking("partial", ""))));
  }

  @Test
  void hasDebtAndFindDebtIndexAreConsistent() {
    List<SessionEntry> settled =
        path(
            new RootEntryPayload(),
            user("question"),
            AssistantAbortedEntryPayload.ofTextAndThinking("", "thinking"));
    List<SessionEntry> pending = path(new RootEntryPayload(), toolResult("call-1"));

    assertEquals(detector.findDebtIndex(settled).isPresent(), detector.hasDebt(settled));
    assertEquals(detector.findDebtIndex(pending).isPresent(), detector.hasDebt(pending));
    assertTrue(detector.hasDebt(pending));
  }

  private static MessageEntryPayload user(String text) {
    return new MessageEntryPayload(message(AgentMessageRole.USER, text), SETTINGS, null);
  }

  private static MessageEntryPayload assistant(String text) {
    return new MessageEntryPayload(message(AgentMessageRole.ASSISTANT, text), null, metadata());
  }

  private static MessageEntryPayload toolResult(String callId) {
    return new MessageEntryPayload(
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    callId, "lookup", List.of(new TextMessageContent("result")), false, "{}"))),
        null,
        null);
  }

  private static AgentMessage system(String text) {
    return message(AgentMessageRole.SYSTEM, text);
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(
        ProviderStopReason.COMPLETED,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static List<SessionEntry> path(EntryPayload... payloads) {
    List<SessionEntry> entries = new ArrayList<>(payloads.length);
    for (int index = 0; index < payloads.length; index++) {
      long id = index + 1L;
      entries.add(new SessionEntry(id, index == 0 ? null : id - 1L, payloads[index]));
    }
    return List.copyOf(entries);
  }
}
