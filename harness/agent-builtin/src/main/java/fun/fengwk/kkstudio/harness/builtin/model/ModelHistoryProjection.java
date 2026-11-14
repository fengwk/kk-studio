package fun.fengwk.kkstudio.harness.builtin.model;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.ForkPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemHostedTool;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.RedactedThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.SystemReminder;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemHostedToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolRemovalBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 压缩感知的历史投影：把不可变 {@link EntryPath} 投影为 Provider messages，不改写 durable 历史。
 *
 * <p>路径上存在 complete 压缩时，以最新 complete 压缩的 summary 作为单个 wrapper USER 消息，并从其 cutEntryId
 * 继续投影。COMPACTION 控制 turn 内部、TURN 边界、MODEL_ATTEMPT_FAILURE 与 ASSISTANT_ERROR 不进入 Provider messages。
 *
 * <p>增量 system 变更只在 USER 消息前插入，避免把工具/系统变更切进 assistant 与 tool 结果之间。
 */
final class ModelHistoryProjection {

  private final ModelRequestSpec spec;
  private final boolean incrementalSystem;
  private final boolean inlineToolChanges;
  private final ProviderMessageProjector projector = new ProviderMessageProjector();
  private final List<ProviderMessage> messages = new ArrayList<>();
  private final List<ProviderMessageProjector.ProjectedMessage> projectedMessages =
      new ArrayList<>();
  private final List<ProviderMessage> deferredSystemMessages = new ArrayList<>();

  private int lastBaselineIndex;
  private int thinkingDropFrom = -1;
  private int thinkingDropBefore = -1;
  private boolean inCompactionTurn;

  ModelHistoryProjection(
      ModelRequestSpec spec, boolean incrementalSystem, boolean inlineToolChanges) {
    this.spec = spec;
    this.incrementalSystem = incrementalSystem;
    this.inlineToolChanges = inlineToolChanges;
  }

  List<ProviderMessage> project(EntryPath path) {
    List<Entry> entries = path.entries();
    lastBaselineIndex = lastBaselineIndex(entries);
    int walkStart = startAfterLatestCompaction(path, entries);
    for (int index = walkStart; index < entries.size(); index++) {
      appendEntry(entries.get(index), index);
    }
    messages.addAll(projector.projectSources(projectedMessages));
    messages.addAll(deferredSystemMessages);
    return List.copyOf(messages);
  }

  private static int lastBaselineIndex(List<Entry> entries) {
    int lastBaselineIndex = -1;
    for (int index = 0; index < entries.size(); index++) {
      if (entries.get(index).payload() instanceof SystemPayload system && system.baseline()) {
        lastBaselineIndex = index;
      }
    }
    return lastBaselineIndex;
  }

  /**
   * 压缩分支从切点重新投影：summary 作为唯一 wrapper USER 消息；Anthropic 的保留段 thinking 绑定旧前缀，记录需要移除的区间。
   *
   * @return 从哪个 Entry 下标开始逐条投影
   */
  private int startAfterLatestCompaction(EntryPath path, List<Entry> entries) {
    Optional<CompactionTurns.CompactionTurn> latest = CompactionTurns.latestComplete(path);
    if (latest.isEmpty()) {
      return 0;
    }
    CompactionTurns.CompactionTurn complete = latest.get();
    int cutIndex = indexOfId(entries, complete.freezing().cutEntryId());
    if (cutIndex < 0 || complete.resultIndex() < 0 || cutIndex > complete.resultIndex()) {
      throw new IllegalStateException(
          "complete compaction references are corrupt on the current path: cutEntryId="
              + complete.freezing().cutEntryId());
    }
    projectedMessages.add(
        ProviderMessageProjector.ProjectedMessage.of(
            AgentMessage.user(
                CompactionPrompts.compactedContext(complete.result().summaryText()))));
    if (spec.providerType() == ProviderType.ANTHROPIC) {
      // 保留段 = (切点, 压缩结果)：只有这里的 assistant thinking 绑定旧前缀，必须在重放时移除；
      // 压缩之后新产生的 assistant 绑定新前缀，必须原样重放，否则 Anthropic 的工具续作会丢掉 thinking。
      thinkingDropFrom = cutIndex;
      thinkingDropBefore = complete.resultIndex();
    }
    return cutIndex;
  }

  private void appendEntry(Entry entry, int index) {
    EntryPayload payload = entry.payload();
    if (payload instanceof TurnStartPayload turnStart) {
      if (turnStart.reason() == TurnStartReason.COMPACTION) {
        inCompactionTurn = true;
      }
      return;
    }
    if (payload instanceof TurnEndPayload) {
      inCompactionTurn = false;
      return;
    }
    if (inCompactionTurn) {
      return;
    }
    if (isIncrementalSystemChange(payload, index)) {
      appendSystemChange((SystemPayload) payload);
      return;
    }
    appendProjectedPayload(payload, index);
  }

  private boolean isIncrementalSystemChange(EntryPayload payload, int index) {
    return incrementalSystem
        && index > lastBaselineIndex
        && payload instanceof SystemPayload system
        && !system.baseline();
  }

