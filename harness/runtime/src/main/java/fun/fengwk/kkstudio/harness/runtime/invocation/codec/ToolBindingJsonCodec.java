package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.Objects;

/** Strict deterministic JSON codec for one durable Tool binding. */
public final class ToolBindingJsonCodec {

  private static final String CONTEXT = "toolBinding";
  private static final ToolDescriptorJsonCodec DESCRIPTOR_CODEC = new ToolDescriptorJsonCodec();

  public String encode(ToolBinding binding) {
    return InvocationJsonSupport.write(encodeNode(binding), CONTEXT);
  }

  public ObjectNode encodeNode(ToolBinding binding) {
    Objects.requireNonNull(binding, "binding");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.set("descriptor", DESCRIPTOR_CODEC.encodeNode(binding.descriptor()));
    node.put("type", binding.type().name());
    InvocationJsonSupport.putNullable(node, "environmentId", binding.environmentId());
    return node;
  }

  public ToolBinding decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolBinding decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(node, CONTEXT, "descriptor", "type", "environmentId");
    ToolDescriptor descriptor =
        DESCRIPTOR_CODEC.decodeNode(InvocationJsonSupport.required(node, "descriptor", CONTEXT));
    ToolType type = InvocationJsonSupport.requiredEnum(node, "type", ToolType.class, CONTEXT);
    EnvironmentId environmentId =
        InvocationJsonSupport.nullableEnvironmentId(node, "environmentId", CONTEXT);
    return new ToolBinding(descriptor, type, environmentId);
  }
}
