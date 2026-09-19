package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将语义 Context 投影为与 Provider SDK 无关的 ProviderMessage。
 *
 * <p>历史工具调用的 native 资格按次判定：只有当调用名在当前请求的 {@code nativeToolNames} 中时，assistant tool call 与 其 TOOL
 * 结果才按 native provider 结构投影。其余调用降级为 assistant 文本（描述调用与逐字 arguments），其结果降级为 USER 内容，绝不产生 TOOL
 * 消息。降级只发生在投影结果中，durable Entry 与 callIndex 永不被改写。
 *
 * <p>同一 assistant 之后的 native TOOL 结果先于降级 USER 结果输出，以保证 provider 要求的 tool-call adjacency；组内保持相对顺序。
 * 被降级改写的 assistant 消息不再携带 {@link ProviderReplayState}（native payload 已与投影内容不一致），未被改写的消息保留。
 *
 * <p>对当前连续 Tool chain 中未配对的 native ToolCall，在角色切换或 Context 结束前合成 error ToolResult：{@code No result
 * provided}；降级调用只在文本中体现，不合成结果。Synthetic result 只存在于本次 Provider request，不写 Session Entry。
 */
public final class ProviderMessageProjector {
  private static final String ORPHAN_RESULT_TEXT = "No result provided";

  /** 动态围栏的最小反引号数；内容中的任意反引号连续段都会被安全包含。 */
  private static final int MIN_FENCE_LENGTH = 3;

  public record ProjectedMessage(AgentMessage message, ProviderReplayState replayState) {
    public ProjectedMessage {
      Objects.requireNonNull(message, "message");
      if (replayState != null && message.role() != AgentMessageRole.ASSISTANT) {
        throw new IllegalArgumentException("replayState is only allowed for ASSISTANT messages");
      }
    }

    public static ProjectedMessage of(AgentMessage message) {
      return new ProjectedMessage(message, null);
    }

    public static ProjectedMessage of(AgentMessage message, ProviderReplayState replayState) {
      return new ProjectedMessage(message, replayState);
    }
  }

  private final Set<String> nativeToolNames;

  /**
   * @param nativeToolNames 当前请求实际绑定的工具名；历史调用只有名字在此集合中时才保持 native
   */
  public ProviderMessageProjector(Set<String> nativeToolNames) {
    this.nativeToolNames = Set.copyOf(Objects.requireNonNull(nativeToolNames, "nativeToolNames"));
  }

  public List<ProviderMessage> project(List<AgentMessage> messages) {
    Objects.requireNonNull(messages, "messages");
    return projectSources(messages.stream().map(ProjectedMessage::of).toList());
  }

  public List<ProviderMessage> projectSources(List<ProjectedMessage> sources) {
    Objects.requireNonNull(sources, "sources");
    List<ProviderMessage> result = new ArrayList<>();
    Batch batch = new Batch();
    for (ProjectedMessage source : sources) {
      AgentMessage message = source.message();
      if (message.role() == AgentMessageRole.TOOL) {
        batch.add(projectToolResult((ToolResultMessageContent) message.contents().get(0)));
        continue;
      }
      batch.flush(result);
      if (message.role() == AgentMessageRole.ASSISTANT) {
        result.add(projectAssistant(message, source.replayState(), batch));
        continue;
      }
      result.add(
          new ProviderMessage(ProviderMessageRole.USER, projectContents(message.contents())));
    }
    batch.flush(result);
    return List.copyOf(result);
  }

