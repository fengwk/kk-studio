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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * 将语义 Context 投影为与 Provider SDK 无关的 ProviderMessage。
 *
 * <p>历史工具调用的 native 资格按次判定：调用名必须在当前请求的工具列表中，且该调用冻结时的 Environment 名必须与当前同名工具的 Environment
 * 名一致。任一条件不满足（工具未绑定、Environment 已切换、未知工具）即降级：调用不再出现在 ASSISTANT wire 消息中，与其 后续 ToolResult 配对，并在本
 * batch 的 native TOOL 结果之后合并为单条 USER 自然语言上下文。降级输出绝不暴露 toolCallId、绝不使用 “Tool call / Tool result”
 * 伪协议，也不产生额外的 TOOL 结果。降级只发生在投影结果中，durable Entry 与 callIndex 永不被改写。
 *
 * <p>同一 assistant 之后的 native TOOL 结果先于该 USER 上下文输出，以保证 provider 要求的 tool-call adjacency；组内保持相对顺序。被
 * 降级改写的 assistant 消息不再携带 {@link ProviderReplayState}（native payload 已与投影内容不一致），未被改写的消息保留。
 *
 * <p>USER 上下文中每一项的动作来自调用冻结时的 Tool 语义 action（{@link
 * fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent#historyAction()}）；缺失 action
 * 的调用使用确定性中性回退 {@code external operation} 加逐字 arguments 围栏，因此投影永不猜测第三方工具的语义，也永不需要重新调用 Tool 代码。
 *
 * <p>对当前连续 Tool chain 中未配对的 native ToolCall，在角色切换或 Context 结束前合成 error ToolResult：{@code No result
 * provided}；未被任何结果配对的降级调用在同一 USER 上下文中以 {@code No result provided} 表达，不合成 TOOL 结果。Synthetic result
 * 只存在于本次 Provider request，不写 Session Entry。
 */
public final class ProviderMessageProjector {

  private static final String ORPHAN_RESULT_TEXT = "No result provided";

  /** 降级 USER 上下文的唯一前缀，用于与真实用户输入明确区分。 */
  private static final String CONTEXT_HEADER = "Previous context:";

  /** 无 Tool 语义映射时的确定性中性回退动作：不暴露 toolName，逐字保留全部 arguments。 */
  private static final String FALLBACK_ACTION = "external operation";

  private static final String SUCCESS_MARKER = ":";

  private static final String FAILURE_MARKER = " failed:";

  /** 动态围栏的最小反引号数；内容中的任意反引号连续段都会被安全包含。 */
  private static final int MIN_FENCE_LENGTH = 3;

  /** 当前请求中单个工具贡献的 native 资格事实：模型可见名与其绑定的 Environment 名（null 表示不绑定 Environment）。 */
  public record NativeTool(String name, String environmentName) {

    public NativeTool {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (environmentName != null && environmentName.isBlank()) {
        throw new IllegalArgumentException("environmentName must be null or non-blank");
      }
    }
  }

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

  private final List<NativeTool> nativeTools;

  /**
   * @param nativeTools 当前请求实际绑定的工具及其冻结 Environment 名；历史调用按名字与 Environment 逐次判定 native
   */
  public ProviderMessageProjector(List<NativeTool> nativeTools) {
    this.nativeTools = List.copyOf(Objects.requireNonNull(nativeTools, "nativeTools"));
  }

  /** 只按工具名判定 native 资格的便捷构造：适用于不绑定 Environment 的工具（静态无环境工具与 wire 夹具）。 */
  public static ProviderMessageProjector byNames(Collection<String> nativeToolNames) {
    Objects.requireNonNull(nativeToolNames, "nativeToolNames");
    return new ProviderMessageProjector(
        nativeToolNames.stream().map(name -> new NativeTool(name, null)).toList());
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
        batch.addResult(projectToolResult((ToolResultMessageContent) message.contents().get(0)));
        continue;
      }
      batch.flush(result);
      if (message.role() == AgentMessageRole.ASSISTANT) {
        projectAssistant(message, source.replayState(), batch, result);
        continue;
      }
      result.add(
          new ProviderMessage(ProviderMessageRole.USER, projectContents(message.contents())));
    }
    batch.flush(result);
    return List.copyOf(result);
  }

  /**
   * 一条 assistant 消息的投影：native 调用保留为 wire 调用块并登记待配对，降级调用从消息中移除并登记进 batch 的 USER 上下文；全部内容
   * 都是降级调用时整条消息不再输出（其语义已完整进入 USER 上下文）。
   */
  private void projectAssistant(
      AgentMessage message,
      ProviderReplayState replayState,
      Batch batch,
      List<ProviderMessage> result) {
    List<ProviderContentBlock> contents = new ArrayList<>(message.contents().size());
    boolean downgraded = false;
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof ToolCallMessageContent call) {
        if (isNative(call)) {
          ProviderToolCallBlock block = toolCallBlock(call);
          batch.openNativeCalls.add(block);
          contents.add(block);
        } else {
          batch.downgradeCall(call);
          downgraded = true;
        }
        continue;
      }
      contents.add(projectContent(content));
    }
    if (contents.isEmpty()) {
      return;
    }
    result.add(
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, contents, downgraded ? null : replayState));
  }

  private ProviderToolResultBlock projectToolResult(ToolResultMessageContent content) {
    return new ProviderToolResultBlock(
        content.toolCallId(),
        content.toolName(),
        projectContents(content.contents()),
        content.error(),
        content.detailsJson());
  }

  /** 当前请求的绑定中是否存在同名、且 Environment 名与调用冻结值一致的工具。 */
  private boolean isNative(ToolCallMessageContent call) {
    for (NativeTool nativeTool : nativeTools) {
      if (nativeTool.name().equals(call.toolName())) {
        return Objects.equals(nativeTool.environmentName(), call.environmentName());
      }
    }
    return false;
  }

  private boolean isNativeToolName(String toolName) {
    for (NativeTool nativeTool : nativeTools) {
      if (nativeTool.name().equals(toolName)) {
        return true;
      }
    }
    return false;
  }

  private static ProviderToolCallBlock toolCallBlock(ToolCallMessageContent call) {
    return new ProviderToolCallBlock(
        new ProviderToolCall(call.toolCallId(), call.toolName(), call.argumentsJson()));
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
      return toolCallBlock(value);
    }
    if (content instanceof ToolResultMessageContent value) {
      return projectToolResult(value);
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

  /** 一次 assistant 消息与其后续 TOOL 结果组成的 batch：native 结果先于降级 USER 上下文输出。 */
  private final class Batch {
    private final List<ProviderToolCallBlock> openNativeCalls = new ArrayList<>();
    private final List<ProviderToolResultBlock> nativeResults = new ArrayList<>();
    private final List<DowngradedCall> downgradedCalls = new ArrayList<>();
    private final Deque<ProviderToolResultBlock> downgradedResults = new ArrayDeque<>();

    private void downgradeCall(ToolCallMessageContent call) {
      boolean mapped = call.historyAction() != null;
      downgradedCalls.add(
          new DowngradedCall(
              call.toolCallId(),
              mapped ? call.historyAction() : FALLBACK_ACTION,
              mapped ? null : fence(call.argumentsJson())));
    }

    private void addResult(ProviderToolResultBlock resultBlock) {
      if (isNativeResult(resultBlock)) {
        openNativeCalls.removeIf(open -> open.toolCall().id().equals(resultBlock.toolCallId()));
        nativeResults.add(resultBlock);
        return;
      }
      downgradedResults.add(resultBlock);
    }

    /**
     * 结果资格沿用本 batch 已判定的调用：其调用被降级即为降级结果；调用缺失（如压缩截断）时按工具名兜底。没有任何降级调用与之配对的降级结果不会 进入 USER
     * 上下文——它已失去本次请求内的调用身份与动作语义。
     */
    private boolean isNativeResult(ProviderToolResultBlock resultBlock) {
      for (DowngradedCall call : downgradedCalls) {
        if (call.toolCallId().equals(resultBlock.toolCallId())) {
          return false;
        }
      }
      return isNativeToolName(resultBlock.toolName());
    }

    private void flush(List<ProviderMessage> result) {
      for (ProviderToolResultBlock nativeResult : nativeResults) {
        result.add(new ProviderMessage(ProviderMessageRole.TOOL, List.of(nativeResult)));
      }
      for (ProviderContentBlock block : syntheticOrphanResults()) {
        result.add(new ProviderMessage(ProviderMessageRole.TOOL, List.of(block)));
      }
      if (!downgradedCalls.isEmpty()) {
        result.add(buildPreviousContext());
      }
      openNativeCalls.clear();
      nativeResults.clear();
      downgradedCalls.clear();
      downgradedResults.clear();
    }

    /**
     * 全部降级调用与其结果合并为单条 USER 上下文：一个 {@code Previous context:} 前缀，随后每个调用一项，形如 {@code <action>:} （失败时
     * {@code <action> failed:}）后紧跟该结果的可读内容：
     *
     * <ul>
     *   <li>有 Tool 语义映射：动作来自 Tool 拥有者冻结的 action；
     *   <li>无 Tool 语义映射：动作恒为该调用的中性回退 {@code external operation}，后紧跟逐字 arguments 围栏（不臆测省略项）；
     *   <li>无对应结果（orphan）：动作行之后是本调用的 {@code No result provided}。
     * </ul>
     *
     * 无法表示为文本的 durable resource 块按序追加在文本之后。
     */
    private ProviderMessage buildPreviousContext() {
      StringBuilder text = new StringBuilder(CONTEXT_HEADER);
      List<ProviderContentBlock> resources = new ArrayList<>();
      for (DowngradedCall call : downgradedCalls) {
        ProviderToolResultBlock result = takeResult(call.toolCallId());
        boolean failed = result != null && result.error();
        text.append('\n').append(call.action()).append(failed ? FAILURE_MARKER : SUCCESS_MARKER);
        if (call.argumentsFence() != null) {
          text.append('\n').append(call.argumentsFence());
        }
        if (result == null) {
          text.append('\n').append(ORPHAN_RESULT_TEXT);
          continue;
        }
        appendResultContents(text, result, resources);
      }
      List<ProviderContentBlock> blocks = new ArrayList<>(resources.size() + 1);
      blocks.add(new ProviderTextBlock(text.toString()));
      blocks.addAll(resources);
      return new ProviderMessage(ProviderMessageRole.USER, List.copyOf(blocks));
    }

    /** 按 toolCallId 取出第一个尚未消费的降级结果；无匹配返回 null（orphan 调用）。 */
    private ProviderToolResultBlock takeResult(String toolCallId) {
      Iterator<ProviderToolResultBlock> iterator = downgradedResults.iterator();
      while (iterator.hasNext()) {
        ProviderToolResultBlock candidate = iterator.next();
        if (candidate.toolCallId().equals(toolCallId)) {
          iterator.remove();
          return candidate;
        }
      }
      return null;
    }

    /** Text/JSON 结果逐字围栏化；durable resource 块保留为显式 USER 块，而不是丢弃内容。 */
    private void appendResultContents(
        StringBuilder text, ProviderToolResultBlock result, List<ProviderContentBlock> resources) {
      for (ProviderContentBlock nested : result.contents()) {
        if (nested instanceof ProviderTextBlock textBlock) {
          text.append('\n').append(fence(textBlock.text()));
        } else if (nested instanceof ProviderJsonBlock jsonBlock) {
          text.append('\n').append(fence(jsonBlock.json()));
        } else if (nested instanceof ProviderResourceBlock resourceBlock) {
          resources.add(resourceBlock);
        }
      }
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

  /**
   * 降级调用：durable 顺序下与结果按 toolCallId 配对。
   *
   * @param action 该调用的动作行文本：Tool 拥有的语义 action，或中性回退动作
   * @param argumentsFence 中性回退时的逐字 arguments 围栏；有语义 action 时为 null
   */
  private record DowngradedCall(String toolCallId, String action, String argumentsFence) {}
}
