package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryInput;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 唯一请求重建边界：从不可变 {@link EntryPath} 与冻结 {@link ModelRequestSpec} 纯投影内存 {@link ProviderRequest}。
 *
 * <p>不访问 catalog、Environment registry 或 Plugin ContextProjector；也不持有事务。
 */
public final class ModelRequestMaterializer {

  private final ProviderMessageProjector messageProjector;
  private final ToolDescriptorJsonCodec toolDescriptorCodec;

  public ModelRequestMaterializer() {
    this(new ProviderMessageProjector(), new ToolDescriptorJsonCodec());
  }

  ModelRequestMaterializer(
      ProviderMessageProjector messageProjector, ToolDescriptorJsonCodec toolDescriptorCodec) {
    this.messageProjector = Objects.requireNonNull(messageProjector, "messageProjector");
    this.toolDescriptorCodec = Objects.requireNonNull(toolDescriptorCodec, "toolDescriptorCodec");
  }

  public ProviderRequest materialize(EntryPath path, ModelRequestSpec spec) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(spec, "spec");
    if (spec.compaction() != null) {
      return materializeCompaction(path, spec);
    }
    return materializeLive(path, spec);
  }

  private ProviderRequest materializeLive(EntryPath path, ModelRequestSpec spec) {
    List<AgentMessage> semanticMessages = new ArrayList<>(spec.preambleMessages());
    appendHistory(path, semanticMessages);
    return new ProviderRequest(
        spec.model(),
        spec.variant(),
        messageProjector.project(semanticMessages),
        providerTools(spec.toolBindings()),
        spec.cacheControl());
  }

  private ProviderRequest materializeCompaction(EntryPath path, ModelRequestSpec spec) {
    CompactionRequest compaction = spec.compaction();
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
  private static void appendHistory(EntryPath path, List<AgentMessage> semanticMessages) {
    List<Entry> entries = path.entries();
    int walkStart = 0;
    CompactionPayload latestComplete = latestCompleteCompaction(entries);
    if (latestComplete != null) {
      int compactionIndex = indexOfPayload(entries, latestComplete);
      int cutIndex = indexOfId(entries, latestComplete.cutEntryId());
      int firstKeptIndex = indexOfId(entries, latestComplete.firstKeptEntryId());
      if (cutIndex < 0
          || firstKeptIndex < 0
          || compactionIndex < 0
          || firstKeptIndex > cutIndex
          || cutIndex >= compactionIndex) {
        throw new IllegalStateException(
            "complete compaction references are corrupt on the current path: firstKeptEntryId="
                + latestComplete.firstKeptEntryId()
                + " cutEntryId="
                + latestComplete.cutEntryId());
      }
      semanticMessages.add(
          AgentMessage.user(CompactionPrompts.compactedContext(latestComplete.summaryText())));
      walkStart = cutIndex;
    }
    boolean inCompactionTurn = false;
    for (int i = walkStart; i < entries.size(); i++) {
      EntryPayload payload = entries.get(i).payload();
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
        semanticMessages.add(message.message());
      } else if (payload instanceof CustomMessagePayload message) {
        semanticMessages.add(message.message());
      } else if (payload instanceof AssistantAbortedPayload message) {
        semanticMessages.add(message.message());
      }
    }
  }

  private List<ProviderToolDefinition> providerTools(List<ToolBinding> toolBindings) {
    List<ProviderToolDefinition> tools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      ToolDescriptor tool = binding.descriptor();
      tools.add(
          new ProviderToolDefinition(
              tool.name(),
              tool.description(),
              toolDescriptorCodec.encodeInputSchema(tool.inputSchema())));
    }
    return List.copyOf(tools);
  }

  private static CompactionPayload latestCompleteCompaction(List<Entry> entries) {
    CompactionPayload latest = null;
    for (Entry entry : entries) {
      if (entry.payload() instanceof CompactionPayload payload && payload.complete()) {
        latest = payload;
      }
    }
    return latest;
  }

  private static int indexOfId(List<Entry> entries, UUID entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfPayload(List<Entry> entries, CompactionPayload payload) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).payload() == payload) {
        return i;
      }
    }
    return -1;
  }
}
