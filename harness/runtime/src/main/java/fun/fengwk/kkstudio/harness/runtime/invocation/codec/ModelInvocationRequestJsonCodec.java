package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict deterministic JSON codec for one frozen Model invocation request. */
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
    InvocationJsonSupport.putNullable(node, "environmentId", request.environmentId());
    node.set("providerRequest", PROVIDER_CODEC.encodeNode(request.providerRequest()));
    ArrayNode tools = node.putArray("toolBindings");
    for (ToolBinding binding : request.toolBindings()) {
      tools.add(BINDING_CODEC.encodeNode(binding));
    }
    ArrayNode skills = node.putArray("skillBindings");
    for (SkillBinding skill : request.skillBindings()) {
      skills.add(encodeSkill(skill));
    }
    node.put("yoloEnabled", request.yoloEnabled());
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
        "environmentId",
        "providerRequest",
        "toolBindings",
        "skillBindings",
        "yoloEnabled");
    EnvironmentId environmentId =
        InvocationJsonSupport.nullableEnvironmentId(node, "environmentId", CONTEXT);
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
    boolean yoloEnabled = InvocationJsonSupport.bool(node, "yoloEnabled", CONTEXT);
    return new ModelInvocationRequest(
        environmentId, providerRequest, toolBindings, skillBindings, yoloEnabled);
  }

  private static ObjectNode encodeSkill(SkillBinding skill) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("name", skill.name());
    node.put("description", skill.description());
    InvocationJsonSupport.putNullable(node, "sourceEnvironmentId", skill.sourceEnvironmentId());
    return node;
  }

  private static SkillBinding decodeSkill(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, "skillBinding");
    InvocationJsonSupport.requireFields(
        node, "skillBinding", "name", "description", "sourceEnvironmentId");
    return new SkillBinding(
        InvocationJsonSupport.text(node, "name", "skillBinding"),
        InvocationJsonSupport.text(node, "description", "skillBinding"),
        InvocationJsonSupport.nullableEnvironmentId(node, "sourceEnvironmentId", "skillBinding"));
  }
}
