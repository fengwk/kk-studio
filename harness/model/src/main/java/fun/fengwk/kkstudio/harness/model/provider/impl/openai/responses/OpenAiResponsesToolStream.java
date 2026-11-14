package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responses 流中的工具调用状态。
 *
 * <p>{@code ordinal} 是工具调用之间的连续序号（0..N-1），用于 {@link ProviderStreamEvent.ToolCallDelta}；它必须区别于
 * Responses 协议的 output_index——后者同时计入 reasoning / message 等非工具 item，直接透出会造成序号空洞。
 *
 * <p>工具的身份可由 item id 或 call_id 抵达，因此查找按「id 优先、call_id 次之、最后按两字段线性匹配」进行，与 replay 重建保持一致。
 */
final class OpenAiResponsesToolStream {

  private final ProviderStreamBridge bridge;

  private final Map<String, WireToolCall> toolsById = new LinkedHashMap<>();
  private int nextToolOrdinal = 0;

  OpenAiResponsesToolStream(ProviderStreamBridge bridge) {
    this.bridge = bridge;
  }

  /** 首次观察到的 function_call 分配下一个连续 ordinal；重复 added 复用既有 ordinal，但身份变化即拒绝。 */
  void recordAddedItem(JsonNode item) {
    String id = item.path("id").asString(null);
    if (id == null || id.isBlank()) {
      return;
    }
    String callId = item.path("call_id").asString(null);
    if (callId == null || callId.isBlank()) {
      callId = id;
    }
    String name = item.path("name").asString(null);
    WireToolCall previous = toolsById.get(id);
    if (previous != null) {
      if (!sameIdentity(previous, callId, name)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "duplicate output item has a different identity");
      }
      return;
    }
    int ordinal = nextToolOrdinal++;
    toolsById.put(id, new WireToolCall(ordinal, id, callId, name));
    bridge.emitEvent(new ProviderStreamEvent.ToolCallDelta(ordinal, callId, name, null));
  }

  void appendArgumentsDelta(JsonNode node) {
    WireToolCall tool = findKnownTool(node.path("item_id").asString(null));
    if (tool == null) {
      return;
    }
    String delta = node.path("delta").asString("");
    tool.argumentsBuffer.append(delta);
    tool.argumentsPresent = true;
    tool.argumentsTextual = true;
    if (!delta.isEmpty()) {
      bridge.emitEvent(new ProviderStreamEvent.ToolCallDelta(tool.ordinal, null, null, delta));
    }
  }

  void markArgumentsDone(JsonNode node) {
    WireToolCall tool = findKnownTool(node.path("item_id").asString(null));
    if (tool == null) {
      return;
    }
    String arguments = node.path("arguments").asString(null);
    if (arguments != null) {
      tool.argumentsBuffer.setLength(0);
      tool.argumentsBuffer.append(arguments);
      tool.argumentsPresent = true;
      tool.argumentsTextual = true;
    }
  }

  /**
   * 用权威 item 同步工具状态：未在流中登记过的调用（只出现在 {@code output_item.done} 或 terminal output）在此分配连续 ordinal；item
   * 未声明 {@code arguments} 时保留此前 delta 聚合的结果。
   */
  void syncFromItem(JsonNode item) {
    String itemId = item.path("id").asString(null);
    String callId = item.path("call_id").asString(null);
    WireToolCall tool = findToolCall(itemId, callId);
    if (tool == null) {
      int ordinal = nextToolOrdinal++;
      String key =
          (itemId != null && !itemId.isBlank())
              ? itemId
              : ((callId != null && !callId.isBlank()) ? callId : ("tool_" + ordinal));
      tool = new WireToolCall(ordinal, itemId, callId, null);
      toolsById.put(key, tool);
    }
    if (itemId != null && !itemId.isBlank()) {
      tool.id = itemId;
    }
    if (callId != null && !callId.isBlank()) {
      tool.callId = callId;
    }
    if (item.has("name")) {
      tool.name = item.path("name").asString(null);
    }
    if (!item.has("arguments")) {
      if (tool.argumentsBuffer.length() == 0) {
        tool.argumentsPresent = false;
      }
      return;
    }
    if (item.get("arguments") != null && !item.get("arguments").isNull()) {
      tool.argumentsPresent = true;
      tool.argumentsTextual = item.get("arguments").isString();
      if (tool.argumentsTextual) {
        tool.argumentsBuffer.setLength(0);
        tool.argumentsBuffer.append(item.get("arguments").asString(""));
      }
    } else {
      tool.argumentsPresent = false;
    }
  }

  /**
   * 按终态解析 durable 工具调用：COMPLETE 要求身份与参数完整，否则拒绝；LENGTH 把残缺调用转为诊断并标明截断原因。
   *
   * <p>诊断的 ordinal 使用流内迭代序号，与实时 {@link ProviderStreamEvent.ToolCallDelta} 的 ordinal 对齐。
   */
  ToolCalls resolve(GenerationStopReason stopReason) {
    List<ProviderToolCall> calls = new ArrayList<>();
    List<ProviderToolCallDiagnostic> diagnostics = new ArrayList<>();
    int ordinal = 0;
    for (WireToolCall tool : toolsById.values()) {
      String effectiveCallId =
          (tool.callId != null && !tool.callId.isBlank()) ? tool.callId : tool.id;
      boolean hasValidId = effectiveCallId != null && !effectiveCallId.isBlank();
      boolean hasValidName = tool.name != null && !tool.name.isBlank();
      boolean hasArgs = tool.argumentsPresent || tool.argumentsBuffer.length() > 0;
      String arguments = tool.argumentsBuffer.toString();
      JsonNode parsedArguments =
          (tool.argumentsTextual && hasArgs && !arguments.isBlank())
              ? ProviderJsonHelper.tryParseJsonObject(arguments)
              : null;

      if (stopReason == GenerationStopReason.COMPLETE) {
        calls.add(
            requireCompleteTool(
                effectiveCallId, tool, hasValidId, hasValidName, arguments, parsedArguments));
      } else {
        resolveTruncatedTool(
            tool,
            ordinal,
            effectiveCallId,
            arguments,
            hasValidId,
            hasValidName,
            hasArgs,
            parsedArguments,
            calls,
            diagnostics);
      }
      ordinal++;
    }
    return new ToolCalls(calls, diagnostics);
  }

  private static ProviderToolCall requireCompleteTool(
      String callId,
      WireToolCall tool,
      boolean hasValidId,
      boolean hasValidName,
      String arguments,
      JsonNode parsedArguments) {
    if (!hasValidId) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "tool call missing id");
    }
    if (!hasValidName) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "tool call missing name");
    }
    if (arguments.isEmpty()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "incomplete tool call missing arguments");
    }
    if (!tool.argumentsTextual) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "tool call arguments must be a JSON string");
    }
    if (parsedArguments == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "tool call arguments cannot be parsed as JSON object");
    }
    return new ProviderToolCall(callId, tool.name, arguments);
  }

  private static void resolveTruncatedTool(
      WireToolCall tool,
      int ordinal,
      String effectiveCallId,
      String arguments,
      boolean hasValidId,
      boolean hasValidName,
      boolean hasArgs,
      JsonNode parsedArguments,
      List<ProviderToolCall> calls,
      List<ProviderToolCallDiagnostic> diagnostics) {
    boolean valid =
        hasValidId && hasValidName && hasArgs && tool.argumentsTextual && parsedArguments != null;
    if (valid) {
      calls.add(new ProviderToolCall(effectiveCallId, tool.name, arguments));
      return;
    }
    String diagnosticId = hasValidId ? effectiveCallId : null;
    String diagnosticName = hasValidName ? tool.name : null;
    String partialArguments = (tool.argumentsTextual && !arguments.isEmpty()) ? arguments : null;
    diagnostics.add(
        new ProviderToolCallDiagnostic(
            ordinal,
            diagnosticId,
            diagnosticName,
            partialArguments,
            truncationReason(hasValidId, hasValidName, hasArgs, arguments)));
  }

  private static String truncationReason(
      boolean hasValidId, boolean hasValidName, boolean hasArgs, String arguments) {
    if (!hasValidId || !hasValidName) {
      return "tool call truncated before identity received";
    }
    if (!hasArgs || arguments.isEmpty()) {
      return "tool call truncated before arguments received";
    }
    return "tool call arguments truncated or malformed";
  }

  /** 清空流内草稿：终态显式给出 output 时，工具状态完全由权威 item 重建。 */
  void clear() {
    toolsById.clear();
    nextToolOrdinal = 0;
  }

  /** 冻结为 replay 重建所需的不可变快照。 */
  List<OpenAiResponsesReplayBuilder.ReplayTool> snapshot() {
    List<OpenAiResponsesReplayBuilder.ReplayTool> tools = new ArrayList<>(toolsById.size());
    for (Map.Entry<String, WireToolCall> entry : toolsById.entrySet()) {
      WireToolCall tool = entry.getValue();
      tools.add(
          new OpenAiResponsesReplayBuilder.ReplayTool(
              entry.getKey(),
              tool.id,
              tool.callId,
              tool.name,
              tool.argumentsBuffer.toString(),
              tool.argumentsTextual));
    }
    return tools;
  }

  private WireToolCall findKnownTool(String itemId) {
    if (itemId == null || itemId.isBlank()) {
      return null;
    }
    return toolsById.get(itemId);
  }

  private WireToolCall findToolCall(String itemId, String callId) {
    WireToolCall byId = findKnownTool(itemId);
    if (byId != null) {
      return byId;
    }
    WireToolCall byCallId = findKnownTool(callId);
    if (byCallId != null) {
      return byCallId;
    }
    for (WireToolCall tool : toolsById.values()) {
      if (matchesEitherIdentity(tool, itemId, callId)) {
        return tool;
      }
    }
    return null;
  }

  private static boolean matchesEitherIdentity(WireToolCall tool, String itemId, String callId) {
    return (isPresent(itemId) && (itemId.equals(tool.id) || itemId.equals(tool.callId)))
        || (isPresent(callId) && (callId.equals(tool.callId) || callId.equals(tool.id)));
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean sameIdentity(WireToolCall tool, String callId, String name) {
    return Objects.equals(tool.callId, callId) && Objects.equals(tool.name, name);
  }

  /** 终态解析结果：可执行的调用与 LENGTH 截断诊断互斥出现。 */
  record ToolCalls(List<ProviderToolCall> calls, List<ProviderToolCallDiagnostic> diagnostics) {}

  private static final class WireToolCall {
    private final int ordinal;
    private String id;
    private String callId;
    private String name;
    private final StringBuilder argumentsBuffer = new StringBuilder();
    private boolean argumentsPresent = false;
    private boolean argumentsTextual = true;

    WireToolCall(int ordinal, String id, String callId, String name) {
      this.ordinal = ordinal;
      this.id = id;
      this.callId = callId;
      this.name = name;
    }
  }
}
