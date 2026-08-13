package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Model failed-attempt history 的严格、确定性 JSON array codec。 */
public final class ModelAttemptFailuresJsonCodec {

  private static final String CONTEXT = "modelAttemptFailures";
  private static final ModelInvocationErrorJsonCodec ERRORS = new ModelInvocationErrorJsonCodec();

  public String encode(List<ModelAttemptFailure> history) {
    return InvocationJsonSupport.write(encodeNode(history), CONTEXT);
  }

  public ArrayNode encodeNode(List<ModelAttemptFailure> history) {
    Objects.requireNonNull(history, "history");
    ArrayNode node = InvocationJsonSupport.NODES.arrayNode();
    for (ModelAttemptFailure failure : history) {
      node.add(encodeFailure(failure));
    }
    return node;
  }

  public List<ModelAttemptFailure> decode(String json) {
    return decodeNode(InvocationJsonSupport.parse(json, CONTEXT));
  }

  public List<ModelAttemptFailure> decodeNode(JsonNode value) {
    ArrayNode node = InvocationJsonSupport.array(value, CONTEXT);
    List<ModelAttemptFailure> history = new ArrayList<>(node.size());
    for (JsonNode failure : node) {
      history.add(decodeFailure(failure));
    }
    return List.copyOf(history);
  }

  private static ObjectNode encodeFailure(ModelAttemptFailure failure) {
    Objects.requireNonNull(failure, "failure");
    ObjectNode node = InvocationJsonSupport.NODES.objectNode();
    node.put("attempt", failure.attempt());
    node.put("sequence", failure.sequence());
    node.put("text", failure.text());
    node.put("thinking", failure.thinking());
    node.set("error", ERRORS.encodeNode(failure.error()));
    node.put("failedAt", failure.failedAt().toString());
    node.put("retryAt", failure.retryAt().toString());
    return node;
  }

  private static ModelAttemptFailure decodeFailure(JsonNode value) {
    ObjectNode node = InvocationJsonSupport.object(value, CONTEXT + " entry");
    InvocationJsonSupport.requireFields(
        node,
        CONTEXT + " entry",
        "attempt",
        "sequence",
        "text",
        "thinking",
        "error",
        "failedAt",
        "retryAt");
    int attempt = InvocationJsonSupport.positiveInt(node, "attempt", CONTEXT + " entry");
    long sequence = InvocationJsonSupport.nonNegativeLong(node, "sequence", CONTEXT + " entry");
    String text = InvocationJsonSupport.text(node, "text", CONTEXT + " entry");
    String thinking = InvocationJsonSupport.text(node, "thinking", CONTEXT + " entry");
    ModelInvocationError error =
        ERRORS.decodeNode(InvocationJsonSupport.required(node, "error", CONTEXT + " entry"));
    Instant failedAt = requiredInstant(node, "failedAt");
    Instant retryAt = requiredInstant(node, "retryAt");
    return new ModelAttemptFailure(attempt, sequence, text, thinking, error, failedAt, retryAt);
  }

  private static Instant requiredInstant(ObjectNode node, String field) {
    String value = InvocationJsonSupport.text(node, field, CONTEXT + " entry");
    try {
      return Instant.parse(value);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          CONTEXT + " entry." + field + " must be an ISO-8601 instant", error);
    }
  }
}