  /** 一条 assistant 消息的投影结果：内容块，以及是否因降级改写过（决定 replay state 是否保留）。 */
  private ProviderMessage projectAssistant(
      AgentMessage message, ProviderReplayState replayState, Batch batch) {
    List<ProviderContentBlock> contents = new ArrayList<>(message.contents().size());
    boolean downgraded = false;
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof ToolCallMessageContent call
          && !nativeToolNames.contains(call.toolName())) {
        contents.add(new ProviderTextBlock(describeDowngradedCall(call)));
        downgraded = true;
        continue;
      }
      ProviderContentBlock block = projectContent(content);
      if (block instanceof ProviderToolCallBlock toolCall) {
        batch.openNativeCalls.add(toolCall);
      }
      contents.add(block);
    }
    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT, contents, downgraded ? null : replayState);
  }

  /** 一条 TOOL 结果要么保持 native，要么降级为 USER 内容。 */
  private PendingResult projectToolResult(ToolResultMessageContent content) {
    ProviderToolResultBlock nativeResult =
        new ProviderToolResultBlock(
            content.toolCallId(),
            content.toolName(),
            projectContents(content.contents()),
            content.error(),
            content.detailsJson());
    if (nativeToolNames.contains(content.toolName())) {
      return new PendingResult.Native(nativeResult);
    }
    return new PendingResult.Downgraded(downgradeResult(nativeResult));
  }

  /** 降级 TOOL 结果：可读文本包进动态围栏；无法表示为文本的 durable resource 块作为显式说明后的 USER 块保留。 */
  private static List<ProviderContentBlock> downgradeResult(ProviderToolResultBlock result) {
    List<ProviderContentBlock> blocks = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    text.append("Tool result for `")
        .append(result.toolName())
        .append("` (call id `")
        .append(result.toolCallId())
        .append("`)")
        .append(result.error() ? " failed" : "")
        .append(":\n");
    List<ProviderContentBlock> resources = new ArrayList<>();
    for (ProviderContentBlock nested : result.contents()) {
      if (nested instanceof ProviderTextBlock textBlock) {
        text.append(fence(textBlock.text())).append('\n');
      } else if (nested instanceof ProviderJsonBlock jsonBlock) {
        text.append(fence(jsonBlock.json())).append('\n');
      } else if (nested instanceof ProviderResourceBlock resourceBlock) {
        resources.add(resourceBlock);
      }
    }
    blocks.add(new ProviderTextBlock(text.toString().stripTrailing()));
    blocks.addAll(resources);
    return List.copyOf(blocks);
  }

  private static String describeDowngradedCall(ToolCallMessageContent call) {
    return "Tool call `"
        + call.toolName()
        + "` (call id `"
        + call.toolCallId()
        + "`) is not available in this context. Arguments:\n"
        + fence(call.argumentsJson());
  }

  /** 动态围栏：反引号数取 {@code max(3, 内容中最长反引号连续段 + 1)}，不带语言标识，因此逐字 payload 中的反引号与空白都不会被 误读。 */
  static String fence(String payload) {
    Objects.requireNonNull(payload, "payload");
    int longestRun = 0;
    int currentRun = 0;
    for (int i = 0; i < payload.length(); i++) {
      if (payload.charAt(i) == '`') {
        currentRun++;
        longestRun = Math.max(longestRun, currentRun);
      } else {
        currentRun = 0;
      }
    }
    String ticks = "`".repeat(Math.max(MIN_FENCE_LENGTH, longestRun + 1));
    return ticks + "\n" + payload + "\n" + ticks;
  }

  /** 一次 assistant 消息与其后续 TOOL 结果组成的 batch：native 结果先于降级 USER 结果输出。 */
  private static final class Batch {
    private final List<ProviderToolCallBlock> openNativeCalls = new ArrayList<>();
    private final List<ProviderToolResultBlock> nativeResults = new ArrayList<>();
    private final List<ProviderContentBlock> downgradedResults = new ArrayList<>();

    private void add(PendingResult pending) {
      switch (pending) {
        case PendingResult.Native nativeResult -> {
          openNativeCalls.removeIf(
              open -> open.toolCall().id().equals(nativeResult.block().toolCallId()));
          nativeResults.add(nativeResult.block());
        }
        case PendingResult.Downgraded downgraded -> downgradedResults.addAll(downgraded.blocks());
      }
    }

    private void flush(List<ProviderMessage> result) {
      for (ProviderToolResultBlock nativeResult : nativeResults) {
        result.add(new ProviderMessage(ProviderMessageRole.TOOL, List.of(nativeResult)));
      }
      List<ProviderContentBlock> synthetic = syntheticOrphanResults();
      if (!synthetic.isEmpty()) {
        for (ProviderContentBlock block : synthetic) {
          result.add(new ProviderMessage(ProviderMessageRole.TOOL, List.of(block)));
        }
      }
      if (!downgradedResults.isEmpty()) {
        result.add(new ProviderMessage(ProviderMessageRole.USER, List.copyOf(downgradedResults)));
      }
      openNativeCalls.clear();
      nativeResults.clear();
      downgradedResults.clear();
    }

    private List<ProviderContentBlock> syntheticOrphanResults() {
      List<ProviderContentBlock> synthetic = new ArrayList<>(openNativeCalls.size());
      for (ProviderToolCallBlock toolCall : openNativeCalls) {
        synthetic.add(
            new ProviderToolResultBlock(
                toolCall.toolCall().id(),
                toolCall.toolCall().name(),
                List.of(new ProviderTextBlock(ORPHAN_RESULT_TEXT)),
                true,
                null));
      }
      return synthetic;
    }
  }

  private sealed interface PendingResult {
    record Native(ProviderToolResultBlock block) implements PendingResult {}

    record Downgraded(List<ProviderContentBlock> blocks) implements PendingResult {}
  }

  private List<ProviderContentBlock> projectContents(List<AgentMessageContent> contents) {
    List<ProviderContentBlock> result = new ArrayList<>(contents.size());
    for (AgentMessageContent content : contents) {
      result.add(projectContent(content));
    }
    return List.copyOf(result);
  }

  private ProviderContentBlock projectContent(AgentMessageContent content) {
    if (content instanceof TextMessageContent value) {
      return new ProviderTextBlock(value.text());
    }
    // durable history 绝不携带 Image/Audio/Video 瞬时内容（AgentMessageJsonCodec 拒绝）；媒体只在
    // Provider attempt 物化（ProviderResourceMaterializer）时由 resource 块投影为 media 块。
    if (content instanceof ThinkingMessageContent value) {
      return new ProviderThinkingBlock(value.text());
    }
    if (content instanceof JsonMessageContent value) {
      return new ProviderJsonBlock(value.json());
    }
    if (content instanceof ToolCallMessageContent value) {
      return new ProviderToolCallBlock(
          new ProviderToolCall(value.toolCallId(), value.toolName(), value.argumentsJson()));
    }
    if (content instanceof ToolResultMessageContent value) {
      return new ProviderToolResultBlock(
          value.toolCallId(),
          value.toolName(),
          projectContents(value.contents()),
          value.error(),
          value.detailsJson());
    }
    if (content instanceof ResourceMessageContent value) {
      // durable blob 引用原样投影；Provider attempt 物化（platform ProviderResourceMaterializer）在每次
      // attempt 时按 storage_blob 事实替换为携带新鲜预签名 URL 的 media 块或确定性文本回退。
      return new ProviderResourceBlock(
          value.blobId(),
          value.name(),
          value.totalBytes(),
          value.totalLines(),
          value.preview() == null ? "" : value.preview());
    }
    throw new IllegalArgumentException("unsupported agent message content: " + content.getClass());
  }
}
