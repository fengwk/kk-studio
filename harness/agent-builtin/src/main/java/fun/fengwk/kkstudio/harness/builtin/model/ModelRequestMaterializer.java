package fun.fengwk.kkstudio.harness.builtin.model;

import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
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
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemHostedToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolRemovalBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionChildSpawn;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 唯一请求重建边界：从不可变 {@link EntryPath} 与冻结 {@link ModelRequestSpec} 纯投影内存 {@link ProviderRequest}。
 *
 * <p>不访问 catalog 或 Environment registry，也不持有事务。压缩执行不在本 materializer 内发生：COMPACTION 分支由 {@link
 * CompactionChildSpawn} 经统一 spawn 创建，首条输入是普通 CUSTOM_MESSAGE，父 COMPACTION turn 不创建
 * ModelInvocation。本类只把 durable 事实投影成请求：压缩后的主 Thread 以 摘要 wrapper 替代切点前历史，分支第一次请求在切点前最后一条 assistant
 * 之前写入唯一只读缓存断点。
 *
 * <p>历史工具调用与结果按 durable Entry 投影，不根据当前工具绑定或 Environment 重新分类；没有存储结果的调用由历史正规化补齐 synthetic 结果。
 */
public final class ModelRequestMaterializer {

  private final SchemaJsonCodec schemaCodec;

  public ModelRequestMaterializer() {
    this(new SchemaJsonCodec());
  }

  ModelRequestMaterializer(SchemaJsonCodec schemaCodec) {
    this.schemaCodec = Objects.requireNonNull(schemaCodec, "schemaCodec");
  }

