package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 一个冻结 Model invocation request 的严格、确定性 JSON codec。 */
public final class ModelInvocationRequestJsonCodec {

  private static final String CONTEXT = "modelInvocationRequest";
  private static final ProviderRequestJsonCodec PROVIDER_CODEC = new ProviderRequestJsonCodec();
  private static final ToolBindingJsonCodec BINDING_CODEC = new ToolBindingJsonCodec();

  public String encode(ModelInvocationRequest request) {
    return InvocationJsonSupport.write(encodeNode(request), CONTEXT);
  }

  public ObjectNode encodeNode(ModelInvocationRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    InvocationJsonSupport.putNullable(node, "environmentName", request.environmentName());
    node.set("providerRequest", PROVIDER_CODEC.encodeNode(request.providerRequest()));
    ArrayNode tools = node.putArray("toolBindings");
    for (ToolBinding binding : request.toolBindings()) {
      tools.add(BINDING_CODEC.encodeNode(binding));
    }
    ArrayNode skills = node.putArray("skillBindings");
    for (SkillBinding skill : request.skillBindings()) {
      skills.add(encodeSkill(skill));
    }
    ArrayNode subagents = node.putArray("subagentBindings");
    for (SubagentBinding subagent : request.subagentBindings()) {
      subagents.add(encodeSubagent(subagent));
    }
    node.put("yoloEnabled", request.yoloEnabled());
    node.put("contextWindow", request.contextWindow());
    if (request.compaction() == null) {
      node.putNull("compaction");
    } else {
      node.set("compaction", encodeCompaction(request.compaction()));
    }
    return node;
  }

  public ModelInvocationRequest decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public ModelInvocationRequest decodeNode(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT);
    InvocationJsonSupport.requireFields(
        node,
        CONTEXT,
        "environmentName",
        "providerRequest",
        "toolBindings",
        "skillBindings",
        "subagentBindings",
        "yoloEnabled",
        "contextWindow",
        "compaction");
    EnvironmentName environmentName =
        InvocationJsonSupport.nullableEnvironmentName(node, "environmentName", CONTEXT);
    ProviderRequest providerRequest =
        PROVIDER_CODEC.decodeNode(InvocationJsonSupport.required(node, "providerRequest", CONTEXT));
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
    boolean yoloEnabled = InvocationJsonSupport.bool(node, "yoloEnabled", CONTEXT);
    int contextWindow = InvocationJsonSupport.positiveInt(node, "contextWindow", CONTEXT);
    JsonNode compactionNode = InvocationJsonSupport.declared(node, "compaction", CONTEXT);
    CompactionRequest compaction =
        compactionNode.isNull() ? null : decodeCompaction(compactionNode);
    return new ModelInvocationRequest(
        environmentName,
        providerRequest,
        toolBindings,
        skillBindings,
        subagentBindings,
        yoloEnabled,
        contextWindow,
        compaction);
  }

  private static ObjectNode encodeCompaction(CompactionRequest compaction) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("phase", compaction.phase().name());
    node.put("trigger", compaction.trigger().name());
    // tokensBefore 是数值（decode 用 nonNegativeLong 要求 integral number）；ids 是规范十进制字符串。
    node.put("tokensBefore", compaction.tokensBefore());
    node.put("firstKeptEntryId", Long.toString(compaction.firstKeptEntryId()));
    node.put("cutEntryId", Long.toString(compaction.cutEntryId()));
    if (compaction.turnPrefixStartEntryId() == null) {
      node.putNull("turnPrefixStartEntryId");
    } else {
      node.put("turnPrefixStartEntryId", Long.toString(compaction.turnPrefixStartEntryId()));
    }
    return node;
  }

  private static CompactionRequest decodeCompaction(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, "compaction");
    InvocationJsonSupport.requireFields(
        node,
        "compaction",
        "phase",
        "trigger",
        "tokensBefore",
        "firstKeptEntryId",
        "cutEntryId",
        "turnPrefixStartEntryId");
    return new CompactionRequest(
        InvocationJsonSupport.requiredEnum(node, "phase", CompactionPhase.class, "compaction"),
        InvocationJsonSupport.requiredEnum(node, "trigger", CompactionTrigger.class, "compaction"),
        InvocationJsonSupport.nonNegativeLong(node, "tokensBefore", "compaction"),
        canonicalPositiveId(node, "firstKeptEntryId"),
        canonicalPositiveId(node, "cutEntryId"),
        nullableCanonicalPositiveId(node, "turnPrefixStartEntryId"));
  }

  private static long canonicalPositiveId(ObjectNode node, String field) {
    String text = InvocationJsonSupport.text(node, field, "compaction");
    long value;
    try {
      value = Long.parseLong(text);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          "compaction." + field + " must be a decimal string", error);
    }
    if (value <= 0 || !Long.toString(value).equals(text)) {
      throw new IllegalArgumentException(
          "compaction." + field + " must be a canonical positive decimal string");
    }
    return value;
  }

  private static Long nullableCanonicalPositiveId(ObjectNode node, String field) {
    String text = InvocationJsonSupport.nullableText(node, field, "compaction");
    if (text == null) {
      return null;
    }
    long value;
    try {
      value = Long.parseLong(text);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          "compaction." + field + " must be a decimal string", error);
    }
    if (value <= 0 || !Long.toString(value).equals(text)) {
      throw new IllegalArgumentException(
          "compaction." + field + " must be a canonical positive decimal string");
    }
    return value;
  }

  private static ObjectNode encodeSkill(SkillBinding skill) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("name", skill.name());
    node.put("description", skill.description());
    InvocationJsonSupport.putNullable(node, "sourceEnvironmentName", skill.sourceEnvironmentName());
    return node;
  }

  private static SkillBinding decodeSkill(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, "skillBinding");
    InvocationJsonSupport.requireFields(
        node, "skillBinding", "name", "description", "sourceEnvironmentName");
    return new SkillBinding(
        InvocationJsonSupport.text(node, "name", "skillBinding"),
        InvocationJsonSupport.text(node, "description", "skillBinding"),
        InvocationJsonSupport.nullableEnvironmentName(
            node, "sourceEnvironmentName", "skillBinding"));
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
