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
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.SystemReminder;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 唯一请求重建边界：从不可变 {@link EntryPath} 与冻结 {@link ModelRequestSpec} 纯投影内存 {@link ProviderRequest}。
 *
 * <p>不访问 catalog 或 Environment registry，也不持有事务。压缩执行已完全移出本 materializer：压缩子 Thread 由 {@code
 * CompactionChildSpawn} 经统一 spawn 创建，首条输入是普通 CUSTOM_MESSAGE； 父 COMPACTION turn 不创建
 * ModelInvocation，也不在请求内复制 compaction facts。
 *
 * <p>历史 native 资格按次判定：toolName 必须在当前 {@code toolBindings} 中，且环境工具冻结的 Environment 名必须与当前 binding
 * 一致；其余调用及结果在投影时降级为 USER 自然语言上下文，durable Entry 永不被改写。
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
   * 纯投影一次普通 Model 请求：压缩执行已完全移出本 materializer——压缩子 Thread 的首条输入是普通 CUSTOM_MESSAGE，由 {@code
   * CompactionChildSpawn} 用共享 {@link CompactionPrompts} 构造，父 COMPACTION turn 不产生 ModelInvocation。
   */
  public ProviderRequest materialize(EntryPath path, ModelRequestSpec spec) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(spec, "spec");
    return materializeLive(path, spec);
  }

  private ProviderRequest materializeLive(EntryPath path, ModelRequestSpec spec) {
    List<ProviderToolDefinition> tools = providerTools(spec.toolBindings());
    List<ProviderMessageProjector.ProjectedMessage> projectedMessages = new ArrayList<>();
    appendHistory(path, spec, projectedMessages);
    return new ProviderRequest(
        spec.model(),
        spec.variant(),
        spec.outputTokens(),
        spec.systemInstruction(),
        projector(spec.toolBindings()).projectSources(projectedMessages),
        tools,
        ProviderCacheControl.session(
            spec.variant().promptCacheRetention(), path.root().sessionId().toString()));
  }

  private ProviderMessageProjector projector(List<ToolBinding> toolBindings) {
    List<ProviderMessageProjector.NativeTool> nativeTools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      nativeTools.add(
          new ProviderMessageProjector.NativeTool(
              binding.descriptor().name(), binding.environmentName()));
    }
    return new ProviderMessageProjector(nativeTools);
  }

  /**
   * 压缩感知历史投影：路径上存在 complete 压缩时，以最新 complete 压缩的 summary 作为单个 wrapper USER 消息，并从其 cutEntryId
   * 继续投影。COMPACTION 控制 turn 内部、TURN 边界、MODEL_ATTEMPT_FAILURE 与 ASSISTANT_ERROR 不进入 Provider
   * messages。
   */
  private static void appendHistory(
      EntryPath path,
      ModelRequestSpec spec,
      List<ProviderMessageProjector.ProjectedMessage> projectedMessages) {
    List<Entry> entries = path.entries();
    int walkStart = 0;
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
      if (payload instanceof MessagePayload message) {
        projectedMessages.add(
            new ProviderMessageProjector.ProjectedMessage(
                message.message(), reasoningEffort(message, spec)));
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
  }

  private static String reasoningEffort(MessagePayload message, ModelRequestSpec spec) {
    if (message.assistantMetadata() == null) {
      return null;
    }
    ModelSelection model = message.assistantMetadata().model();
    if (model == null || !spec.model().modelName().equals(model.modelName())) {
      return null;
    }
    for (ModelVariant variant : spec.modelVariants()) {
      if (variant.id().equals(model.variant())) {
        return variant.reasoningEffort();
      }
    }
    return null;
  }

  private List<ProviderToolDefinition> providerTools(List<ToolBinding> toolBindings) {
    List<ProviderToolDefinition> tools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      ToolDescriptor tool = binding.descriptor();
      tools.add(
          new ProviderToolDefinition(
              tool.name(), tool.description(), schemaCodec.encode(tool.inputSchema())));
    }
    return List.copyOf(tools);
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
