package fun.fengwk.kkstudio.core.studio.function.h3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** H3 adapterState 的唯一 typed 视图。 */
public record H3AdapterState(
    Long seed,
    UUID harnessThreadId,
    String enhancedPrompt,
    Map<UUID, H3UploadedFile> uploads,
    String promptId,
    H3OutputDescriptor output) {

  static final int MAX_ENHANCED_PROMPT_BYTES = 48 * 1024;
  private static final Set<String> FIELDS =
      Set.of("seed", "harnessThreadId", "enhancedPrompt", "uploads", "promptId", "output");

  public H3AdapterState {
    if (seed != null && seed < 0L) {
      throw new IllegalArgumentException("seed must be nonnegative");
    }
    if (enhancedPrompt != null) {
      enhancedPrompt = requireText(enhancedPrompt, "enhancedPrompt");
      if (enhancedPrompt.getBytes(StandardCharsets.UTF_8).length > MAX_ENHANCED_PROMPT_BYTES) {
        throw new IllegalArgumentException(
            "enhancedPrompt must be at most " + MAX_ENHANCED_PROMPT_BYTES + " UTF-8 bytes");
      }
    }
    uploads = Map.copyOf(new LinkedHashMap<>(uploads == null ? Map.of() : uploads));
    promptId = promptId == null ? null : requireText(promptId, "promptId");
  }

  public static H3AdapterState empty() {
    return new H3AdapterState(null, null, null, Map.of(), null, null);
  }

  public static H3AdapterState decode(Map<String, Object> raw, ObjectMapper mapper) {
    Objects.requireNonNull(raw, "raw");
    ObjectNode root = mapper.valueToTree(raw);
    Set<String> unknown = new HashSet<>();
    root.fieldNames()
        .forEachRemaining(
            field -> {
              if (!FIELDS.contains(field)) {
                unknown.add(field);
              }
            });
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("H3 adapterState contains unknown fields: " + unknown);
    }
    Long seed = optionalLong(root, "seed", false);
    UUID harnessThreadId = optionalUuid(root, "harnessThreadId");
    String enhancedPrompt = optionalText(root, "enhancedPrompt");
    String promptId = optionalText(root, "promptId");
    Map<UUID, H3UploadedFile> uploads = decodeUploads(root.get("uploads"));
    H3OutputDescriptor output = decodeOutput(root.get("output"));
    return new H3AdapterState(seed, harnessThreadId, enhancedPrompt, uploads, promptId, output);
  }

  public Map<String, Object> encode() {
    Map<String, Object> state = new LinkedHashMap<>();
    put(state, "seed", seed);
    put(state, "harnessThreadId", harnessThreadId);
    put(state, "enhancedPrompt", enhancedPrompt);
    if (!uploads.isEmpty()) {
      Map<String, Object> values = new LinkedHashMap<>();
      for (Map.Entry<UUID, H3UploadedFile> entry : uploads.entrySet()) {
        H3UploadedFile value = entry.getValue();
        values.put(
            entry.getKey().toString(),
            Map.of(
                "name", value.name(),
                "subfolder", value.subfolder(),
                "type", value.type()));
      }
      state.put("uploads", values);
    }
    put(state, "promptId", promptId);
    if (output != null) {
      state.put(
          "output",
          Map.of(
              "filename", output.filename(),
              "subfolder", output.subfolder(),
              "type", output.type()));
    }
    return Map.copyOf(state);
  }

  public H3AdapterState withSeed(long value) {
    return new H3AdapterState(value, harnessThreadId, enhancedPrompt, uploads, promptId, output);
  }

  public H3AdapterState withHarnessThreadId(UUID value) {
    return new H3AdapterState(seed, value, enhancedPrompt, uploads, promptId, output);
  }

  public H3AdapterState withEnhancedPrompt(String value) {
    return new H3AdapterState(seed, harnessThreadId, value, uploads, promptId, output);
  }

  public H3AdapterState withUpload(UUID resourceId, H3UploadedFile value) {
    Objects.requireNonNull(resourceId, "resourceId");
    Map<UUID, H3UploadedFile> next = new LinkedHashMap<>(uploads);
    next.put(resourceId, Objects.requireNonNull(value, "value"));
    return new H3AdapterState(seed, harnessThreadId, enhancedPrompt, next, promptId, output);
  }

  public H3AdapterState withPromptId(String value) {
    return new H3AdapterState(seed, harnessThreadId, enhancedPrompt, uploads, value, output);
  }

  public H3AdapterState withOutput(H3OutputDescriptor value) {
    return new H3AdapterState(seed, harnessThreadId, enhancedPrompt, uploads, promptId, value);
  }

  private static Map<UUID, H3UploadedFile> decodeUploads(JsonNode value) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException("uploads must be an object");
    }
    Map<UUID, H3UploadedFile> uploads = new LinkedHashMap<>();
    object
        .fields()
        .forEachRemaining(
            entry -> {
              UUID resourceId;
              try {
                resourceId = UUID.fromString(entry.getKey());
              } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                    "uploads key must be a canonical UUID Resource id", error);
              }
              ObjectNode file = object(entry.getValue(), "uploads." + entry.getKey());
              requireExact(file, Set.of("name", "subfolder", "type"), "uploads." + entry.getKey());
              uploads.put(
                  resourceId,
                  new H3UploadedFile(
                      text(file, "name"), text(file, "subfolder"), text(file, "type")));
            });
    return uploads;
  }

  private static H3OutputDescriptor decodeOutput(JsonNode value) {
    if (value == null) {
      return null;
    }
    ObjectNode output = object(value, "output");
    requireExact(output, Set.of("filename", "subfolder", "type"), "output");
    return new H3OutputDescriptor(
        text(output, "filename"), string(output, "subfolder"), text(output, "type"));
  }

  private static Long optionalLong(ObjectNode root, String field, boolean positive) {
    JsonNode value = root.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    long number = value.longValue();
    if ((positive && number <= 0L) || (!positive && number < 0L)) {
      throw new IllegalArgumentException(
          field + (positive ? " must be positive" : " must be nonnegative"));
    }
    return number;
  }

  private static UUID optionalUuid(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a canonical UUID string");
    }
    String raw = value.textValue();
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) {
        throw new IllegalArgumentException(field + " must be a canonical UUID string");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID string", error);
    }
  }

  private static String optionalText(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    return value == null ? null : requireTextValue(value, field);
  }

  private static String text(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return requireTextValue(value, field);
  }

  private static String string(ObjectNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static String requireTextValue(JsonNode value, String field) {
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static ObjectNode object(JsonNode value, String field) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(field + " must be an object");
    }
    return object;
  }

  private static void requireExact(ObjectNode node, Set<String> fields, String path) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(fields)) {
      throw new IllegalArgumentException(path + " must contain exactly " + fields);
    }
  }

  private static void put(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
