package fun.fengwk.kkstudio.core.studio.function;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.PromptSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionParameterDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Function config 的唯一严格 parser 与 canonical encoder。 */
@Component
public final class CanvasFunctionConfigCodec {

  private static final Set<String> ROOT_FIELDS = Set.of("prompt", "parameters");
  private static final Set<String> PROMPT_FIELDS = Set.of("segments");
  private static final Set<String> TEXT_FIELDS = Set.of("type", "text");
  private static final Set<String> REFERENCE_FIELDS = Set.of("type", "nodeId", "index");

  private final ObjectMapper mapper;

  public CanvasFunctionConfigCodec(ObjectMapper objectMapper) {
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 按 descriptor 严格校验、canonicalize 参数并补齐默认值。 */
  public CanvasFunctionConfig decode(String json, CanvasFunctionModel model) {
    if (json == null || json.isBlank()) {
      throw invalid("function config must not be blank");
    }
    try {
      JsonNode rootValue = mapper.readTree(json);
      ObjectNode root = object(rootValue, "config");
      requireExactFields(root, ROOT_FIELDS, "config");
      ObjectNode prompt = object(required(root, "prompt"), "prompt");
      requireExactFields(prompt, PROMPT_FIELDS, "prompt");
      List<PromptSegment> segments = decodeSegments(required(prompt, "segments"));
      Map<String, Object> parameters =
          decodeParameters(required(root, "parameters"), Objects.requireNonNull(model, "model"));
      return new CanvasFunctionConfig(segments, parameters);
    } catch (JsonProcessingException exception) {
      throw invalid("function config must be valid JSON", exception);
    }
  }

  public ObjectNode encodeNode(CanvasFunctionConfig config) {
    Objects.requireNonNull(config, "config");
    ObjectNode root = mapper.createObjectNode();
    ObjectNode prompt = root.putObject("prompt");
    ArrayNode segments = prompt.putArray("segments");
    for (PromptSegment segment : config.segments()) {
      ObjectNode item = segments.addObject();
      switch (segment) {
        case TextSegment text -> {
          item.put("type", "TEXT");
          item.put("text", text.text());
        }
        case ReferenceSegment reference -> {
          item.put("type", "REFERENCE");
          item.put("nodeId", reference.nodeId().toString());
          item.put("index", reference.index());
        }
      }
    }
    ObjectNode parameters = root.putObject("parameters");
    for (Map.Entry<String, Object> entry : config.parameters().entrySet()) {
      switch (entry.getValue()) {
        case String value -> parameters.put(entry.getKey(), value);
        case Integer value -> parameters.put(entry.getKey(), value);
        default -> throw new IllegalArgumentException(
            "unsupported function parameter value: " + entry.getKey());
      }
    }
    return root;
  }

  public String encode(CanvasFunctionConfig config) {
    try {
      return mapper.writeValueAsString(encodeNode(config));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("cannot encode function config", exception);
    }
  }

  public List<ReferenceSegment> uniqueReferences(CanvasFunctionConfig config) {
    LinkedHashMap<ReferenceKey, ReferenceSegment> unique = new LinkedHashMap<>();
    for (PromptSegment segment : config.segments()) {
      if (segment instanceof ReferenceSegment reference) {
        unique.putIfAbsent(new ReferenceKey(reference.nodeId(), reference.index()), reference);
      }
    }
    return List.copyOf(unique.values());
  }

  private List<PromptSegment> decodeSegments(JsonNode value) {
    ArrayNode raw = array(value, "prompt.segments");
    if (raw.isEmpty()) {
      throw invalid("prompt.segments must not be empty");
    }
    List<PromptSegment> canonical = new ArrayList<>();
    StringBuilder visibleText = new StringBuilder();
    for (int index = 0; index < raw.size(); index++) {
      ObjectNode segment = object(raw.get(index), "prompt.segments[" + index + "]");
      JsonNode typeValue = required(segment, "type");
      if (!typeValue.isTextual()) {
        throw invalid("prompt.segments[" + index + "].type must be text");
      }
      switch (typeValue.textValue()) {
        case "TEXT" -> decodeText(segment, index, canonical, visibleText);
        case "REFERENCE" -> canonical.add(decodeReference(segment, index));
        default -> throw invalid("prompt.segments[" + index + "].type must be TEXT or REFERENCE");
      }
    }
    if (visibleText.toString().isBlank()) {
      throw invalid("prompt visible text must not be blank");
    }
    if (canonical.isEmpty()) {
      throw invalid("prompt.segments must not be empty after canonicalization");
    }
    return List.copyOf(canonical);
  }

  private void decodeText(
      ObjectNode segment, int index, List<PromptSegment> canonical, StringBuilder visibleText) {
    requireExactFields(segment, TEXT_FIELDS, "prompt.segments[" + index + "]");
    JsonNode textValue = required(segment, "text");
    if (!textValue.isTextual()) {
      throw invalid("prompt.segments[" + index + "].text must be text");
    }
    String text = textValue.textValue();
    if (text.isEmpty()) {
      return;
    }
    visibleText.append(text);
    int lastIndex = canonical.size() - 1;
    if (lastIndex >= 0 && canonical.get(lastIndex) instanceof TextSegment previous) {
      canonical.set(lastIndex, new TextSegment(previous.text() + text));
    } else {
      canonical.add(new TextSegment(text));
    }
  }

  private ReferenceSegment decodeReference(ObjectNode segment, int index) {
    requireExactFields(segment, REFERENCE_FIELDS, "prompt.segments[" + index + "]");
    JsonNode nodeIdValue = required(segment, "nodeId");
    if (!nodeIdValue.isTextual()) {
      throw invalid("prompt.segments[" + index + "].nodeId must be a canonical UUID string");
    }
    UUID nodeId;
    try {
      nodeId = UUID.fromString(nodeIdValue.textValue());
    } catch (IllegalArgumentException exception) {
      throw invalid(
          "prompt.segments[" + index + "].nodeId must be a canonical UUID string", exception);
    }
    JsonNode indexValue = required(segment, "index");
    if (!indexValue.isIntegralNumber()
        || !indexValue.canConvertToInt()
        || indexValue.intValue() < 0) {
      throw invalid("prompt.segments[" + index + "].index must be a nonnegative integer");
    }
    return new ReferenceSegment(nodeId, indexValue.intValue());
  }

  private Map<String, Object> decodeParameters(JsonNode value, CanvasFunctionModel model) {
    ObjectNode parameters = object(value, "parameters");
    Map<String, CanvasFunctionParameterDefinition> definitions = new LinkedHashMap<>();
    for (CanvasFunctionParameterDefinition definition : model.parameters()) {
      definitions.put(definition.key(), definition);
    }
    Set<String> unknown = new LinkedHashSet<>();
    parameters
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!definitions.containsKey(key)) {
                unknown.add(key);
              }
            });
    if (!unknown.isEmpty()) {
      throw invalid("parameters contains unknown keys: " + unknown);
    }

    Map<String, Object> canonical = new LinkedHashMap<>();
    for (CanvasFunctionParameterDefinition definition : model.parameters()) {
      JsonNode raw = parameters.get(definition.key());
      if (raw == null) {
        if (definition.defaultValue() != null) {
          canonical.put(definition.key(), definition.defaultValue());
        } else if (definition.required()) {
          throw invalid("parameters." + definition.key() + " is required");
        }
        continue;
      }
      if (raw.isNull()) {
        throw invalid("parameters." + definition.key() + " must not be null");
      }
      canonical.put(definition.key(), decodeParameter(definition, raw));
    }
    return canonical;
  }

  private Object decodeParameter(CanvasFunctionParameterDefinition definition, JsonNode raw) {
    return switch (definition.type()) {
      case ENUM -> {
        if (!raw.isTextual() || !definition.options().contains(raw.textValue())) {
          throw invalid(
              "parameters." + definition.key() + " must be one of " + definition.options());
        }
        yield raw.textValue();
      }
      case INTEGER -> {
        if (!raw.isIntegralNumber() || !raw.canConvertToInt()) {
          throw invalid("parameters." + definition.key() + " must be an integer");
        }
        int value = raw.intValue();
        if (value < definition.min() || value > definition.max()) {
          throw invalid(
              "parameters."
                  + definition.key()
                  + " must be between "
                  + definition.min()
                  + " and "
                  + definition.max());
        }
        yield value;
      }
    };
  }

  private static JsonNode required(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw invalid(field + " is required and must not be null");
    }
    return value;
  }

  private static ObjectNode object(JsonNode value, String path) {
    if (!(value instanceof ObjectNode object)) {
      throw invalid(path + " must be an object");
    }
    return object;
  }

  private static ArrayNode array(JsonNode value, String path) {
    if (!(value instanceof ArrayNode array)) {
      throw invalid(path + " must be an array");
    }
    return array;
  }

  private static void requireExactFields(ObjectNode node, Set<String> allowed, String path) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    Set<String> unknown = new HashSet<>(actual);
    unknown.removeAll(allowed);
    if (!unknown.isEmpty()) {
      throw invalid(path + " contains unknown fields: " + unknown);
    }
    Set<String> missing = new HashSet<>(allowed);
    missing.removeAll(actual);
    if (!missing.isEmpty()) {
      throw invalid(path + " is missing fields: " + missing);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }

  private record ReferenceKey(UUID nodeId, int index) {}
}
