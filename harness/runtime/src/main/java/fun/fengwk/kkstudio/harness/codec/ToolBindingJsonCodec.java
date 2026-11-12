package fun.fengwk.kkstudio.harness.codec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.contributor.api.ConfiguredSkill;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
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
    ArrayNode skills = InvocationJsonSupport.NODES.arrayNode();
    for (ConfiguredSkill skill : binding.configuredSkills()) {
      ObjectNode item = InvocationJsonSupport.NODES.objectNode();
      item.put("name", skill.name());
      item.put("packageName", skill.packageName());
      item.put("version", skill.version());
      skills.add(item);
    }
    node.set("configuredSkills", skills);
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
        "environmentName",
        "configuredSkills");
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
    List<ConfiguredSkill> configuredSkills = decodeConfiguredSkills(node);
    return new ToolBinding(
        definition,
        contributor,
        environmentSupport,
        environmentId,
        environmentName,
        configuredSkills);
  }

  private static List<ConfiguredSkill> decodeConfiguredSkills(ObjectNode node) {
    JsonNode value = InvocationJsonSupport.required(node, "configuredSkills", CONTEXT);
    if (!value.isArray()) {
      throw new IllegalArgumentException(CONTEXT + ".configuredSkills must be an array");
    }
    List<ConfiguredSkill> skills = new ArrayList<>();
    for (JsonNode itemValue : value) {
      ObjectNode item = InvocationJsonSupport.object(itemValue, CONTEXT + ".configuredSkills[]");
      String itemContext = CONTEXT + ".configuredSkills[]";
      InvocationJsonSupport.requireFields(item, itemContext, "name", "packageName", "version");
      skills.add(
          new ConfiguredSkill(
              InvocationJsonSupport.text(item, "name", itemContext),
              InvocationJsonSupport.text(item, "packageName", itemContext),
              InvocationJsonSupport.text(item, "version", itemContext)));
    }
    return List.copyOf(skills);
  }

  private static ObjectNode encodeContributor(ContributorBinding contributor) {
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("contributorId", contributor.contributorId());
    node.put("localName", contributor.localName());
    return node;
  }

  private static ContributorBinding decodeContributor(JsonNode value) {
    String context = CONTEXT + ".contributor";
    ObjectNode node = InvocationJsonSupport.object(value, context);
    InvocationJsonSupport.requireFields(node, context, "contributorId", "localName");
    return new ContributorBinding(
        InvocationJsonSupport.text(node, "contributorId", context),
        InvocationJsonSupport.text(node, "localName", context));
  }
}
