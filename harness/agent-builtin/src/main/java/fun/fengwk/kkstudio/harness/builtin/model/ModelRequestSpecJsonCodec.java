package fun.fengwk.kkstudio.harness.builtin.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.codec.ModelDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationJsonSupport;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolBindingJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 一个冻结 {@link ModelRequestSpec} 的严格、确定性 JSON codec。 */
public final class ModelRequestSpecJsonCodec {

  private static final String CONTEXT = "modelRequestSpec";
  private static final ModelDescriptorJsonCodec MODEL_CODEC = new ModelDescriptorJsonCodec();
  private static final ProviderRequestJsonCodec PROVIDER_CODEC = new ProviderRequestJsonCodec();
  private static final ToolBindingJsonCodec BINDING_CODEC = new ToolBindingJsonCodec();

  public String encode(ModelRequestSpec spec) {
    return InvocationJsonSupport.write(encodeNode(spec), CONTEXT);
  }

  public ObjectNode encodeNode(ModelRequestSpec spec) {
    Objects.requireNonNull(spec, "spec");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("providerType", spec.providerType().name());
    node.put("providerConnectionGenerationId", spec.providerConnectionGenerationId().toString());
    node.set("model", MODEL_CODEC.encodeDescriptorNode(spec.model()));
    node.set("variant", MODEL_CODEC.encodeVariantNode(spec.variant()));
    node.put("outputTokens", spec.outputTokens());
    node.put("systemInstruction", spec.systemInstruction());
    ArrayNode tools = node.putArray("toolBindings");
    for (ToolBinding binding : spec.toolBindings()) {
      tools.add(BINDING_CODEC.encodeNode(binding));
    }
    ArrayNode subagents = node.putArray("subagentBindings");
    for (SubagentBinding subagent : spec.subagentBindings()) {
      subagents.add(encodeSubagent(subagent));
    }
    node.set("cacheControl", PROVIDER_CODEC.encodeCacheControlNode(spec.cacheControl()));
    if (spec.groupExecution() != null) {
      node.set("groupExecution", encodeGroup(spec.groupExecution()));
    }
    return node;
  }

