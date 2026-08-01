package fun.fengwk.kkstudio.harness.runtime.model.plan;

import fun.fengwk.kkstudio.harness.runtime.entry.AssistantAbortedEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Pure response-debt detector.
 *
 * <p>It only inspects persisted semantic entries. In particular, it never resolves an Agent, Model,
 * Environment, Tool, or Skill, so stop and reconcile callers can decide whether a response exists
 * without touching live definitions.
 */
public final class ResponseDebtDetector {

  /** Returns the latest debt entry index, or empty when the path is already settled. */
  public OptionalInt findDebtIndex(List<SessionEntry> rootToHead) {
    Objects.requireNonNull(rootToHead, "rootToHead");
    for (int index = rootToHead.size() - 1; index >= 0; index--) {
      EntryPayload payload =
          Objects.requireNonNull(rootToHead.get(index), "rootToHead[]").payload();
      if (payload instanceof AssistantErrorEntryPayload
          || payload instanceof AssistantAbortedEntryPayload) {
        return OptionalInt.empty();
      }
      AgentMessage message = message(payload);
      if (message == null || message.role() == AgentMessageRole.SYSTEM) {
        continue;
      }
      if (message.role() == AgentMessageRole.ASSISTANT) {
        return OptionalInt.empty();
      }
      if (message.role() == AgentMessageRole.USER || message.role() == AgentMessageRole.TOOL) {
        return OptionalInt.of(index);
      }
    }
    return OptionalInt.empty();
  }

  public boolean hasDebt(List<SessionEntry> rootToHead) {
    return findDebtIndex(rootToHead).isPresent();
  }

  private static AgentMessage message(EntryPayload payload) {
    if (payload instanceof MessageEntryPayload message) {
      return message.message();
    }
    if (payload instanceof CustomMessageEntryPayload message) {
      return message.message();
    }
    return null;
  }
}