  /** 系统变更切在消息之间：先落定已投影消息，再把变更留到下一个 USER 消息前。 */
  private void appendSystemChange(SystemPayload system) {
    flushProjectedMessages();
    ProviderMessage change = systemMessage(system);
    if (change != null) {
      deferredSystemMessages.add(change);
    }
  }

  private void appendProjectedPayload(EntryPayload payload, int index) {
    if (payload instanceof MessagePayload message) {
      appendMessage(message, index);
    } else if (payload instanceof CustomMessagePayload message) {
      projectedMessages.add(ProviderMessageProjector.ProjectedMessage.of(message.message()));
    } else if (payload instanceof NotificationPayload notification) {
      projectedMessages.add(
          ProviderMessageProjector.ProjectedMessage.of(
              wrapNotificationMessage(notification.message())));
    } else if (payload instanceof ForkPayload fork) {
      projectedMessages.add(
          ProviderMessageProjector.ProjectedMessage.of(AgentMessage.user(fork.notice())));
    } else if (payload instanceof AssistantAbortedPayload message) {
      projectedMessages.add(ProviderMessageProjector.ProjectedMessage.of(message.message()));
    }
  }

  private void appendMessage(MessagePayload message, int index) {
    AgentMessage projected = message.message();
    if (projected.role() == AgentMessageRole.ASSISTANT
        && index > thinkingDropFrom
        && index < thinkingDropBefore) {
      projected = withoutThinking(projected);
      if (projected.contents().isEmpty()) {
        // 只剩 thinking 的 assistant 不得变成空消息：它本来就没有可回放的语义内容。
        return;
      }
    }
    projectedMessages.add(
        new ProviderMessageProjector.ProjectedMessage(projected, reasoningEffort(message)));
    if (incrementalSystem
        && !deferredSystemMessages.isEmpty()
        && message.message().role() == AgentMessageRole.USER) {
      flushProjectedMessages();
      messages.addAll(deferredSystemMessages);
      deferredSystemMessages.clear();
    }
  }

  private void flushProjectedMessages() {
    messages.addAll(projector.projectSources(projectedMessages));
    projectedMessages.clear();
  }

  /** 历史 assistant 的 reasoning effort 只在模型身份相同时带入请求，避免把别的模型的声明混进本次调用。 */
  private String reasoningEffort(MessagePayload message) {
    if (message.assistantMetadata() == null) {
      return null;
    }
    ModelSelection model = message.assistantMetadata().model();
    if (model == null || !spec.model().modelName().equals(model.modelName())) {
      return null;
    }
    return message.assistantMetadata().effort();
  }

  /** 一次增量系统变更投影为 SYSTEM 消息；没有文本也没有可内联的工具变更时不产生消息。 */
  private ProviderMessage systemMessage(SystemPayload change) {
    List<ProviderContentBlock> contents = new ArrayList<>();
    String text = SystemContext.renderChangeText(change);
    if (!text.isEmpty()) {
      contents.add(new ProviderTextBlock(text));
    }
    if (inlineToolChanges) {
      appendToolChanges(change, contents);
    }
    return contents.isEmpty() ? null : new ProviderMessage(ProviderMessageRole.SYSTEM, contents);
  }

  private static void appendToolChanges(SystemPayload change, List<ProviderContentBlock> contents) {
    for (String name : change.removedTools()) {
      contents.add(new ProviderSystemToolRemovalBlock(name));
    }
    for (String name : change.removedHostedTools()) {
      contents.add(new ProviderSystemToolRemovalBlock(name));
    }
    for (ProviderToolDefinition tool : change.addedTools()) {
      contents.add(new ProviderSystemToolAdditionBlock(tool));
    }
    for (SystemHostedTool tool : change.addedHostedTools()) {
      contents.add(new ProviderSystemHostedToolAdditionBlock(tool.definitionJson()));
    }
  }

  private static AgentMessage withoutThinking(AgentMessage message) {
    List<AgentMessageContent> contents = new ArrayList<>(message.contents().size());
    for (AgentMessageContent content : message.contents()) {
      if (!(content instanceof ThinkingMessageContent)
          && !(content instanceof RedactedThinkingMessageContent)) {
        contents.add(content);
      }
    }
    return new AgentMessage(message.role(), contents);
  }

  private static int indexOfId(List<Entry> entries, UUID entryId) {
    for (int index = 0; index < entries.size(); index++) {
      if (entries.get(index).id().equals(entryId)) {
        return index;
      }
    }
    return -1;
  }

  /**
   * NOTIFICATION 在模型投影单一边界包装为 system-reminder。
   *
   * <p>依据 {@link NotificationPayload} 事实判断来源，保持历史原始角色与数据库 UI 原文，不改 provider 契约。
   */
  private static AgentMessage wrapNotificationMessage(AgentMessage message) {
    List<AgentMessageContent> wrappedContents = new ArrayList<>(message.contents().size());
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent text) {
        String wrappedText =
            SystemReminder.isReminderText(text.text())
                ? text.text()
                : SystemReminder.wrap(text.text());
        wrappedContents.add(new TextMessageContent(wrappedText));
      } else {
        wrappedContents.add(content);
      }
    }
    return new AgentMessage(message.role(), wrappedContents);
  }
}
