package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.BranchSummaryEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** active leaf path -> default transform -> extension transform -> AgentMessage projection。 */
public final class SessionContextBuilder {
  private final SessionStore sessionStore;
  private final SessionEntryStore entryStore;
  private final DefaultContextTransform defaultTransform;
  private final List<ContextTransform> extensions;

  public SessionContextBuilder(
      SessionStore sessionStore,
      SessionEntryStore entryStore,
      DefaultContextTransform defaultTransform,
      List<ContextTransform> extensions) {
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.defaultTransform = Objects.requireNonNull(defaultTransform, "defaultTransform");
    this.extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
  }

  public SessionContext build(long sessionId) {
    Session session =
        sessionStore
            .find(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
    if (session.leafEntryId() == null) {
      throw new ContextProjectionException("session has no active leaf");
    }
    ContextState state =
        defaultTransform.transform(entryStore.loadPath(sessionId, session.leafEntryId()));
    for (ContextTransform extension : extensions) {
      state = Objects.requireNonNull(extension.transform(state), "context transform result");
    }
    return new SessionContext(state.config(), project(state));
  }

  private List<AgentMessage> project(ContextState state) {
    List<AgentMessage> messages = new ArrayList<>();
    if (state.config().systemPrompt() != null && !state.config().systemPrompt().isBlank()) {
      messages.add(AgentMessage.system(state.config().systemPrompt()));
    }
    for (SessionEntry entry : state.entries()) {
      if (entry.payload() instanceof MessageEntryPayload message) {
        messages.add(message.message());
      } else if (entry.payload() instanceof CustomMessageEntryPayload customMessage) {
        messages.add(customMessage.message());
      } else if (entry.payload() instanceof CompactionEntryPayload compaction) {
        messages.add(AgentMessage.system("Session summary:\n" + compaction.summary()));
      } else if (entry.payload() instanceof BranchSummaryEntryPayload branchSummary) {
        messages.add(AgentMessage.system("Branch summary:\n" + branchSummary.summary()));
      }
    }
    return List.copyOf(messages);
  }
}
