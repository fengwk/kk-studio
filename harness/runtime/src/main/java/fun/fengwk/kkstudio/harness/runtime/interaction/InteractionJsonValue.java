package fun.fengwk.kkstudio.harness.runtime.interaction;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Shared strict validation for raw JSON values stored by an Interaction. */
final class InteractionJsonValue {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private InteractionJsonValue() {}

  static String validate(String json, String name) {
    Objects.requireNonNull(json, name);
    if (json.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    try {
      if (MAPPER.readTree(json) == null) {
        throw new IllegalArgumentException(name + " must contain one JSON value");
      }
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("invalid " + name + " JSON", error);
    }
    return json;
  }
}
