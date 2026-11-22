package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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

/**
 * OpenAI Chat 流式 tool call 片段的聚合。
 *
 * <p>{@code tool_calls[].index} 是 provider 的 wire 坐标，只在原生槽位确认 function 意图后才分配连续的 runtime ordinal；非
 * function 调用只保留原生事实，不参与 durable 语义。聚合后的调用在终态按 stop reason 校验完整性，LENGTH 时把残缺调用转为诊断而不是丢弃。
 */
final class OpenAiChatToolCallStream {

  private final ProviderStreamBridge bridge;
  private final OpenAiChatNativeAssistantMessage nativeMessage;

  /** 按首次确认 function 意图的顺序分配连续的 runtime ordinal；key 仍为原生 wire index。 */
  private final Map<Integer, ToolCallBuilder> builders = new LinkedHashMap<>();

  OpenAiChatToolCallStream(
      ProviderStreamBridge bridge, OpenAiChatNativeAssistantMessage nativeMessage) {
    this.bridge = bridge;
    this.nativeMessage = nativeMessage;
  }

  /** 合并一帧 {@code choices[0].delta.tool_calls}；每个片段都必须携带可转 int 的非负整数 index。 */
  void mergeDelta(ArrayNode toolCalls) {
    for (JsonNode toolCall : toolCalls) {
      JsonNode index = toolCall.get("index");
      if (index == null
          || !index.isIntegralNumber()
          || !index.canConvertToInt()
          || index.intValue() < 0) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "tool call index must be a non-negative integer");
      }
      mergeFragment(index.intValue(), toolCall);
    }
  }

  private void mergeFragment(int wireIndex, JsonNode fragment) {
    JsonNode nativeSlot = nativeMessage.toolCall(wireIndex);
    // type=function 可以先于 function 对象到达；无已知 function 意图的调用只保留原生事实。
    if (!carriesFunctionIntent(nativeSlot) || carriesNonFunctionType(fragment)) {
      return;
    }
    ToolCallBuilder builder = builders.get(wireIndex);
    if (builder == null) {
      confirmFunction(wireIndex, nativeSlot);
    } else {
      appendFragment(builder, fragment);
    }
  }

  private static boolean carriesFunctionIntent(JsonNode nativeSlot) {
    return nativeSlot != null && !carriesNonFunctionType(nativeSlot);
  }

  private static boolean carriesNonFunctionType(JsonNode slot) {
    return slot.path("type").isString() && !"function".equals(slot.path("type").stringValue());
  }

  /** 首次确认 function 意图：native 已合并本帧，因此用完整槽位一次性补齐此前 identity-only 片段；此前没有任何字段的调用不进入 durable 语义。 */
  private void confirmFunction(int wireIndex, JsonNode nativeSlot) {
    if (!"function".equals(nativeSlot.path("type").asString(""))
        && !nativeSlot.path("function").isObject()) {
      return;
    }
    ToolCallBuilder builder = new ToolCallBuilder(wireIndex, builders.size());
    builders.put(wireIndex, builder);
    JsonNode function = nativeSlot.path("function");
    String id = textualValue(nativeSlot.get("id"));
    String name = textualValue(function.get("name"));
    String arguments = textualValue(function.get("arguments"));
    builder.appendId(id);
    builder.appendName(name);
    builder.appendArguments(arguments);
    String projectedId = blankToNull(id);
    String projectedName = blankToNull(name);
    if (projectedId != null || projectedName != null || arguments != null) {
      bridge.emitEvent(
          new ProviderStreamEvent.ToolCallDelta(
              builder.ordinal(), projectedId, projectedName, arguments));
    }
  }

  private void appendFragment(ToolCallBuilder builder, JsonNode fragment) {
    String idDelta = null;
    if (fragment.has("id") && fragment.get("id").isString()) {
      idDelta = fragment.get("id").stringValue();
      builder.appendId(idDelta);
    }

    String nameDelta = null;
    String argumentsDelta = null;
    JsonNode function = fragment.get("function");
    if (function != null) {
      if (function.isNull() || !function.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "tool call function must be an object");
      }
      if (function.has("name") && function.get("name").isString()) {
        nameDelta = function.get("name").stringValue();
        builder.appendName(nameDelta);
      }
      if (function.has("arguments") && function.get("arguments").isString()) {
        argumentsDelta = function.get("arguments").stringValue();
        builder.appendArguments(argumentsDelta);
      }
    }

    String projectedId = idDelta == null || builder.id().isBlank() ? null : builder.id();
    String projectedName = nameDelta == null || builder.name().isBlank() ? null : builder.name();
    if (projectedId != null || projectedName != null || argumentsDelta != null) {
      bridge.emitEvent(
          new ProviderStreamEvent.ToolCallDelta(
              builder.ordinal(), projectedId, projectedName, argumentsDelta));
    }
  }

  /** 按终态解析 durable 工具调用：COMPLETE 要求身份与参数都完整（否则污染响应事实，拒绝）；LENGTH 把残缺调用转为诊断，保证调用者能看到截断原因。 */
  ToolCalls resolve(GenerationStopReason stopReason) {
    List<ProviderToolCall> calls = new ArrayList<>();
    List<ProviderToolCallDiagnostic> diagnostics = new ArrayList<>();
    for (ToolCallBuilder builder : builders.values()) {
      if (stopReason == GenerationStopReason.LENGTH) {
        resolveTruncated(builder, calls, diagnostics);
      } else {
        calls.add(requireComplete(builder));
      }
    }
    return new ToolCalls(calls, diagnostics);
  }

  private static void resolveTruncated(
      ToolCallBuilder builder,
      List<ProviderToolCall> calls,
      List<ProviderToolCallDiagnostic> diagnostics) {
    String id = builder.id();
    String name = builder.name();
    String arguments = builder.arguments();
    boolean idValid = !id.isBlank();
    boolean nameValid = !name.isBlank();
    if (idValid && nameValid && ProviderJsonHelper.tryParseJsonObject(arguments) != null) {
      calls.add(new ProviderToolCall(id, name, arguments));
      return;
    }
    diagnostics.add(
        new ProviderToolCallDiagnostic(
            builder.ordinal(),
            idValid ? id : null,
            nameValid ? name : null,
            arguments,
            "tool call truncated due to max tokens"));
  }

  private static ProviderToolCall requireComplete(ToolCallBuilder builder) {
    String id = builder.id();
    String name = builder.name();
    String arguments = builder.arguments();
    if (id.isBlank()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "tool call missing id");
    }
    if (name.isBlank()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "tool call missing name");
    }
    ProviderJsonHelper.requireToolArgumentsObject(name, arguments);
    return new ProviderToolCall(id, name, arguments);
  }

  /** 取工具调用的原生扩展字段：去掉 index、id、type 与语义已表达的函数 name/arguments，只保留 provider 独有的事实。 */
  ObjectNode nativeFields(ProviderToolCall call) {
    for (ToolCallBuilder builder : builders.values()) {
      if (!call.id().equals(builder.id())) {
        continue;
      }
      JsonNode nativeCall = nativeMessage.toolCall(builder.wireIndex());
      if (nativeCall == null || !nativeCall.isObject()) {
        return nativeMessage.emptyObject();
      }
      ObjectNode fields = (ObjectNode) nativeCall.deepCopy();
      fields.remove("index");
      fields.remove("id");
      fields.remove("type");
      JsonNode functionValue = fields.remove("function");
      if (functionValue != null && functionValue.isObject()) {
        ObjectNode function = (ObjectNode) functionValue.deepCopy();
        function.remove("name");
        function.remove("arguments");
        if (!function.isEmpty()) {
          fields.set("function", function);
        }
      }
      return fields;
    }
    return nativeMessage.emptyObject();
  }

  private static String textualValue(JsonNode node) {
    return node != null && node.isString() ? node.stringValue() : null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** 终态解析结果：可执行的调用与 LENGTH 截断诊断互斥出现。 */
  record ToolCalls(List<ProviderToolCall> calls, List<ProviderToolCallDiagnostic> diagnostics) {}

  private static final class ToolCallBuilder {
    private final int wireIndex;
    private final int ordinal;
    private final StringBuilder id = new StringBuilder();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    ToolCallBuilder(int wireIndex, int ordinal) {
      this.wireIndex = wireIndex;
      this.ordinal = ordinal;
    }

    int wireIndex() {
      return wireIndex;
    }

    int ordinal() {
      return ordinal;
    }

    void appendId(String value) {
      if (value != null) {
        id.append(value);
      }
    }

    void appendName(String value) {
      if (value != null) {
        name.append(value);
      }
    }

    void appendArguments(String value) {
      if (value != null) {
        arguments.append(value);
      }
    }

    String id() {
      return id.toString();
    }

    String name() {
      return name.toString();
    }

    String arguments() {
      return arguments.toString();
    }
  }
}
