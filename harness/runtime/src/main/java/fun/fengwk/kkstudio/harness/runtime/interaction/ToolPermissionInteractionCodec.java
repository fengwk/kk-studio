package fun.fengwk.kkstudio.harness.runtime.interaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** Strict product boundary for persisted Tool permission prompts and user approval responses. */
public final class ToolPermissionInteractionCodec {
  private static final Set<String> REQUEST_FIELDS =
      Set.of("invocationId", "threadId", "tool", "workdir", "arguments");
  private static final Set<String> RESPONSE_FIELDS = Set.of("approved");

  private final ObjectMapper objectMapper;

  public ToolPermissionInteractionCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /** Projects only the safe user-facing Tool preview from a strict persisted request. */
  public InteractionProjection project(InteractionRequest request) {
    PermissionRequest parsed = parseRequest(request);
    ObjectNode projection = objectMapper.createObjectNode();
    projection.put("tool", parsed.tool());
    projection.put("workdir", parsed.workdir());
    projection.put("arguments", parsed.arguments());
    return new InteractionProjection(write(projection, "tool permission projection"));
  }

  /**
   * Resolves an Interaction after verifying the audit request names its explicit durable Tool
   * invocation.
   */
  public ToolPermissionDecision resolve(Interaction interaction, InteractionResponse response) {
    Objects.requireNonNull(interaction, "interaction");
    PermissionRequest request = parseRequest(interaction.request());
    if (request.invocationId() != interaction.toolInvocationId()) {
      throw new IllegalArgumentException(
          "tool permission request invocationId does not match interaction");
    }
    return parseApproved(response) ? ToolPermissionDecision.APPROVE : ToolPermissionDecision.DENY;
  }

  private PermissionRequest parseRequest(InteractionRequest request) {
    Objects.requireNonNull(request, "request");
    JsonNode node = readObject(request.json(), "tool permission request");
    requireExactFields(node, REQUEST_FIELDS, "tool permission request");
    return new PermissionRequest(
        positiveId(node.get("invocationId"), "tool permission request.invocationId"),
        positiveId(node.get("threadId"), "tool permission request.threadId"),
        nonBlankText(node.get("tool"), "tool permission request.tool"),
        nonBlankText(node.get("workdir"), "tool permission request.workdir"),
        nonBlankText(node.get("arguments"), "tool permission request.arguments"));
  }

  private boolean parseApproved(InteractionResponse response) {
    Objects.requireNonNull(response, "response");
    JsonNode node = readObject(response.json(), "tool permission response");
    requireExactFields(node, RESPONSE_FIELDS, "tool permission response");
    JsonNode approved = node.get("approved");
    if (approved == null || !approved.isBoolean()) {
      throw new IllegalArgumentException("tool permission response.approved must be boolean");
    }
    return approved.booleanValue();
  }

  private JsonNode readObject(String json, String label) {
    try {
      JsonNode node = objectMapper.readTree(json);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(label + " must be a JSON object");
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(label + " must be valid JSON", error);
    }
  }

  private static void requireExactFields(JsonNode node, Set<String> expected, String label) {
    Set<String> actual = new HashSet<>();
    Iterator<String> fields = node.fieldNames();
    fields.forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          label + " fields must be exactly " + expected + " but were " + actual);
    }
  }

  private static long positiveId(JsonNode value, String label) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() <= 0) {
      throw new IllegalArgumentException(label + " must be a positive integer");
    }
    return value.longValue();
  }

  private static String nonBlankText(JsonNode value, String label) {
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(label + " must be a non-blank string");
    }
    return value.textValue();
  }

  private String write(ObjectNode node, String label) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("failed to encode " + label, error);
    }
  }

  private record PermissionRequest(
      long invocationId, long threadId, String tool, String workdir, String arguments) {}
}
