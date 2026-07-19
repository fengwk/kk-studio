package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.BranchSummaryEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * head entry path 消息投影 + 外部注入的 Thread 运行时配置。
 *
 * <p>配置与消息路径分离：config 来自 Thread 状态与当前 AgentDefinition，不从 Entry path fold。
 */
public final class SessionContextBuilder {
  private final SessionEntryStore entryStore;
  private final DefaultContextTransform defaultTransform;
  private final List<ContextTransform> extensions;

  public SessionContextBuilder(
      SessionEntryStore entryStore,
      DefaultContextTransform defaultTransform,
      List<ContextTransform> extensions) {
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.defaultTransform = Objects.requireNonNull(defaultTransform, "defaultTransform");
    this.extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
  }

  public SessionContext build(long sessionId, long headEntryId, AgentRuntimeConfig config) {
    if (sessionId <= 0 || headEntryId <= 0) {
      throw new IllegalArgumentException("sessionId and headEntryId must be positive");
    }
    Objects.requireNonNull(config, "config");
    List<SessionEntry> entries =
        defaultTransform.transform(entryStore.loadPath(sessionId, headEntryId));
    ContextState state = new ContextState(config, entries);
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
