package fun.fengwk.kkstudio.harness.codec;

import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.array;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.object;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.orderedSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.history.SystemHostedTool;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SYSTEM entry payload 的严格、确定性 JSON 形态：sections 增量、tool 与 hosted tool 的增删以及 baseline 标记。
 *
 * <p>由 {@link HistoryEntryPayloadJsonCodec} 组合使用；字段集合、写入顺序与拒绝文案保持原样。
 */
final class HistorySystemJsonCodec {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> SYSTEM_FIELDS =
      orderedSet(
          "sections",
          "removedSections",
          "addedTools",
          "removedTools",
          "addedHostedTools",
          "removedHostedTools",
          "baseline");
  private static final Set<String> SYSTEM_HOSTED_TOOL_FIELDS = orderedSet("name", "definitionJson");

  private static final ProviderToolDefinitionJsonCodec PROVIDER_TOOL_DEFINITION_CODEC =
      new ProviderToolDefinitionJsonCodec();

  private HistorySystemJsonCodec() {}

  static ObjectNode encodeSystem(SystemPayload value) {
    ObjectNode node = NODES.objectNode();
    ObjectNode sections = node.putObject("sections");
    value.sections().forEach(sections::put);
    ArrayNode removedSections = node.putArray("removedSections");
    value.removedSections().forEach(removedSections::add);
    ArrayNode added = node.putArray("addedTools");
    value
        .addedTools()
        .forEach(definition -> added.add(PROVIDER_TOOL_DEFINITION_CODEC.encodeNode(definition)));
    ArrayNode removed = node.putArray("removedTools");
    value.removedTools().forEach(removed::add);
    ArrayNode addedHosted = node.putArray("addedHostedTools");
    for (SystemHostedTool tool : value.addedHostedTools()) {
      ObjectNode item = addedHosted.addObject();
      item.put("name", tool.name());
      item.put("definitionJson", tool.definitionJson());
    }
    ArrayNode removedHosted = node.putArray("removedHostedTools");
    value.removedHostedTools().forEach(removedHosted::add);
    node.put("baseline", value.baseline());
    return node;
  }

  static SystemPayload decodeSystem(JsonNode value) {
    String context = "SYSTEM";
    ObjectNode node = object(value, context);
    JsonCodecSupport.requireFields(node, SYSTEM_FIELDS, context);
    Map<String, String> sections = decodeSections(node, context);
    List<String> removedSections =
        decodeTextArray(node.get("removedSections"), context + ".removedSections");
    List<ProviderToolDefinition> addedTools = decodeAddedTools(node, context);
    List<String> removedTools =
        decodeTextArray(node.get("removedTools"), context + ".removedTools");
    List<SystemHostedTool> addedHostedTools = decodeAddedHostedTools(node, context);
    List<String> removedHostedTools =
        decodeTextArray(node.get("removedHostedTools"), context + ".removedHostedTools");
    // baseline 只在六个集合都读取通过后才校验，与基线拒绝顺序一致。
    if (!node.get("baseline").isBoolean()) {
      throw new IllegalArgumentException("SYSTEM.baseline must be boolean");
    }
    return new SystemPayload(
        sections,
        removedSections,
        addedTools,
        removedTools,
        addedHostedTools,
        removedHostedTools,
        node.get("baseline").booleanValue());
  }

  /** SYSTEM sections 是 name -> 非 blank 文本的 map；顺序保留。 */
  static Map<String, String> decodeSections(ObjectNode node, String context) {
    ObjectNode sectionNode = object(node.get("sections"), context + ".sections");
    Map<String, String> sections = new LinkedHashMap<>();
    sectionNode
        .properties()
        .forEach(
            entry -> {
              if (!entry.getValue().isString() || entry.getValue().stringValue().isBlank()) {
                throw new IllegalArgumentException("SYSTEM.sections values must be non-blank text");
              }
              sections.put(entry.getKey(), entry.getValue().stringValue());
            });
    return sections;
  }

  static List<ProviderToolDefinition> decodeAddedTools(ObjectNode node, String context) {
    ArrayNode added = array(node.get("addedTools"), context + ".addedTools");
    List<ProviderToolDefinition> addedTools = new ArrayList<>(added.size());
    for (JsonNode item : added) {
      addedTools.add(PROVIDER_TOOL_DEFINITION_CODEC.decodeNode(item));
    }
    return addedTools;
  }

  static List<SystemHostedTool> decodeAddedHostedTools(ObjectNode node, String context) {
    String itemContext = context + ".addedHostedTools[]";
    ArrayNode values = array(node.get("addedHostedTools"), context + ".addedHostedTools");
    List<SystemHostedTool> addedHostedTools = new ArrayList<>(values.size());
    for (JsonNode item : values) {
      ObjectNode hostedTool = object(item, itemContext);
      JsonCodecSupport.requireFields(hostedTool, SYSTEM_HOSTED_TOOL_FIELDS, itemContext);
      addedHostedTools.add(
          new SystemHostedTool(
              HistoryValueCodecs.requiredText(hostedTool, "name", itemContext),
              HistoryValueCodecs.requiredText(hostedTool, "definitionJson", itemContext)));
    }
    return addedHostedTools;
  }

  /** removedSections / removedTools / removedHostedTools 都是 string 数组。 */
  static List<String> decodeTextArray(JsonNode value, String context) {
    ArrayNode values = array(value, context);
    List<String> texts = new ArrayList<>(values.size());
    for (JsonNode item : values) {
      if (!item.isString()) {
        throw new IllegalArgumentException(context + "[] must be text");
      }
      texts.add(item.stringValue());
    }
    return texts;
  }
}
