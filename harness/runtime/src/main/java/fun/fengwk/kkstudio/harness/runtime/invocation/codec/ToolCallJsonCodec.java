package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/** 一个冻结 ToolCall 的严格、确定性 JSON codec（PostgreSQL 持久化所需的最小 tool call codec）。 */
public final class ToolCallJsonCodec {

  private static final String CONTEXT = "toolCall";

  public String encode(ToolCall call) {
    return InvocationJsonSupport.write(encodeNode(call), CONTEXT);
  }

  public ObjectNode encodeNode(ToolCall call) {
    Objects.requireNonNull(call, "call");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("id", call.id());
    node.put("toolName", call.toolName());
    node.put(
        "argumentsJson",
        InvocationJsonSupport.requireJsonObject(call.argumentsJson(), "toolCall.argumentsJson"));
    return node;
  }

  public ToolCall decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolCall decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(node, CONTEXT, "id", "toolName", "argumentsJson");
    return new ToolCall(
        InvocationJsonSupport.text(node, "id", CONTEXT),
        InvocationJsonSupport.text(node, "toolName", CONTEXT),
        InvocationJsonSupport.jsonObjectText(node, "argumentsJson", CONTEXT));
  }
}
