package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 一个 durable Tool binding 的严格、确定性 JSON codec。 */
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
    InvocationJsonSupport.putNullable(node, "environmentName", binding.environmentName());
    if (binding.plugin() == null) {
      node.putNull("plugin");
    } else {
      node.set("plugin", encodePlugin(binding.plugin()));
    }
    return node;
  }

  public ToolBinding decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolBinding decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(
        node, CONTEXT, "descriptor", "type", "environmentName", "plugin");
    ToolDescriptor descriptor =
        DESCRIPTOR_CODEC.decodeNode(InvocationJsonSupport.required(node, "descriptor", CONTEXT));
    ToolType type = InvocationJsonSupport.requiredEnum(node, "type", ToolType.class, CONTEXT);
    EnvironmentName environmentName =
        InvocationJsonSupport.nullableEnvironmentName(node, "environmentName", CONTEXT);
    PluginToolBinding plugin =
        decodeNullablePlugin(InvocationJsonSupport.declared(node, "plugin", CONTEXT));
    return new ToolBinding(descriptor, type, environmentName, plugin);
  }

  private static ObjectNode encodePlugin(PluginToolBinding plugin) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("pluginId", plugin.pluginId());
    node.put("contributionLocalName", plugin.contributionLocalName());
    ArrayNode accesses = node.putArray("stateAccesses");
    for (PluginStateAccess access : plugin.stateAccesses()) {
      ObjectNode value = accesses.addObject();
      value.put("customType", access.customType());
      value.put("mode", access.mode().name());
    }
    return node;
  }

  private static PluginToolBinding decodeNullablePlugin(JsonNode value) {
    if (value.isNull()) {
      return null;
    }
    String context = CONTEXT + ".plugin";
    ObjectNode node = InvocationJsonSupport.object(value, context);
    InvocationJsonSupport.requireFields(
        node, context, "pluginId", "contributionLocalName", "stateAccesses");
    ArrayNode accesses =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "stateAccesses", context),
            context + ".stateAccesses");
    List<PluginStateAccess> decoded = new ArrayList<>(accesses.size());
    for (JsonNode accessValue : accesses) {
      String accessContext = context + ".stateAccesses[]";
      ObjectNode accessNode = InvocationJsonSupport.object(accessValue, accessContext);
      InvocationJsonSupport.requireFields(accessNode, accessContext, "customType", "mode");
      decoded.add(
          new PluginStateAccess(
              InvocationJsonSupport.text(accessNode, "customType", accessContext),
              InvocationJsonSupport.requiredEnum(
                  accessNode, "mode", PluginStateAccessMode.class, accessContext)));
    }
    return new PluginToolBinding(
        InvocationJsonSupport.text(node, "pluginId", context),
        InvocationJsonSupport.text(node, "contributionLocalName", context),
        decoded);
  }
}
