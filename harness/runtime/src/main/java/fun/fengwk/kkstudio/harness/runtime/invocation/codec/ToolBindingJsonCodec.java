package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolPluginSource;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 一个 durable Tool binding 的严格、确定性 JSON codec。 */
public final class ToolBindingJsonCodec {

  private static final String CONTEXT = "toolBinding";
  private static final AgentToolDefinitionJsonCodec DEFINITION_CODEC =
      new AgentToolDefinitionJsonCodec();

  public String encode(ToolBinding binding) {
    return InvocationJsonSupport.write(encodeNode(binding), CONTEXT);
  }

  public ObjectNode encodeNode(ToolBinding binding) {
    Objects.requireNonNull(binding, "binding");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("catalogEpoch", binding.catalogEpoch());
    node.set("definition", DEFINITION_CODEC.encodeNode(binding.definition()));
    node.set("contributor", encodeContributor(binding.contributor()));
    node.put("environmentSupport", binding.environmentSupport().name());
    InvocationJsonSupport.putNullable(node, "environmentId", binding.environmentId());
    InvocationJsonSupport.putNullable(node, "environmentName", binding.environmentName());
    // 插件工具携带冻结来源；内置与 MCP 工具没有插件来源，输出显式 null。
    if (binding.pluginSource() == null) {
      node.putNull("pluginSource");
    } else {
      node.set("pluginSource", encodePluginSource(binding.pluginSource()));
    }
    return node;
  }

  public ToolBinding decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ToolBinding decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(
        node,
        CONTEXT,
        "catalogEpoch",
        "definition",
        "contributor",
        "environmentSupport",
        "environmentId",
        "environmentName",
        "pluginSource");
    long catalogEpoch = InvocationJsonSupport.positiveLong(node, "catalogEpoch", CONTEXT);
    AgentToolDefinition definition =
        DEFINITION_CODEC.decodeNode(InvocationJsonSupport.required(node, "definition", CONTEXT));
    ContributorBinding contributor =
        decodeContributor(InvocationJsonSupport.required(node, "contributor", CONTEXT));
    EnvironmentSupport environmentSupport =
        InvocationJsonSupport.requiredEnum(
            node, "environmentSupport", EnvironmentSupport.class, CONTEXT);
    EnvironmentId environmentId =
        InvocationJsonSupport.nullableEnvironmentId(node, "environmentId", CONTEXT);
    String environmentName = InvocationJsonSupport.nullableText(node, "environmentName", CONTEXT);
    JsonNode pluginSourceNode = InvocationJsonSupport.declared(node, "pluginSource", CONTEXT);
    ToolPluginSource pluginSource =
        pluginSourceNode.isNull() ? null : decodePluginSource(pluginSourceNode);
    // 非法组合（NONE 携带环境、REQUIRED 缺少环境、id/name 不成对）由 ToolBinding 构造边界 fail
    // closed。
    return new ToolBinding(
        catalogEpoch,
        definition,
        contributor,
        environmentSupport,
        environmentId,
        environmentName,
        pluginSource);
  }

  private static ObjectNode encodePluginSource(ToolPluginSource source) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("pluginId", source.pluginId());
    node.put("pluginVersion", source.pluginVersion());
    node.put("configRevision", source.configRevision());
    node.put("capabilityFingerprint", source.capabilityFingerprint());
    node.put("contextFingerprint", source.contextFingerprint());
    node.put("authGeneration", source.authGeneration().toString());
    return node;
  }

  private static ToolPluginSource decodePluginSource(JsonNode value) {
    String context = CONTEXT + ".pluginSource";
    ObjectNode node = InvocationJsonSupport.object(value, context);
    InvocationJsonSupport.requireFields(
        node,
        context,
        "pluginId",
        "pluginVersion",
        "configRevision",
        "capabilityFingerprint",
        "contextFingerprint",
        "authGeneration");
    return new ToolPluginSource(
        InvocationJsonSupport.text(node, "pluginId", context),
        InvocationJsonSupport.text(node, "pluginVersion", context),
        InvocationJsonSupport.positiveLong(node, "configRevision", context),
        InvocationJsonSupport.text(node, "capabilityFingerprint", context),
        InvocationJsonSupport.text(node, "contextFingerprint", context),
        InvocationJsonSupport.requiredUuid(node, "authGeneration", context));
  }

  private static ObjectNode encodeContributor(ContributorBinding contributor) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("contributorId", contributor.contributorId());
    node.put("localName", contributor.localName());
    ArrayNode accesses = node.putArray("stateAccesses");
    for (ContributorStateAccess access : contributor.stateAccesses()) {
      ObjectNode value = accesses.addObject();
      value.put("customType", access.customType());
      value.put("mode", access.mode().name());
    }
    return node;
  }

  private static ContributorBinding decodeContributor(JsonNode value) {
    String context = CONTEXT + ".contributor";
    ObjectNode node = InvocationJsonSupport.object(value, context);
    InvocationJsonSupport.requireFields(
        node, context, "contributorId", "localName", "stateAccesses");
    ArrayNode accesses =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "stateAccesses", context),
            context + ".stateAccesses");
    List<ContributorStateAccess> decoded = new ArrayList<>(accesses.size());
    for (JsonNode accessValue : accesses) {
      String accessContext = context + ".stateAccesses[]";
      ObjectNode accessNode = InvocationJsonSupport.object(accessValue, accessContext);
      InvocationJsonSupport.requireFields(accessNode, accessContext, "customType", "mode");
      decoded.add(
          new ContributorStateAccess(
              InvocationJsonSupport.text(accessNode, "customType", accessContext),
              InvocationJsonSupport.requiredEnum(
                  accessNode, "mode", ContributorStateAccessMode.class, accessContext)));
    }
    return new ContributorBinding(
        InvocationJsonSupport.text(node, "contributorId", context),
        InvocationJsonSupport.text(node, "localName", context),
        decoded);
  }
}
