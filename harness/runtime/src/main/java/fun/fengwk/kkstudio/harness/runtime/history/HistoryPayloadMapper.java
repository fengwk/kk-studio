package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
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
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 从 Provider / invocation 事实到 immutable history Entry payload 的聚焦纯 mapper。
 *
 * <p>只做类型化映射，不做任何持久化决策：{@link ProviderResponse} 投影为 ASSISTANT {@link MessagePayload}（内容顺序为
 * thinking、text、tool calls，全部为空时回退为空 text，metadata 直接快照 usage/cost）；terminal Model 错误投影为 {@link
 * AssistantErrorPayload}（code 使用 {@code error.kind().name()}）；terminal {@link ToolInvocation} 投影为
 * TOOL {@link MessagePayload}（SUCCEEDED 把 Text/JSON/Resource {@link ResultContent}（含 Resource
 * preview）映射为对应 {@link AgentMessageContent} 并回退空 text，error 标志原样保留，Binary / 未知 content 显式 {@link
 * IllegalArgumentException} 失败而不是静默丢失；非成功使用 error message + {@link ToolInvocationErrorJsonCodec}
 * 详情且 error=true，metadata.status 精确映射 invocation terminal status）；history normalization 的 synthetic
 * 结果固定为 UNKNOWN / HISTORY_CUT / "No result provided"，不关联任何 ToolInvocation。
 */
public final class HistoryPayloadMapper {

  /** Synthetic history-cut ToolResult 的稳定错误内容。 */
  public static final String HISTORY_CUT_RESULT_TEXT = "No result provided";

  /** 无 frozen binding（unknown tool / 输出截断）槽位的 durable renderer fallback。 */
  public static final String UNBOUND_RENDERER_KEY = "tool";

  private static final String EMPTY_DETAILS_JSON = "{}";

  private final ToolInvocationErrorJsonCodec errorCodec = new ToolInvocationErrorJsonCodec();

  /** ASSISTANT MESSAGE payload：ToolCall 的 rendererKey 来自本次 ModelInvocation 冻结的 Tool binding。 */
  public MessagePayload assistantPayload(ProviderResponse response, List<ToolBinding> bindings) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(bindings, "bindings");
    List<AgentMessageContent> contents = new ArrayList<>();
    if (!response.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(response.thinking()));
    }
    if (!response.text().isEmpty()) {
      contents.add(new TextMessageContent(response.text()));
    }
    for (ProviderToolCall call : response.toolCalls()) {
      contents.add(
          new ToolCallMessageContent(
              call.id(), call.name(), rendererKey(call.name(), bindings), call.argumentsJson()));
    }
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent(""));
    }
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(response.stopReason(), response.usage(), response.cost()),
        null);
  }

  /** 非 Model attempt 的 ASSISTANT_ERROR payload，例如 planning / stop barrier。 */
  public AssistantErrorPayload assistantErrorPayload(ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    return new AssistantErrorPayload(
        new AssistantError(error.kind().name(), error.message()), null);
  }

  /** terminal Model 错误的 provider-transparent ASSISTANT_ERROR payload：保留已 durable 的完整 partial。 */
  public AssistantErrorPayload assistantErrorPayload(
      ModelInvocationError error, ModelAttemptSnapshot attempt) {
    Objects.requireNonNull(error, "error");
    return new AssistantErrorPayload(
        new AssistantError(error.kind().name(), error.message()), attempt);
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
            invocation.call().id(),
            invocation.callIndex(),
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
            assistantEntryId,
            call.toolCallId(),
            callIndex,
            ToolResultStatus.UNKNOWN,
            true,
            ToolResultReason.HISTORY_CUT);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  /**
   * 用调用方物化好的 contents 构建 SUCCEEDED ToolResult payload（contents 为空时回退为空文本）。
   *
   * <p>物化路径（注入 {@link ToolResultHistoryMaterializer}）由物化端口完成 Resource 内容的 blob 外部化；本方法只做
   * 语义消息组装，不再接受 ResourceResultContent。
   */
  public MessagePayload toolResultPayload(
      ToolInvocation invocation, List<AgentMessageContent> contents) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(contents, "contents");
    if (!invocation.status().isTerminal()) {
      throw new IllegalArgumentException("tool result payload requires a terminal invocation");
    }
    if (invocation.status() != ToolInvocationStatus.SUCCEEDED) {
      throw new IllegalArgumentException(
          "materialized contents are only valid for succeeded tool results");
    }
    List<AgentMessageContent> effective = List.copyOf(contents);
    if (effective.isEmpty()) {
      effective = List.of(new TextMessageContent(""));
    }
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            invocation.call().id(),
            invocation.call().toolName(),
            invocation.binding().descriptor().rendererKey(),
            effective,
            invocation.result().error(),
            invocation.result().detailsJson());
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)),
        null,
        new ToolResultMetadata(
            invocation.assistantEntryId(),
            invocation.call().id(),
            invocation.callIndex(),
            statusOf(invocation),
            false,
            null));
  }

  private ToolResultMessageContent succeededContent(ToolInvocation invocation, ToolResult result) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (ResultContent toolContent : result.contents()) {
      if (toolContent instanceof TextResultContent text) {
        contents.add(new TextMessageContent(text.text()));
      } else if (toolContent instanceof JsonResultContent json) {
        contents.add(new JsonMessageContent(json.json()));
      } else if (toolContent instanceof ResourceResultContent) {
        // Resource 引用无法在没有 ToolResultHistoryMaterializer 的情况下表示为 durable blob 内容：
        // fail-closed（与 BinaryResultContent 一致），绝不让瞬时 URI / ResourceStore 引用进入持久化 message。
        throw new IllegalArgumentException(
            "resource tool result content requires a ToolResultHistoryMaterializer");
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
        errorCodec.encode(invocation.error()));
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

  /** ToolCall 的 rendererKey：优先 frozen binding；unknown tool 槽位 fallback 固定为 {@code tool}。 */
  private static String rendererKey(String toolName, List<ToolBinding> bindings) {
    for (ToolBinding binding : bindings) {
      if (binding.descriptor().name().equals(toolName)) {
        return binding.descriptor().rendererKey();
      }
    }
    return UNBOUND_RENDERER_KEY;
  }
}
