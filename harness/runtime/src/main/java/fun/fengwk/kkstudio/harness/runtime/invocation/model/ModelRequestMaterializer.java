package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryInput;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
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
 * <p>不访问 catalog、Environment registry 或 Contributor ContextProjector；也不持有事务。压缩摘要调用通过 basis
 * EntryPath 末尾 owned {@code TURN_START.compaction}（{@link #compactionStartAtHead}）识别——closed
 * Invocation 后仍可从 Entry 恢复 fallback / split 元数据，不再在请求内复制 compaction facts。
 *
 * <p>压缩感知历史投影还把当前生效的用户 Goal 作为有界 USER 级历史背景放在摘要之后、真实近期消息之前：原始 Goal 输入消息仍在近期消息 中时不重复插入；已被切掉时插入当前
 * Goal 原文（或当前无 Goal 的事实说明）。Goal 只作为用户级背景，绝不提升为 SYSTEM， 背景也不是新指令。
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

  public ProviderRequest materialize(EntryPath path, ModelRequestSpec spec) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(spec, "spec");
    CompactionStart compaction = compactionStartAtHead(path);
    if (compaction != null) {
      return materializeCompaction(path, spec, compaction);
    }
    return materializeLive(path, spec);
  }

  /** basis path 末尾条目是 owned COMPACTION TURN_START 时返回其冻结元数据，否则 null。 */
  public static CompactionStart compactionStartAtHead(EntryPath path) {
    Entry head = path.head();
    if (head.payload() instanceof TurnStartPayload start
        && start.reason() == TurnStartReason.COMPACTION) {
      return start.compaction();
    }
    return null;
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

  /** 摘要调用：summarization system prompt 是唯一 systemInstruction，conversation 是一个 USER 消息。 */
  private ProviderRequest materializeCompaction(
      EntryPath path, ModelRequestSpec spec, CompactionStart compaction) {
    CompactionSummaryInput input = CompactionPlanner.reconstructSummaryInput(path, compaction);
    String userPrompt =
        compaction.phase() == CompactionPhase.TURN_PREFIX
            ? CompactionPrompts.turnPrefixUserPrompt(input.messages())
            : CompactionPrompts.summaryUserPrompt(input.messages(), input.previousSummary());
    ProviderMessageProjector projector = projector(List.of());
    return new ProviderRequest(
        spec.model(),
        spec.variant(),
        spec.outputTokens(),
        CompactionPrompts.summarizationSystemPrompt(),
        projector.project(List.of(AgentMessage.user(userPrompt))),
        List.of(),
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
      appendGoalBackground(path, entries, cutIndex, projectedMessages);
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
      } else if (payload instanceof AssistantAbortedPayload message) {
        projectedMessages.add(ProviderMessageProjector.ProjectedMessage.of(message.message()));
      }
    }
  }

  /**
   * 压缩后当前 Goal 的用户级历史背景：找出最近一次 Goal 变更（settings 快照切换）所对应的冻结 USER 输入消息，只有它已被 {@code cutIndex}
   * 切掉时才插入背景；Goal 从未变更则没有需要补的背景。背景文本由当前 settings 快照派生，因此清除后不会 复活旧目标。
   */
  private static void appendGoalBackground(
      EntryPath path,
      List<Entry> entries,
      int cutIndex,
      List<ProviderMessageProjector.ProjectedMessage> projectedMessages) {
    GoalSetting goal = path.baseSettings().goal();
    int transitionIndex = -1;
    GoalSetting effective = ((RootPayload) entries.get(0).payload()).settings().goal();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).payload() instanceof TurnStartPayload start
          && start.reason() != TurnStartReason.COMPACTION
          && !Objects.equals(start.settings().goal(), effective)) {
        effective = start.settings().goal();
        transitionIndex = i;
      }
    }
    if (goal == null && transitionIndex < 0) {
      return;
    }
    if (transitionIndex >= 0 && goalInputMessageRetained(entries, transitionIndex, cutIndex)) {
      return;
    }
    projectedMessages.add(
        ProviderMessageProjector.ProjectedMessage.of(
            goal == null
                ? GoalMessages.backgroundCleared()
                : GoalMessages.backgroundSet(goal.text())));
  }

  /**
   * 该次 Goal 变更的冻结 USER 输入消息是否仍在真实近期消息中（cut entry 本身属于保留区间）。Goal 输入消息是变更 TURN_START 之后、下一个
   * TURN_START 之前的首条 USER Message；找不到（例如路径被截断）时视为已切掉。
   */
  private static boolean goalInputMessageRetained(
      List<Entry> entries, int transitionIndex, int cutIndex) {
    for (int i = transitionIndex + 1; i < entries.size(); i++) {
      EntryPayload payload = entries.get(i).payload();
      if (payload instanceof TurnStartPayload || payload instanceof TurnEndPayload) {
        return false;
      }
      if (payload instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.USER) {
        return i >= cutIndex;
      }
    }
    return false;
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
