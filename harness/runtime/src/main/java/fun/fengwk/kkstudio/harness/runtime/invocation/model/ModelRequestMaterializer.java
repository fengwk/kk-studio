package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.GoalMessages;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 唯一请求重建边界：从不可变 {@link EntryPath} 与冻结 {@link ModelRequestSpec} 纯投影内存 {@link ProviderRequest}。
 *
 * <p>不访问 catalog、Environment registry 或 Contributor ContextProjector；也不持有事务。压缩执行已完全移出本
 * materializer：压缩子 Thread 的首条输入由 {@code CompactionChildStarter} 以普通 CUSTOM_MESSAGE 构造，父 COMPACTION
 * turn 不创建 ModelInvocation，也不在请求内复制 compaction facts。
 *
 * <p>压缩感知历史投影还把执行本次压缩的 COMPACTION TURN_START 冻结 settings 中的 Goal 作为有界 USER 级历史背景放在摘要之后、真实近期消息之前：
 * 背景逐字来自该冻结快照（同一压缩每次重建结果相同），已被切掉或仍在近期消息中都不改变它，后续 Goal 设置 / 清除由真实输入消息自身携带。 Goal 只作为用户级背景，绝不提升为
 * SYSTEM，背景也不是新指令。
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
   * CompactionChildStarter} 用共享 {@link CompactionPrompts} 构造，父 COMPACTION turn 不产生 ModelInvocation。
   */
  public ProviderRequest materialize(EntryPath path, ModelRequestSpec spec) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(spec, "spec");
    return materializeLive(path, spec);
  }

  private ProviderRequest materializeLive(EntryPath path, ModelRequestSpec spec) {
    List<ProviderToolDefinition> tools = providerTools(spec.toolBindings());
    List<ProviderMessageProjector.ProjectedMessage> projectedMessages = new ArrayList<>();
    appendHistory(path, projectedMessages);
    return new ProviderRequest(
        spec.model(),
        spec.variant(),
        spec.outputTokens(),
        spec.systemInstruction(),
        projector(spec.toolBindings()).projectSources(projectedMessages),
        tools,
        spec.cacheControl());
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
      EntryPath path, List<ProviderMessageProjector.ProjectedMessage> projectedMessages) {
    List<Entry> entries = path.entries();
    int walkStart = 0;
    int compactionResultIndex = -1;
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
      appendGoalBackground(complete.start().settings().goal(), projectedMessages);
      walkStart = cutIndex;
      compactionResultIndex = complete.resultIndex();
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
      boolean suppressReplay = (compactionResultIndex >= 0 && i <= compactionResultIndex);
      ProviderReplayState replayState = suppressReplay ? null : entry.providerReplayState();

      if (payload instanceof MessagePayload message) {
        projectedMessages.add(
            ProviderMessageProjector.ProjectedMessage.of(message.message(), replayState));
      } else if (payload instanceof CustomMessagePayload message) {
        projectedMessages.add(ProviderMessageProjector.ProjectedMessage.of(message.message()));
      } else if (payload instanceof NotificationPayload notification) {
        projectedMessages.add(ProviderMessageProjector.ProjectedMessage.of(notification.message()));
      } else if (payload instanceof ForkPayload) {
        projectedMessages.add(
            ProviderMessageProjector.ProjectedMessage.of(AgentMessage.user(ForkPayload.NOTICE)));
      } else if (payload instanceof AssistantAbortedPayload message) {
        projectedMessages.add(ProviderMessageProjector.ProjectedMessage.of(message.message()));
      }
    }
  }

  /**
   * 压缩后 Goal 的用户级历史背景：唯一来源是最新 complete 压缩的冻结 settings 快照（{@code start().settings().goal()}），因此同一
   * 压缩每次重建都得到逐字相同的背景，不扫描后续 Goal 切换、不做文本级替换，也不因后续真实输入而改变。后续 Goal 设置 / 清除由真实输入 消息自身携带；冻结快照无 Goal
   * 时只陈述该事实，绝不复活更早的目标。
   */
  private static void appendGoalBackground(
      GoalSetting goal, List<ProviderMessageProjector.ProjectedMessage> projectedMessages) {
    projectedMessages.add(
        ProviderMessageProjector.ProjectedMessage.of(
            goal == null
                ? GoalMessages.backgroundCleared()
                : GoalMessages.backgroundSet(goal.text())));
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
}
