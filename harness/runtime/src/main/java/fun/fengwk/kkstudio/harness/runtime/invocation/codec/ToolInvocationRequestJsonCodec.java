package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/** 一个冻结 Tool invocation request 的严格、确定性 JSON codec。 */
public final class ToolInvocationRequestJsonCodec {

  private static final String CONTEXT = "toolInvocationRequest";
  private static final ToolBindingJsonCodec BINDING_CODEC = new ToolBindingJsonCodec();

  public String encode(ToolInvocationRequest request) {
    return InvocationJsonSupport.write(encodeNode(request), CONTEXT);
  }

  public ObjectNode encodeNode(ToolInvocationRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.set("call", encodeCall(request.call()));
    if (request.binding() == null) {
      node.putNull("binding");
    } else {
      node.set("binding", BINDING_CODEC.encodeNode(request.binding()));
    }
    return node;
  }

  public ToolInvocationRequest decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolInvocationRequest decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(node, CONTEXT, "call", "binding");
    ToolCall call = decodeCall(InvocationJsonSupport.required(node, "call", CONTEXT));
    JsonNode bindingNode = node.get("binding");
    ToolBinding binding =
        bindingNode == null || bindingNode.isNull() ? null : BINDING_CODEC.decodeNode(bindingNode);
    return new ToolInvocationRequest(call, binding);
  }

  private static ObjectNode encodeCall(ToolCall call) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("id", call.id());
    node.put("toolName", call.toolName());
    node.put(
        "argumentsJson",
        InvocationJsonSupport.requireJsonObject(call.argumentsJson(), "toolCall.argumentsJson"));
    return node;
  }

  private static ToolCall decodeCall(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, "toolCall");
    InvocationJsonSupport.requireFields(node, "toolCall", "id", "toolName", "argumentsJson");
    return new ToolCall(
        InvocationJsonSupport.text(node, "id", "toolCall"),
        InvocationJsonSupport.text(node, "toolName", "toolCall"),
        InvocationJsonSupport.jsonObjectText(node, "argumentsJson", "toolCall"));
  }
}
