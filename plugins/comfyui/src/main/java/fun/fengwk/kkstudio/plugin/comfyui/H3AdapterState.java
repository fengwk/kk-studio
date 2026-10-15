package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    List<H3OutputDescriptor> outputs) {

  static final int MAX_ENHANCED_PROMPT_BYTES = 48 * 1024;
  private static final Set<String> FIELDS =
      Set.of("seed", "harnessThreadId", "enhancedPrompt", "uploads", "promptId", "outputs");

  /**
   * 自有纯 mapper：rawMap → typed state 只做 valueToTree，不继承宿主 HTTP mapper 的任何 module/profile（如
   * Long→String serializer），因此合法 Long seed 不会被写成字符串节点。
   */
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

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
    outputs = outputs == null ? null : List.copyOf(outputs);
  }

  public static H3AdapterState empty() {
    return new H3AdapterState(null, null, null, Map.of(), null, null);
  }

  public static H3AdapterState decode(Map<String, Object> raw) {
    Objects.requireNonNull(raw, "raw");
    ObjectNode root = MAPPER.valueToTree(raw);
    Set<String> unknown = new HashSet<>();
    for (String field : root.propertyNames()) {
      if (!FIELDS.contains(field)) {
        unknown.add(field);
      }
    }
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("H3 adapterState contains unknown fields: " + unknown);
    }
    Long seed = optionalLong(root, "seed", false);
    UUID harnessThreadId = optionalUuid(root, "harnessThreadId");
    String enhancedPrompt = optionalText(root, "enhancedPrompt");
    String promptId = optionalText(root, "promptId");
    Map<UUID, H3UploadedFile> uploads = decodeUploads(root.get("uploads"));
    List<H3OutputDescriptor> outputs = decodeOutputs(root.get("outputs"));
    return new H3AdapterState(seed, harnessThreadId, enhancedPrompt, uploads, promptId, outputs);
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
    if (outputs != null) {
      List<Map<String, Object>> values = new ArrayList<>(outputs.size());
      for (H3OutputDescriptor output : outputs) {
        values.add(
            Map.of(
                "filename", output.filename(),
                "subfolder", output.subfolder(),
                "type", output.type()));
      }
      state.put("outputs", values);
    }
    return Map.copyOf(state);
  }

  public H3AdapterState withSeed(long value) {
    return new H3AdapterState(value, harnessThreadId, enhancedPrompt, uploads, promptId, outputs);
  }

  public H3AdapterState withHarnessThreadId(UUID value) {
    return new H3AdapterState(seed, value, enhancedPrompt, uploads, promptId, outputs);
  }

  public H3AdapterState withEnhancedPrompt(String value) {
    return new H3AdapterState(seed, harnessThreadId, value, uploads, promptId, outputs);
  }

  public H3AdapterState withUpload(UUID resourceId, H3UploadedFile value) {
    Objects.requireNonNull(resourceId, "resourceId");
    Map<UUID, H3UploadedFile> next = new LinkedHashMap<>(uploads);
    next.put(resourceId, Objects.requireNonNull(value, "value"));
    return new H3AdapterState(seed, harnessThreadId, enhancedPrompt, next, promptId, outputs);
  }

  public H3AdapterState withPromptId(String value) {
    return new H3AdapterState(seed, harnessThreadId, enhancedPrompt, uploads, value, outputs);
  }

  public H3AdapterState withOutputs(List<H3OutputDescriptor> value) {
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
    for (Map.Entry<String, JsonNode> entry : object.properties()) {
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
          new H3UploadedFile(text(file, "name"), text(file, "subfolder"), text(file, "type")));
    }
    return uploads;
  }

  private static List<H3OutputDescriptor> decodeOutputs(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException("outputs must be an array");
    }
    List<H3OutputDescriptor> outputs = new ArrayList<>(array.size());
    for (int index = 0; index < array.size(); index++) {
      ObjectNode output = object(array.get(index), "outputs[" + index + "]");
      requireExact(output, Set.of("filename", "subfolder", "type"), "outputs[" + index + "]");
      outputs.add(
          new H3OutputDescriptor(
              text(output, "filename"), string(output, "subfolder"), text(output, "type")));
    }
    return outputs;
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

  private static String text(ObjectNode node, String field) {
    return requireTextValue(node.get(field), field);
  }

  private static String string(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a string");
    }
    return value.textValue();
  }

  private static String requireTextValue(JsonNode value, String field) {
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static ObjectNode object(JsonNode value, String field) {
    if (value == null || !(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(field + " must be an object");
    }
    return object;
  }

  private static void requireExact(ObjectNode node, Set<String> fields, String path) {
    Set<String> actual = new HashSet<>(node.propertyNames());
    if (!actual.equals(fields)) {
      throw new IllegalArgumentException(
          path + " must declare exactly " + fields + ", got " + actual);
    }
  }

  private static void put(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value instanceof UUID ? value.toString() : value);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