  public ModelRequestSpec decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ModelRequestSpec decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(
        node,
        CONTEXT,
        node.has("groupExecution")
            ? new String[] {
              "providerType",
              "providerConnectionGenerationId",
              "model",
              "variant",
              "outputTokens",
              "systemInstruction",
              "toolBindings",
              "subagentBindings",
              "cacheControl",
              "groupExecution"
            }
            : new String[] {
              "providerType",
              "providerConnectionGenerationId",
              "model",
              "variant",
              "outputTokens",
              "systemInstruction",
              "toolBindings",
              "subagentBindings",
              "cacheControl"
            });
    ArrayNode toolNodes =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "toolBindings", CONTEXT), "toolBindings");
    List<ToolBinding> toolBindings = new ArrayList<>(toolNodes.size());
    for (JsonNode toolNode : toolNodes) {
      toolBindings.add(BINDING_CODEC.decodeNode(toolNode));
    }
    ArrayNode subagentNodes =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "subagentBindings", CONTEXT), "subagentBindings");
    List<SubagentBinding> subagentBindings = new ArrayList<>(subagentNodes.size());
    for (JsonNode subagentNode : subagentNodes) {
      subagentBindings.add(decodeSubagent(subagentNode));
    }
    return new ModelRequestSpec(
        InvocationJsonSupport.requiredEnum(node, "providerType", ProviderType.class, CONTEXT),
        InvocationJsonSupport.requiredUuid(node, "providerConnectionGenerationId", CONTEXT),
        MODEL_CODEC.decodeDescriptorNode(InvocationJsonSupport.required(node, "model", CONTEXT)),
        MODEL_CODEC.decodeVariantNode(InvocationJsonSupport.required(node, "variant", CONTEXT)),
        InvocationJsonSupport.positiveInt(node, "outputTokens", CONTEXT),
        InvocationJsonSupport.text(node, "systemInstruction", CONTEXT),
        toolBindings,
        subagentBindings,
        PROVIDER_CODEC.decodeCacheControlNode(
            InvocationJsonSupport.required(node, "cacheControl", CONTEXT)),
        node.has("groupExecution") ? decodeGroup(node.get("groupExecution")) : null);
  }

  private static ObjectNode encodeGroup(ModelGroupExecution group) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("groupName", group.groupName());
    node.put("groupVersion", group.groupVersion());
    ArrayNode codes = node.putArray("fallbackStatusCodes");
    group.fallbackStatusCodes().stream().sorted().forEach(codes::add);
    node.put("cooldownSeconds", group.cooldownSeconds());
    ArrayNode members = node.putArray("members");
    for (ModelGroupMemberExecution member : group.members()) {
      ObjectNode memberNode = members.addObject();
      memberNode.put("providerType", member.providerType().name());
      memberNode.put(
          "providerConnectionGenerationId", member.providerConnectionGenerationId().toString());
      memberNode.set("model", MODEL_CODEC.encodeDescriptorNode(member.model()));
      memberNode.set("variant", MODEL_CODEC.encodeVariantNode(member.variant()));
    }
    return node;
  }

  private static ModelGroupExecution decodeGroup(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, "groupExecution");
    InvocationJsonSupport.requireFields(
        node,
        "groupExecution",
        "groupName",
        "groupVersion",
        "fallbackStatusCodes",
        "cooldownSeconds",
        "members");
    ArrayNode codeNodes =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "fallbackStatusCodes", "groupExecution"),
            "groupExecution.fallbackStatusCodes");
    Set<Integer> codes = new HashSet<>();
    for (JsonNode code : codeNodes) {
      if (!code.isInt()) {
        throw new IllegalArgumentException(
            "groupExecution.fallbackStatusCodes must contain integers");
      }
      codes.add(code.intValue());
    }
    ArrayNode memberNodes =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "members", "groupExecution"),
            "groupExecution.members");
    List<ModelGroupMemberExecution> members = new ArrayList<>();
    for (JsonNode memberNodeValue : memberNodes) {
      ObjectNode memberNode =
          InvocationJsonSupport.object(memberNodeValue, "groupExecution.members[]");
      InvocationJsonSupport.requireFields(
          memberNode,
          "groupExecution.members[]",
          "providerType",
          "providerConnectionGenerationId",
          "model",
          "variant");
      members.add(
          new ModelGroupMemberExecution(
              InvocationJsonSupport.requiredEnum(
                  memberNode, "providerType", ProviderType.class, "groupExecution.members[]"),
              InvocationJsonSupport.requiredUuid(
                  memberNode, "providerConnectionGenerationId", "groupExecution.members[]"),
              MODEL_CODEC.decodeDescriptorNode(
                  InvocationJsonSupport.required(memberNode, "model", "groupExecution.members[]")),
              MODEL_CODEC.decodeVariantNode(
                  InvocationJsonSupport.required(
                      memberNode, "variant", "groupExecution.members[]"))));
    }
    return new ModelGroupExecution(
        InvocationJsonSupport.text(node, "groupName", "groupExecution"),
        InvocationJsonSupport.nonNegativeLong(node, "groupVersion", "groupExecution"),
        members,
        codes,
        InvocationJsonSupport.nonNegativeInt(node, "cooldownSeconds", "groupExecution"));
  }

  private static ObjectNode encodeSubagent(SubagentBinding subagent) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("name", subagent.name());
    node.put("description", subagent.description());
    return node;
  }

  private static SubagentBinding decodeSubagent(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, "subagentBinding");
    InvocationJsonSupport.requireFields(node, "subagentBinding", "name", "description");
    return new SubagentBinding(
        InvocationJsonSupport.text(node, "name", "subagentBinding"),
        InvocationJsonSupport.text(node, "description", "subagentBinding"));
  }
}
