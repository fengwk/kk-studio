package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
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
    node.set("definition", DEFINITION_CODEC.encodeNode(binding.definition()));
    node.set("contributor", encodeContributor(binding.contributor()));
    node.put("environmentSupport", binding.environmentSupport().name());
    InvocationJsonSupport.putNullable(node, "environmentId", binding.environmentId());
    InvocationJsonSupport.putNullable(node, "environmentName", binding.environmentName());
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
        "definition",
        "contributor",
        "environmentSupport",
        "environmentId",
        "environmentName");
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
    // 非法组合（NONE 携带环境、REQUIRED 缺少环境、id/name 不成对）由 ToolBinding 构造边界 fail closed。
    return new ToolBinding(
        definition, contributor, environmentSupport, environmentId, environmentName);
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
