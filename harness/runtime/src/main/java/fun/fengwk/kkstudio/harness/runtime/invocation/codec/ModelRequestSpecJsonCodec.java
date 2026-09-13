package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 一个冻结 {@link ModelRequestSpec} 的严格、确定性 JSON codec。 */
public final class ModelRequestSpecJsonCodec {

  private static final String CONTEXT = "modelRequestSpec";
  private static final ModelDescriptorJsonCodec MODEL_CODEC = new ModelDescriptorJsonCodec();
  private static final ProviderRequestJsonCodec PROVIDER_CODEC = new ProviderRequestJsonCodec();
  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();
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
    ArrayNode preamble = node.putArray("preambleMessages");
    for (AgentMessage message : spec.preambleMessages()) {
      preamble.add(MESSAGE_CODEC.encodeNode(message));
    }
    ArrayNode tools = node.putArray("toolBindings");
    for (ToolBinding binding : spec.toolBindings()) {
      tools.add(BINDING_CODEC.encodeNode(binding));
    }
    ArrayNode skills = node.putArray("skillBindings");
    for (SkillBinding skill : spec.skillBindings()) {
      skills.add(encodeSkill(skill));
    }
    ArrayNode subagents = node.putArray("subagentBindings");
    for (SubagentBinding subagent : spec.subagentBindings()) {
      subagents.add(encodeSubagent(subagent));
    }
    node.set("cacheControl", PROVIDER_CODEC.encodeCacheControlNode(spec.cacheControl()));
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
        "providerType",
        "providerConnectionGenerationId",
        "model",
        "variant",
        "outputTokens",
        "preambleMessages",
        "toolBindings",
        "skillBindings",
        "subagentBindings",
        "cacheControl");
    ArrayNode preambleNodes =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "preambleMessages", CONTEXT), "preambleMessages");
    List<AgentMessage> preambleMessages = new ArrayList<>(preambleNodes.size());
    for (JsonNode messageNode : preambleNodes) {
      preambleMessages.add(MESSAGE_CODEC.decodeNode(messageNode));
    }
    ArrayNode toolNodes =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "toolBindings", CONTEXT), "toolBindings");
    List<ToolBinding> toolBindings = new ArrayList<>(toolNodes.size());
    for (JsonNode toolNode : toolNodes) {
      toolBindings.add(BINDING_CODEC.decodeNode(toolNode));
    }
    ArrayNode skillNodes =
        InvocationJsonSupport.array(
            InvocationJsonSupport.required(node, "skillBindings", CONTEXT), "skillBindings");
    List<SkillBinding> skillBindings = new ArrayList<>(skillNodes.size());
    for (JsonNode skillNode : skillNodes) {
      skillBindings.add(decodeSkill(skillNode));
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
        preambleMessages,
        toolBindings,
        skillBindings,
        subagentBindings,
        PROVIDER_CODEC.decodeCacheControlNode(
            InvocationJsonSupport.required(node, "cacheControl", CONTEXT)));
  }

  private static ObjectNode encodeSkill(SkillBinding skill) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("sourceEnvironmentId", skill.sourceEnvironmentId().toString());
    node.put("sourceId", skill.sourceId().toString());
    node.put("name", skill.name());
    node.put("description", skill.description());
    node.put("baseDirectory", skill.baseDirectory());
    node.put("contentRevision", skill.contentRevision());
    return node;
  }

  /** 严格解码：全部身份/描述字段必填，旧 tolerant 形状（缺少 sourceId/revision）被拒绝。 */
  private static SkillBinding decodeSkill(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, "skillBinding");
    InvocationJsonSupport.requireFields(
        node,
        "skillBinding",
        "sourceEnvironmentId",
        "sourceId",
        "name",
        "description",
        "baseDirectory",
        "contentRevision");
    return new SkillBinding(
        InvocationJsonSupport.environmentId(node, "sourceEnvironmentId", "skillBinding"),
        InvocationJsonSupport.requiredUuid(node, "sourceId", "skillBinding"),
        InvocationJsonSupport.text(node, "name", "skillBinding"),
        InvocationJsonSupport.text(node, "description", "skillBinding"),
        InvocationJsonSupport.text(node, "baseDirectory", "skillBinding"),
        InvocationJsonSupport.text(node, "contentRevision", "skillBinding"));
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
