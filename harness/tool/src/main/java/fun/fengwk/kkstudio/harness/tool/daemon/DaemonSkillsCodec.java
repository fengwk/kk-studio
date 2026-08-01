package fun.fengwk.kkstudio.harness.tool.daemon;

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
import java.util.Set;

/** Strict codec for the skills-only payload carried by the simplified daemon READY message. */
public final class DaemonSkillsCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public String encode(List<DaemonSkillDescriptor> skills) {
    Objects.requireNonNull(skills, "skills");
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode array = root.putArray("skills");
    Map<String, DaemonSkillDescriptor> seen = new LinkedHashMap<>();
    for (DaemonSkillDescriptor skill : skills) {
      Objects.requireNonNull(skill, "skills[]");
      if (seen.putIfAbsent(skill.name(), skill) != null) {
        throw new DaemonProtocolException("duplicate READY skill: " + skill.name());
      }
      ObjectNode node = array.addObject();
      node.put("name", skill.name());
      node.put("description", skill.description());
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode READY skills payload", error);
    }
  }

  public List<DaemonSkillDescriptor> decode(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("malformed READY payload", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new DaemonProtocolException("READY payload must be an object");
    }
    rejectUnknown(root, Set.of("skills"));
    JsonNode skillsNode = root.get("skills");
    if (skillsNode == null || !skillsNode.isArray()) {
      throw new DaemonProtocolException("READY payload.skills must be an array");
    }
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

  private static String text(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(context + "." + field + " must be non-blank text");
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
