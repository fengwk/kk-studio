package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 从 terminal ToolInvocation / history normalization 事实到 immutable history Entry payload 的聚焦纯
 * mapper。
 *
 * <p>只做类型化映射，不做任何持久化决策：terminal {@link ToolInvocation} 投影为 TOOL {@link MessagePayload} （SUCCEEDED 把
 * Text/JSON/Resource {@link ResultContent}（含 Resource preview）映射为对应 {@link AgentMessageContent}
 * 并回退空 text，error 标志原样保留，Binary / 未知 content 显式 {@link IllegalArgumentException} 失败而不是静默丢失；非成功使用
 * error message + {@link ToolInvocationError} JSON 详情且 error=true，metadata.status 精确映射 invocation
 * terminal status）；history normalization 的 synthetic 结果固定为 UNKNOWN / HISTORY_CUT / "No result
 * provided"，不关联任何 ToolInvocation。
 */
public final class HistoryPayloadMapper {

  /** Synthetic history-cut ToolResult 的稳定错误内容。 */
  public static final String HISTORY_CUT_RESULT_TEXT = "No result provided";

  /** 无 frozen binding（unknown tool / 输出截断）槽位的 durable renderer fallback。 */
  public static final String UNBOUND_RENDERER_KEY = "tool";

  private static final String EMPTY_DETAILS_JSON = "{}";

  /**
   * terminal ToolInvocation 的 TOOL MESSAGE payload：SUCCEEDED 映射稳定的 Text/JSON/Resource 内容（Resource
   * 必须是 Session 已持有的 {@code kkstudio} 引用）；空结果回退空 text。FAILED / CANCELLED / UNKNOWN 使用 error message
   * + codec 详情且 error=true。metadata 精确映射 invocation terminal status。
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
            invocation.id(),
            invocation.assistantEntryId(),
            invocation.call().id(),
            invocation.callIndex(),
            statusOf(invocation),
            false,
            null,
            invocation.inputReceipt(),
            invocation.binding());
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  /**
   * history normalization 的 synthetic ToolResult payload：status UNKNOWN、synthetic=true、reason
   * HISTORY_CUT，稳定 "No result provided" 错误内容；不关联任何 ToolInvocation。
   */
  public MessagePayload syntheticHistoryCutToolResult(
      UUID assistantEntryId, int callIndex, ToolCallMessageContent call) {
    Objects.requireNonNull(call, "call");
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            call.toolCallId(),
            call.toolName(),
            call.rendererKey(),
            List.of(new TextMessageContent(HISTORY_CUT_RESULT_TEXT)),
            true,
            EMPTY_DETAILS_JSON);
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            null,
            assistantEntryId,
            call.toolCallId(),
            callIndex,
            ToolResultStatus.UNKNOWN,
            true,
            ToolResultReason.HISTORY_CUT,
            null,
            null);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  private ToolResultMessageContent succeededContent(ToolInvocation invocation, ToolResult result) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (ResultContent toolContent : result.contents()) {
      if (toolContent instanceof TextResultContent text) {
        contents.add(new TextMessageContent(text.text()));
      } else if (toolContent instanceof JsonResultContent json) {
        contents.add(new JsonMessageContent(json.json()));
      } else if (toolContent instanceof ResourceResultContent resource) {
        contents.add(stableResourceContent(resource));
      } else {
        // BinaryResultContent 不能进入 Session 语义消息；未知 content 也不得静默丢失字节——显式失败让调用方事务回滚。
        throw new IllegalArgumentException(
            "unsupported tool result content type " + toolContent.getClass().getSimpleName());
      }
    }
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent(""));
    }
    return new ToolResultMessageContent(
        invocation.call().id(),
        invocation.call().toolName(),
        invocation.binding().descriptor().rendererKey(),
        contents,
        result.error(),
        result.detailsJson());
  }

  /**
   * 稳定 Resource 直接映射为 durable 消息内容：文本工件保留字节/行数事实，普通媒体按平台默认图片档位冻结。此处绝不再消费 upload 或执行 metadata-only
   * 降级。
   */
  private static ResourceMessageContent stableResourceContent(ResourceResultContent content) {
    var resource = content.resource();
    if (resource.sessionBlobId() == null) {
      throw new IllegalArgumentException(
          "succeeded tool resource must be a stable session resource");
    }
    if (resource.name() == null || resource.name().isBlank()) {
      throw new IllegalArgumentException("succeeded tool resource must declare a filename");
    }
    if (content.textMetadata() != null) {
      if (resource.size() != null && content.textMetadata().totalBytes() != resource.size()) {
        throw new IllegalArgumentException(
            "succeeded tool text resource size must match its metadata");
      }
      return ResourceMessageContent.externalizedText(
          resource.sessionBlobId(),
          resource.name(),
          content.textMetadata().totalBytes(),
          content.textMetadata().totalLines(),
          content.preview());
    }
    return ResourceMessageContent.media(
        resource.sessionBlobId(),
        resource.name(),
        content.preview(),
        defaultImageTier(resource.mediaType()));
  }

  /** 工具结果图片没有用户选择：冻结平台默认档位（720P）；非图片媒体不带档位。 */
  private static ImageInputTier defaultImageTier(String mediaType) {
    return mediaType != null && mediaType.startsWith("image/") ? ImageInputTier.P720 : null;
  }

  private ToolResultMessageContent failedContent(ToolInvocation invocation) {
    if (invocation.error() == null) {
      throw new IllegalArgumentException("non-succeeded invocation requires an error");
    }
    return new ToolResultMessageContent(
        invocation.call().id(),
        invocation.call().toolName(),
        rendererKey(invocation),
        List.of(new TextMessageContent(invocation.error().message())),
        true,
        StrictJson.standard().write(invocation.error(), "toolInvocationError"));
  }

  /** FAILED 槽位的 binding 可能为空（unknown tool）：durable renderer fallback 固定为 {@code tool}。 */
  private static String rendererKey(ToolInvocation invocation) {
    ToolBinding binding = invocation.binding();
    return binding == null ? UNBOUND_RENDERER_KEY : binding.descriptor().rendererKey();
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
