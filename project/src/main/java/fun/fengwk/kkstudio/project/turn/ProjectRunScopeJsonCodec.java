package fun.fengwk.kkstudio.project.turn;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ProjectRunScope} 的严格 JSON 编解码：Run 冻结快照是 branch custom state 的唯一事实源。
 *
 * <p>编码输出确定性规范文本（固定字段顺序、无空白），可直接作为 Harness {@code CustomEntryPayload.dataJson} 的 canonical
 * JSON；解码拒绝 duplicate field、trailing token、unknown/missing/null field 与错误类型，绝不静默忽略字段，因此损坏或版本不符的
 * 快照会确定性失败而不是被降级成"没有 Issue 上下文"。
 */
public final class ProjectRunScopeJsonCodec {

  private static final String CONTEXT = "project run scope";
  private static final Set<String> FIELDS =
      Set.of(
          "runId",
          "issueId",
          "projectId",
          "sourceThreadId",
          "issueNumber",
          "issueTitle",
          "issueDescription",
          "stage",
          "stageName",
          "stageInstructions",
          "nextStates",
          "agentName",
          "active");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 编码为规范 JSON 文本；相同快照始终得到相同文本。 */
  public String encode(ProjectRunScope scope) {
    Objects.requireNonNull(scope, "scope");
    ObjectNode node = NODES.objectNode();
    node.put("runId", scope.runId().toString());
    node.put("issueId", scope.issueId().toString());
    node.put("projectId", scope.projectId().toString());
    node.put("sourceThreadId", scope.sourceThreadId().toString());
    node.put("issueNumber", scope.issueNumber());
    node.put("issueTitle", scope.issueTitle());
    if (scope.issueDescription() == null) {
      node.putNull("issueDescription");
    } else {
      node.put("issueDescription", scope.issueDescription());
    }
    node.put("stage", scope.stage());
    node.put("stageName", scope.stageName());
    if (scope.stageInstructions() == null) {
      node.putNull("stageInstructions");
    } else {
      node.put("stageInstructions", scope.stageInstructions());
    }
    ArrayNode nextStates = node.putArray("nextStates");
    for (String next : scope.nextStates()) {
      nextStates.add(next);
    }
    node.put("agentName", scope.agentName());
    node.put("active", scope.active());
    return write(node);
  }

  /** 解码规范 JSON 文本；任何结构或类型错误都抛 {@link IllegalArgumentException}。 */
  public ProjectRunScope decode(String dataJson) {
    JsonNode parsed = parse(dataJson);
    if (!(parsed instanceof ObjectNode node)) {
      throw new IllegalArgumentException(CONTEXT + " must be a JSON object");
    }
    requireExactFields(node);
    UUID runId = requireUuid(node, "runId");
    UUID issueId = requireUuid(node, "issueId");
    UUID projectId = requireUuid(node, "projectId");
    UUID sourceThreadId = requireUuid(node, "sourceThreadId");
    long issueNumber = requireLong(node, "issueNumber");
    String issueTitle = requireText(node, "issueTitle");
    String issueDescription = optionalText(node, "issueDescription");
    String stage = requireText(node, "stage");
    String stageName = requireText(node, "stageName");
    String stageInstructions = optionalText(node, "stageInstructions");
    List<String> nextStates = requireStringArray(node, "nextStates");
    String agentName = requireText(node, "agentName");
    boolean active = requireBoolean(node, "active");
    return new ProjectRunScope(
        runId,
        issueId,
        projectId,
        sourceThreadId,
        issueNumber,
        issueTitle,
        issueDescription,
        stage,
        stageName,
        stageInstructions,
        nextStates,
        agentName,
        active);
  }

  private static JsonNode parse(String dataJson) {
    Objects.requireNonNull(dataJson, "dataJson");
    try {
      return MAPPER.readTree(dataJson);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(CONTEXT + " must be valid JSON", error);
    }
  }

  private static void requireExactFields(ObjectNode node) {
    Set<String> present = new HashSet<>();
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!FIELDS.contains(name)) {
        throw new IllegalArgumentException(CONTEXT + " has unknown field " + name);
      }
      present.add(name);
    }
    for (String field : FIELDS) {
      if (!present.contains(field)) {
        throw new IllegalArgumentException(CONTEXT + " is missing field " + field);
      }
    }
  }

  private static UUID requireUuid(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(CONTEXT + " field " + field + " must be a UUID string");
    }
    try {
      return UUID.fromString(value.textValue());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          CONTEXT + " field " + field + " must be a UUID string", error);
    }
  }

  private static long requireLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber()) {
      throw new IllegalArgumentException(CONTEXT + " field " + field + " must be an integer");
    }
    return value.longValue();
  }

  private static boolean requireBoolean(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(CONTEXT + " field " + field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static String requireText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(
          CONTEXT + " field " + field + " must be a non-blank string");
    }
    return value.textValue();
  }

  private static String optionalText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(CONTEXT + " field " + field + " must be a string or null");
    }
    return value.textValue();
  }

  private static List<String> requireStringArray(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException(CONTEXT + " field " + field + " must be an array");
    }
    List<String> values = new ArrayList<>(value.size());
    for (JsonNode element : value) {
      if (!element.isTextual() || element.textValue().isBlank()) {
        throw new IllegalArgumentException(
            CONTEXT + " field " + field + " must contain non-blank strings");
      }
      values.add(element.textValue());
    }
    return values;
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode " + CONTEXT, error);
    }
  }
}
