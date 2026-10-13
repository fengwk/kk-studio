package fun.fengwk.kkstudio.harness.runtime.permission;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tool settings 规范 JSON 编解码器，解析并校验严格的 permission 规则。 */
public final class ToolSettingsCodec {
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  public ToolSettings decode(String settingsJson) {
    ObjectNode root = readRoot(settingsJson);
    return new ToolSettings(decodePermission(root.get("permission")));
  }

  /** 保留未知 Tool 设置，但将 permission 统一编码为规范 JSON。 */
  public String canonicalize(String settingsJson) {
    ObjectNode root = readRoot(settingsJson);
    ToolSettings settings = new ToolSettings(decodePermission(root.get("permission")));
    root.set("permission", encodePermission(settings.permission()));
    return JsonValues.write(root);
  }

  /** null 或空白输入规范化为 {@code {}}，其余输入必须是严格 JSON object：未知设置原样保留，不认识的数值既不丢失也不改写。 */
  private static ObjectNode readRoot(String settingsJson) {
    String input = settingsJson == null || settingsJson.isBlank() ? "{}" : settingsJson;
    JsonNode node = JsonValues.readTree(input);
    if (!(node instanceof ObjectNode object)) {
      throw new IllegalArgumentException("settingsJson must be a JSON object");
    }
    return object;
  }

  private Map<String, List<PermissionRule>> decodePermission(JsonNode permissionNode) {
    Map<String, List<PermissionRule>> result = new LinkedHashMap<>();
    if (permissionNode == null || permissionNode.isNull()) {
      return result;
    }
    if (!permissionNode.isObject()) {
      throw new IllegalArgumentException("permission must be a JSON object");
    }
    for (Map.Entry<String, JsonNode> field : permissionNode.properties()) {
      String key = field.getKey();
      PermissionKeyValidator.requireValid(key, "permission key");
      result.put(key, decodeRules(field.getValue()));
    }
    return result;
  }

  private List<PermissionRule> decodeRules(JsonNode node) {
    if (!node.isArray()) {
      throw new IllegalArgumentException("permission rules must be an array");
    }
    List<PermissionRule> rules = new ArrayList<>();
    for (JsonNode rule : node) {
      if (!rule.isObject()
          || rule.size() != 2
          || !rule.path("pattern").isString()
          || !rule.path("action").isString()) {
        throw new IllegalArgumentException(
            "permission rule must be an object with exact string keys 'pattern' and 'action'");
      }
      rules.add(
          new PermissionRule(
              rule.path("pattern").asText(),
              PermissionAction.fromValue(rule.path("action").asText())));
    }
    return List.copyOf(rules);
  }

  private static ObjectNode encodePermission(Map<String, List<PermissionRule>> permission) {
    ObjectNode result = NODES.objectNode();
    permission.forEach(
        (key, rules) -> {
          ArrayNode array = result.putArray(key);
          for (PermissionRule rule : rules) {
            ObjectNode node = array.addObject();
            node.put("pattern", rule.pattern());
            node.put("action", rule.action().value());
          }
        });
    return result;
  }
}