  /**
   * 纯投影一次 Model 请求：压缩执行的 spawning 与结算不在本类发生；分支首条输入由 {@code CompactionChildSpawn} 用共享 {@link
   * CompactionPrompts} 构造，父 COMPACTION turn 不产生 ModelInvocation。
   */
  public ProviderRequest materialize(EntryPath path, ModelRequestSpec spec) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(spec, "spec");
    return materializeLive(path, spec);
  }

  private ProviderRequest materializeLive(EntryPath path, ModelRequestSpec spec) {
    SystemContext context = path.systemContext().orElse(null);
    boolean incrementalSystem =
        spec.providerType() == ProviderType.ANTHROPIC
            && spec.model().midConversationSystemMessages()
            && context != null;
    boolean baselineHasClientTools =
        incrementalSystem && !SystemContext.baselineTools(path.entries()).isEmpty();
    List<ProviderToolDefinition> effectiveTools =
        context == null ? providerTools(spec.toolBindings()) : context.tools();
    String systemInstruction =
        context == null
            ? spec.systemInstruction()
            : incrementalSystem
                ? SystemContext.baselineSystemInstruction(path.entries())
                : SystemContext.collapseSystemInstruction(path.entries());
    List<ProviderToolDefinition> tools =
        baselineHasClientTools ? SystemContext.baselineTools(path.entries()) : effectiveTools;
    List<ProviderMessage> messages = new ArrayList<>();
    appendHistory(path, spec, incrementalSystem, baselineHasClientTools, messages);
    ModelVariant variant = spec.variant();
    if (incrementalSystem) {
      List<SystemHostedTool> requestHostedTools =
          baselineHasClientTools
              ? SystemContext.baselineHostedTools(path.entries())
              : context.hostedTools();
      List<ProviderProtocolOptions.ToolDefinition> toolDefinitions =
          requestHostedTools.stream()
              .map(
                  tool ->
                      new ProviderProtocolOptions.ToolDefinition(
                          tool.name(), tool.definitionJson()))
              .toList();
      variant =
          new ModelVariant(
              variant.id(),
              variant.reasoningEffort(),
              variant.promptCacheRetention(),
              variant.protocolOptions().withToolDefinitions(toolDefinitions));
    }
    return new ProviderRequest(
        spec.model(),
        variant,
        spec.outputTokens(),
        systemInstruction,
        messages,
        tools,
        cacheControl(path, spec, messages),
        baselineHasClientTools);
  }

  /**
   * 压缩感知历史投影：路径上存在 complete 压缩时，以最新 complete 压缩的 summary 作为单个 wrapper USER 消息，并从其 cutEntryId
   * 继续投影。COMPACTION 控制 turn 内部、TURN 边界、MODEL_ATTEMPT_FAILURE 与 ASSISTANT_ERROR 不进入 Provider
   * messages。
   */
  private void appendHistory(
      EntryPath path,
      ModelRequestSpec spec,
      boolean incrementalSystem,
      boolean inlineToolChanges,
      List<ProviderMessage> messages) {
    List<Entry> entries = path.entries();
    List<ProviderMessageProjector.ProjectedMessage> projectedMessages = new ArrayList<>();
    List<ProviderMessage> deferredSystemMessages = new ArrayList<>();
    ProviderMessageProjector projector = new ProviderMessageProjector();
    int lastBaselineIndex = -1;
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).payload() instanceof SystemPayload system && system.baseline()) {
        lastBaselineIndex = i;
      }
    }
    int walkStart = 0;
    // 保留段 = (切点, 压缩结果)：只有这里的 Claude thinking 绑定旧前缀，必须在重放时移除。
    // 压缩之后新产生的 assistant 绑定新前缀，必须原样重放；否则 Anthropic 的工具续作会丢掉 thinking。
    int thinkingDropFrom = -1;
    int thinkingDropBefore = -1;
    var latest = CompactionTurns.latestComplete(path);
    if (latest.isPresent()) {
      var complete = latest.get();
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
      walkStart = cutIndex;
      if (spec.providerType() == ProviderType.ANTHROPIC) {
        thinkingDropFrom = cutIndex;
        thinkingDropBefore = complete.resultIndex();
      }
    }
    boolean inCompactionTurn = false;
    for (int i = walkStart; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      EntryPayload payload = entry.payload();
      if (payload instanceof TurnStartPayload turnStart) {
        if (turnStart.reason() == TurnStartReason.COMPACTION) {
          inCompactionTurn = true;
        }
        continue;
      }
      if (payload instanceof TurnEndPayload) {
        inCompactionTurn = false;
        continue;
      }
      if (inCompactionTurn) {
        continue;
      }
      if (incrementalSystem
          && i > lastBaselineIndex
          && payload instanceof SystemPayload system
          && !system.baseline()) {
        messages.addAll(projector.projectSources(projectedMessages));
        projectedMessages.clear();
        ProviderMessage change = systemMessage(system, inlineToolChanges);
        if (change != null) {
          deferredSystemMessages.add(change);
        }
        continue;
      }
      if (payload instanceof MessagePayload message) {
        AgentMessage projected = message.message();
        if (projected.role() == AgentMessageRole.ASSISTANT
            && i > thinkingDropFrom
            && i < thinkingDropBefore) {
          projected = withoutThinking(projected);
          if (projected.contents().isEmpty()) {
            // 只剩 thinking 的 assistant 不得变成空消息：它本来就没有可回放的语义内容。
            continue;
          }
        }
        projectedMessages.add(
            new ProviderMessageProjector.ProjectedMessage(
                projected, reasoningEffort(message, spec)));
        if (incrementalSystem
            && !deferredSystemMessages.isEmpty()
            && message.message().role() == AgentMessageRole.USER) {
          messages.addAll(projector.projectSources(projectedMessages));
          projectedMessages.clear();
          messages.addAll(deferredSystemMessages);
          deferredSystemMessages.clear();
        }
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
    messages.addAll(projector.projectSources(projectedMessages));
    messages.addAll(deferredSystemMessages);
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

  /**
   * 请求缓存控制：压缩分支第一次请求只读复用主 Thread 已写入的前缀，仅在切点前最后一条 assistant 之前的消息上放一个断点。
   *
   * <p>具体模型与产生该 assistant 的模型不同、或距该请求已超过留存 TTL 时退回常规会话缓存控制（不再额外写断点）。
   */
  private static ProviderCacheControl cacheControl(
      EntryPath path, ModelRequestSpec spec, List<ProviderMessage> messages) {
    String sessionId = path.root().sessionId().toString();
    PromptCacheRetention retention = spec.variant().promptCacheRetention();
    Integer breakpoint = branchMessageBreakpoint(path, spec, messages);
    return breakpoint == null
        ? ProviderCacheControl.session(retention, sessionId)
        : ProviderCacheControl.readOnlyMessage(retention, sessionId, breakpoint);
  }

  private static Integer branchMessageBreakpoint(
      EntryPath path, ModelRequestSpec spec, List<ProviderMessage> messages) {
    if (spec.providerType() != ProviderType.ANTHROPIC
        || spec.variant().promptCacheRetention() == PromptCacheRetention.NONE) {
      return null;
    }
    var openTurn = path.openTurnStart();
    if (openTurn.isEmpty()
        || !(openTurn.get().payload() instanceof TurnStartPayload start)
        || start.reason() != TurnStartReason.COMPACTION_BRANCH_INPUT) {
      return null;
    }
    Entry lastAssistant = null;
    for (Entry entry : path.entries()) {
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        lastAssistant = entry;
      }
    }
    if (lastAssistant == null) {
      return null;
    }
    MessagePayload assistant = (MessagePayload) lastAssistant.payload();
    if (assistant.assistantMetadata() == null
        || assistant.assistantMetadata().model() == null
        || !spec.model().modelName().equals(assistant.assistantMetadata().model().modelName())) {
      return null;
    }
    Duration ttl =
        spec.variant().promptCacheRetention() == PromptCacheRetention.LONG
            ? Duration.ofHours(1)
            : Duration.ofMinutes(5);
    if (Duration.between(lastAssistant.createdAt(), openTurn.get().createdAt()).compareTo(ttl)
        > 0) {
      return null;
    }
    int lastAssistantMessage = -1;
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i).role() == ProviderMessageRole.ASSISTANT) {
        lastAssistantMessage = i;
      }
    }
    return lastAssistantMessage <= 0 ? null : lastAssistantMessage - 1;
  }

  private static String reasoningEffort(MessagePayload message, ModelRequestSpec spec) {
    if (message.assistantMetadata() == null) {
      return null;
    }
    ModelSelection model = message.assistantMetadata().model();
    if (model == null || !spec.model().modelName().equals(model.modelName())) {
      return null;
    }
    return message.assistantMetadata().effort();
  }

  private ProviderMessage systemMessage(SystemPayload change, boolean inlineToolChanges) {
    List<ProviderContentBlock> contents = new ArrayList<>();
    String text = SystemContext.renderChangeText(change);
    if (!text.isEmpty()) {
      contents.add(new ProviderTextBlock(text));
    }
    if (inlineToolChanges) {
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
    return contents.isEmpty() ? null : new ProviderMessage(ProviderMessageRole.SYSTEM, contents);
  }

  private List<ProviderToolDefinition> providerTools(List<ToolBinding> toolBindings) {
    List<ProviderToolDefinition> tools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      tools.add(providerTool(binding));
    }
    return List.copyOf(tools);
  }

  private ProviderToolDefinition providerTool(ToolBinding binding) {
    ToolDescriptor tool = binding.descriptor();
    return new ProviderToolDefinition(
        tool.name(), tool.description(), schemaCodec.encode(tool.inputSchema()));
  }

  private static int indexOfId(List<Entry> entries, UUID entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
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
