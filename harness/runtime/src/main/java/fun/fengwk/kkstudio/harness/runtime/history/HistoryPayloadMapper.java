package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Focused pure mapper from Provider / invocation facts to immutable history Entry payloads.
 *
 * <p>只做类型化映射，不做任何持久化决策：{@link ProviderResponse} 投影为 ASSISTANT {@link MessagePayload}（内容顺序为
 * thinking、text、tool calls，全部为空时回退为空 text，metadata 直接快照 usage/cost）；terminal Model 错误投影为 {@link
 * AssistantErrorPayload}（code 使用 {@code error.kind().name()}）；terminal {@link ToolInvocation} 投影为
 * TOOL {@link MessagePayload}（SUCCEEDED 把 Text/JSON/Resource {@link ToolContent} 映射为对应 {@link
 * AgentMessageContent} 并回退空 text，error 标志原样保留，Binary / 未知 content 显式 {@link
 * IllegalArgumentException} 失败而不是静默丢失；非成功使用 error message + {@link ToolInvocationErrorJsonCodec}
 * 详情且 error=true，metadata.status 精确映射 invocation terminal status）；history normalization 的 synthetic
 * 结果固定为 UNKNOWN / HISTORY_CUT / "No result provided"，不关联任何 ToolInvocation。
 */
public final class HistoryPayloadMapper {

  /** Synthetic history-cut ToolResult 的稳定错误内容。 */
  public static final String HISTORY_CUT_RESULT_TEXT = "No result provided";

  private static final String EMPTY_DETAILS_JSON = "{}";

  private final ToolInvocationErrorJsonCodec errorCodec = new ToolInvocationErrorJsonCodec();

  /** ASSISTANT MESSAGE payload：内容顺序为 thinking（非空）、text（非空）、tool calls；全部为空时回退为单个空 text。 */
  public MessagePayload assistantPayload(ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    List<AgentMessageContent> contents = new ArrayList<>();
    if (!response.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(response.thinking()));
    }
    if (!response.text().isEmpty()) {
      contents.add(new TextMessageContent(response.text()));
    }
    for (ProviderToolCall call : response.toolCalls()) {
      contents.add(new ToolCallMessageContent(call.id(), call.name(), call.argumentsJson()));
    }
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent(""));
    }
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(response.stopReason(), response.usage(), response.cost()),
        null);
  }

  /** terminal Model 错误的 ASSISTANT_ERROR payload：code 使用 {@code error.kind().name()}。 */
  public AssistantErrorPayload assistantErrorPayload(ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    return new AssistantErrorPayload(new AssistantError(error.kind().name(), error.message()));
  }

  /**
   * terminal ToolInvocation 的 TOOL MESSAGE payload：SUCCEEDED 映射 Text/JSON/Resource content 并回退空
   * text；FAILED / CANCELLED / UNKNOWN 使用 error message + codec 详情且 error=true。metadata 精确映射
   * invocation terminal status。
   */
  public MessagePayload toolResultPayload(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    if (!invocation.status().isTerminal()) {
      throw new IllegalArgumentException("tool result payload requires a terminal invocation");
    }
    ToolResult result = invocation.result();
    ToolResultMessageContent content;
    if (invocation.status() == ToolInvocationStatus.SUCCEEDED) {
      if (result == null) {
        throw new IllegalArgumentException("succeeded invocation requires a result");
      }
      content = succeededContent(invocation, result);
    } else {
      content = failedContent(invocation);
    }
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            invocation.assistantEntryId(),
            invocation.request().call().id(),
            invocation.ordinal(),
            statusOf(invocation),
            false,
            null);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  /**
   * history normalization 的 synthetic ToolResult payload：status UNKNOWN、synthetic=true、reason
   * HISTORY_CUT，稳定 "No result provided" 错误内容；不关联任何 ToolInvocation。
   */
  public MessagePayload syntheticHistoryCutToolResult(
      long assistantEntryId, int ordinal, ToolCallMessageContent call) {
    Objects.requireNonNull(call, "call");
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            call.toolCallId(),
            call.toolName(),
            List.of(new TextMessageContent(HISTORY_CUT_RESULT_TEXT)),
            true,
            EMPTY_DETAILS_JSON);
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            assistantEntryId,
            call.toolCallId(),
            ordinal,
            ToolResultStatus.UNKNOWN,
            true,
            ToolResultReason.HISTORY_CUT);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  private ToolResultMessageContent succeededContent(ToolInvocation invocation, ToolResult result) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (ToolContent toolContent : result.contents()) {
      if (toolContent instanceof TextToolContent text) {
        contents.add(new TextMessageContent(text.text()));
      } else if (toolContent instanceof JsonToolContent json) {
        contents.add(new JsonMessageContent(json.json()));
      } else if (toolContent instanceof ResourceToolContent resource) {
        contents.add(new ResourceMessageContent(resource.resource(), null));
      } else {
        // BinaryToolContent 不能进入 Session 语义消息；未知 content 也不得静默丢失字节——显式失败让调用方事务回滚。
        throw new IllegalArgumentException(
            "unsupported tool result content type " + toolContent.getClass().getSimpleName());
      }
    }
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent(""));
    }
    return new ToolResultMessageContent(
        invocation.request().call().id(),
        invocation.request().call().toolName(),
        contents,
        result.error(),
        result.detailsJson());
  }

  private ToolResultMessageContent failedContent(ToolInvocation invocation) {
    if (invocation.error() == null) {
      throw new IllegalArgumentException("non-succeeded invocation requires an error");
    }
    return new ToolResultMessageContent(
        invocation.request().call().id(),
        invocation.request().call().toolName(),
        List.of(new TextMessageContent(invocation.error().message())),
        true,
        errorCodec.encode(invocation.error()));
  }

  private static ToolResultStatus statusOf(ToolInvocation invocation) {
    return switch (invocation.status()) {
      case SUCCEEDED -> ToolResultStatus.SUCCEEDED;
      case FAILED -> ToolResultStatus.FAILED;
      case CANCELLED -> ToolResultStatus.CANCELLED;
      case UNKNOWN -> ToolResultStatus.UNKNOWN;
      default -> throw new IllegalArgumentException(
          "tool result payload requires a terminal invocation status");
    };
  }
}
