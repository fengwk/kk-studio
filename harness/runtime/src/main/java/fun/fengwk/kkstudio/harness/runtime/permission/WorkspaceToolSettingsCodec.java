package fun.fengwk.kkstudio.harness.runtime.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Workspace settings canonical codec，兼容 PiBase string/object 简写。 */
public final class WorkspaceToolSettingsCodec {
  private final ObjectMapper objectMapper;

  public WorkspaceToolSettingsCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public WorkspaceToolSettings decode(String settingsJson) {
    ObjectNode root = readRoot(settingsJson);
    return new WorkspaceToolSettings(
        decodePermission(root.get("permission")), root.path("defaultYolo").asBoolean(false));
  }

  /** 保留未知 Workspace 设置，但将 permission/defaultYolo 统一编码为规范 JSON。 */
  public String canonicalize(String settingsJson) {
    ObjectNode root = readRoot(settingsJson);
    WorkspaceToolSettings settings =
        new WorkspaceToolSettings(
            decodePermission(root.get("permission")), root.path("defaultYolo").asBoolean(false));
    root.set("permission", encodePermission(settings.permission()));
    root.put("defaultYolo", settings.defaultYolo());
    try {
      return objectMapper.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode workspace settings", error);
    }
  }

  private ObjectNode readRoot(String settingsJson) {
    String input = settingsJson == null || settingsJson.isBlank() ? "{}" : settingsJson;
    try {
      JsonNode node = objectMapper.readTree(input);
      if (!(node instanceof ObjectNode object)) {
        throw new IllegalArgumentException("settingsJson must be a JSON object");
      }
      JsonNode defaultYolo = object.get("defaultYolo");
      if (defaultYolo != null && !defaultYolo.isBoolean()) {
        throw new IllegalArgumentException("defaultYolo must be boolean");
      }
      return object.deepCopy();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("settingsJson must be valid JSON", error);
    }
  }

  private Map<String, List<PermissionRule>> decodePermission(JsonNode permissionNode) {
    Map<String, List<PermissionRule>> result = new LinkedHashMap<>();
    if (permissionNode == null || permissionNode.isNull()) {
      return result;
    }
    if (permissionNode.isTextual()) {
      result.put("*", decodeRules(permissionNode));
      return result;
    }
    if (!permissionNode.isObject()) {
      throw new IllegalArgumentException(
          "permission must be allow, ask or deny, or a JSON object keyed by tool name");
    }
    for (Map.Entry<String, JsonNode> field : permissionNode.properties()) {
      result.put(field.getKey(), decodeRules(field.getValue()));
    }
    return result;
  }

  private List<PermissionRule> decodeRules(JsonNode node) {
    if (node.isTextual()) {
      return List.of(new PermissionRule("*", PermissionAction.fromValue(node.asText())));
    }
    if (node.isArray()) {
      List<PermissionRule> rules = new ArrayList<>();
      for (JsonNode rule : node) {
        if (!rule.isObject()
            || !rule.path("pattern").isTextual()
            || !rule.path("action").isTextual()) {
          throw new IllegalArgumentException(
              "permission rule must contain string pattern and action");
        }
        rules.add(
            new PermissionRule(
                rule.path("pattern").asText(),
                PermissionAction.fromValue(rule.path("action").asText())));
      }
      return List.copyOf(rules);
    }
    if (node.isObject()) {
      List<PermissionRule> rules = new ArrayList<>();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        if (!field.getValue().isTextual()) {
          throw new IllegalArgumentException("permission shorthand action must be a string");
        }
        rules.add(
            new PermissionRule(
                field.getKey(), PermissionAction.fromValue(field.getValue().asText())));
      }
      return List.copyOf(rules);
    }
    throw new IllegalArgumentException("permission tool rules must be string, object or array");
  }

  private ObjectNode encodePermission(Map<String, List<PermissionRule>> permission) {
    ObjectNode result = objectMapper.createObjectNode();
    permission.forEach(
        (tool, rules) -> {
          ArrayNode array = result.putArray(tool);
          for (PermissionRule rule : rules) {
            ObjectNode node = array.addObject();
            node.put("pattern", rule.pattern());
            node.put("action", rule.action().value());
          }
        });
    return result;
  }
}
