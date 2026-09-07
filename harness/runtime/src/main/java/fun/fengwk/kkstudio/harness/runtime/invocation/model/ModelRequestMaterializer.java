package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryInput;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
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
 */
public final class ModelRequestMaterializer {

  private final ProviderMessageProjector messageProjector;
  private final SchemaJsonCodec schemaCodec;

  public ModelRequestMaterializer() {
    this(new ProviderMessageProjector(), new SchemaJsonCodec());
  }

  ModelRequestMaterializer(ProviderMessageProjector messageProjector, SchemaJsonCodec schemaCodec) {
    this.messageProjector = Objects.requireNonNull(messageProjector, "messageProjector");
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
    List<ProviderMessageProjector.ProjectedMessage> projectedMessages = new ArrayList<>();
    for (AgentMessage preamble : spec.preambleMessages()) {
      projectedMessages.add(ProviderMessageProjector.ProjectedMessage.of(preamble));
    }
    appendHistory(path, projectedMessages);
    return new ProviderRequest(
        spec.model(),
        spec.variant(),
        messageProjector.projectSources(projectedMessages),
        providerTools(spec.toolBindings()),
        spec.cacheControl());
  }

  /** 摘要调用：永远构建一个 SYSTEM（summarization system）+ 一个 USER（conversation + summary prompt）请求。 */
  private ProviderRequest materializeCompaction(
      EntryPath path, ModelRequestSpec spec, CompactionStart compaction) {
    CompactionSummaryInput input = CompactionPlanner.reconstructSummaryInput(path, compaction);
    List<AgentMessage> semanticMessages = new ArrayList<>(2);
    semanticMessages.add(AgentMessage.system(CompactionPrompts.summarizationSystemPrompt()));
    String userPrompt =
        compaction.phase() == CompactionPhase.TURN_PREFIX
            ? CompactionPrompts.turnPrefixUserPrompt(input.messages())
            : CompactionPrompts.summaryUserPrompt(input.messages(), input.previousSummary());
    semanticMessages.add(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(userPrompt))));
    return new ProviderRequest(
        spec.model(),
        spec.variant(),
        messageProjector.project(semanticMessages),
        List.of(),
        spec.cacheControl());
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
