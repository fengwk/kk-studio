package fun.fengwk.kkstudio.harness.environment.daemon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Daemon READY 能力 payload 的严格 codec：版本化、类型化，拒绝未知字段、重复键、尾随内容与缺失字段。 */
public final class DaemonCapabilitiesCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public String encode(DaemonCapabilities capabilities) {
    Objects.requireNonNull(capabilities, "capabilities");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("version", capabilities.version());
    ObjectNode environment = root.putObject("environment");
    environment.put("operatingSystem", capabilities.environment().operatingSystem().wireValue());
    environment.put("timeZone", capabilities.environment().timeZone());
    environment.put("note", capabilities.environment().note());
    environment.put("rootPath", capabilities.environment().rootPath());
    ArrayNode skills = root.putArray("skills");
    for (DaemonSkillDescriptor skill : capabilities.skills()) {
      ObjectNode node = skills.addObject();
      node.put("name", skill.name());
      node.put("description", skill.description());
    }
    ArrayNode servers = root.putArray("mcpServers");
    for (DaemonMcpServerDescriptor server : capabilities.mcpServers()) {
      ObjectNode node = servers.addObject();
      node.put("name", server.name());
      node.put("status", server.status().name());
      if (server.error() != null) {
        node.put("error", server.error());
      } else {
        node.putNull("error");
      }
      ArrayNode tools = node.putArray("tools");
      for (DaemonMcpToolDescriptor tool : server.tools()) {
        ObjectNode toolNode = tools.addObject();
        toolNode.put("name", tool.name());
        toolNode.put("description", tool.description());
      }
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode READY capabilities payload", error);
    }
  }

  public DaemonCapabilities decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("malformed READY payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("READY payload must be an object");
    }
    rejectUnknown(root, Set.of("version", "environment", "skills", "mcpServers"));
    int version = requiredVersion(root);
    DaemonEnvironmentInfo environment = decodeEnvironment(requiredObject(root, "environment"));
    List<DaemonSkillDescriptor> skills = decodeSkills(requiredArray(root, "skills"));
    List<DaemonMcpServerDescriptor> servers = decodeServers(requiredArray(root, "mcpServers"));
    try {
      return new DaemonCapabilities(version, environment, skills, servers);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY capabilities validation failed: " + error.getMessage(), error);
    }
  }

  private static int requiredVersion(ObjectNode root) {
    JsonNode version = root.get("version");
    if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) {
      throw new DaemonProtocolException("READY payload.version must be an integer");
    }
    return version.intValue();
  }

  private static JsonNode requiredArray(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || !node.isArray()) {
      throw new DaemonProtocolException("READY payload." + field + " must be an array");
    }
    return node;
  }

  private static ObjectNode requiredObject(ObjectNode root, String field) {
    JsonNode node = root.get(field);
    if (!(node instanceof ObjectNode objectNode)) {
      throw new DaemonProtocolException("READY payload." + field + " must be an object");
    }
    return objectNode;
  }

  private static DaemonEnvironmentInfo decodeEnvironment(ObjectNode node) {
    rejectUnknown(node, Set.of("operatingSystem", "timeZone", "note", "rootPath"));
    String operatingSystemText = text(node, "operatingSystem", "READY environment");
    String timeZone = text(node, "timeZone", "READY environment");
    String note = text(node, "note", "READY environment");
    String rootPath = text(node, "rootPath", "READY environment");
    try {
      return new DaemonEnvironmentInfo(
          DaemonOperatingSystem.fromWireValue(operatingSystemText), timeZone, note, rootPath);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "READY environment validation failed: " + error.getMessage(), error);
    }
  }

  private static List<DaemonSkillDescriptor> decodeSkills(JsonNode skillsNode) {
    Map<String, DaemonSkillDescriptor> seen = new LinkedHashMap<>();
    List<DaemonSkillDescriptor> result = new ArrayList<>();
    int index = 0;
    for (JsonNode element : skillsNode) {
      if (!(element instanceof ObjectNode node)) {
        throw new DaemonProtocolException("READY skills[" + index + "] must be an object");
      }
      rejectUnknown(node, Set.of("name", "description"));
      String name = text(node, "name", "READY skills[" + index + "]");
      String description = text(node, "description", "READY skills[" + index + "]");
      DaemonSkillDescriptor skill;
      try {
        skill = new DaemonSkillDescriptor(name, description);
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "READY skill validation failed for " + name + ": " + error.getMessage(), error);
      }
      if (seen.putIfAbsent(name, skill) != null) {
        throw new DaemonProtocolException("duplicate READY skill: " + name);
      }
      result.add(skill);
      index++;
    }
    return List.copyOf(result);
  }

  private static List<DaemonMcpServerDescriptor> decodeServers(JsonNode serversNode) {
    List<DaemonMcpServerDescriptor> result = new ArrayList<>();
    int index = 0;
    for (JsonNode element : serversNode) {
      if (!(element instanceof ObjectNode node)) {
        throw new DaemonProtocolException("READY mcpServers[" + index + "] must be an object");
      }
      rejectUnknown(node, Set.of("name", "status", "error", "tools"));
      String name = text(node, "name", "READY mcpServers[" + index + "]");
      String statusText = text(node, "status", "READY mcpServers[" + index + "]");
      DaemonMcpServerStatus status;
      try {
        status = DaemonMcpServerStatus.valueOf(statusText);
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "READY mcpServers[" + index + "].status must be READY or FAILED");
      }
      String error = requiredError(node, "error", "READY mcpServers[" + index + "]");
      List<DaemonMcpToolDescriptor> tools = decodeTools(requiredArray(node, "tools"), index);
      DaemonMcpServerDescriptor server;
      try {
        server = new DaemonMcpServerDescriptor(name, status, error, tools);
      } catch (IllegalArgumentException validationError) {
        throw new DaemonProtocolException(
            "READY MCP server validation failed for " + name + ": " + validationError.getMessage(),
            validationError);
      }
      result.add(server);
      index++;
    }
    return List.copyOf(result);
  }

  private static List<DaemonMcpToolDescriptor> decodeTools(JsonNode toolsNode, int serverIndex) {
    List<DaemonMcpToolDescriptor> result = new ArrayList<>();
    int index = 0;
    for (JsonNode element : toolsNode) {
      if (!(element instanceof ObjectNode node)) {
        throw new DaemonProtocolException(
            "READY mcpServers[" + serverIndex + "].tools[" + index + "] must be an object");
      }
      rejectUnknown(node, Set.of("name", "description"));
      String name =
          text(node, "name", "READY mcpServers[" + serverIndex + "].tools[" + index + "]");
      String description =
          text(node, "description", "READY mcpServers[" + serverIndex + "].tools[" + index + "]");
      DaemonMcpToolDescriptor tool;
      try {
        tool = new DaemonMcpToolDescriptor(name, description);
      } catch (IllegalArgumentException error) {
        throw new DaemonProtocolException(
            "READY MCP tool validation failed for " + name + ": " + error.getMessage(), error);
      }
      result.add(tool);
      index++;
    }
    return List.copyOf(result);
  }

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }

  /** {@code error} 是必填字段：必须是文本或显式 null；缺失即拒绝。 */
  private static String requiredError(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new DaemonProtocolException(context + "." + field + " is required");
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new DaemonProtocolException(context + "." + field + " must be text or null");
    }
    return value.textValue();
  }

  private static void rejectUnknown(ObjectNode node, Set<String> expected) {
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!expected.contains(field)) {
                throw new DaemonProtocolException("unexpected READY field: " + field);
              }
            });
  }
}
