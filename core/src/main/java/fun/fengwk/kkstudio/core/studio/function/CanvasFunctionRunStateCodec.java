package fun.fengwk.kkstudio.core.studio.function;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** FunctionRun typed/versioned stateJson 的唯一 codec。 */
@Component
public final class CanvasFunctionRunStateCodec {

  public static final int VERSION = 2;
  public static final int MAX_ADAPTER_STATE_BYTES = 64 * 1024;

  private static final Pattern STAGE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Set<String> ROOT_FIELDS = Set.of("version", "plan", "stage", "adapterState");
  private static final Set<String> PLAN_FIELDS =
      Set.of(
          "canvasId",
          "nodeId",
          "nodeName",
          "requestId",
          "modelKey",
          "outputKind",
          "config",
          "manifest",
          "outputName",
          "targetResourceId");
  private static final Set<String> REFERENCE_FIELDS =
      Set.of(
          "sourceNodeId",
          "sourceIndex",
          "resourceId",
          "blobId",
          "kind",
          "name",
          "mediaType",
          "sizeBytes",
          "width",
          "height",
          "durationMs");

  private final ObjectMapper mapper;
  private final CanvasFunctionConfigCodec configCodec;

  public CanvasFunctionRunStateCodec(
      ObjectMapper objectMapper, CanvasFunctionConfigCodec configCodec) {
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
  }

  public String initial(CanvasFunctionFrozenRun run) {
    return encode(run);
  }

  public String encode(CanvasFunctionFrozenRun run) {
    validateStage(run.stage());
    ObjectNode root = mapper.createObjectNode();
    root.put("version", VERSION);
    ObjectNode plan = root.putObject("plan");
    plan.put("canvasId", run.canvasId().toString());
    plan.put("nodeId", run.nodeId().toString());
    plan.put("nodeName", run.nodeName());
    plan.put("requestId", run.requestId().toString());
    plan.put("modelKey", run.model().key());
    plan.put("outputKind", run.model().outputKind().name());
    plan.set("config", configCodec.encodeNode(run.config()));
    ArrayNode manifest = plan.putArray("manifest");
    for (CanvasFunctionFrozenReference reference : run.manifest()) {
      ObjectNode item = manifest.addObject();
      item.put("sourceNodeId", reference.sourceNodeId().toString());
      item.put("sourceIndex", reference.sourceIndex());
      item.put("resourceId", reference.resourceId().toString());
      item.put("blobId", reference.blobId().toString());
      item.put("kind", reference.kind().name());
      item.put("name", reference.name());
      item.put("mediaType", reference.mediaType());
      item.put("sizeBytes", reference.sizeBytes());
      item.put("width", reference.width());
      item.put("height", reference.height());
      item.put("durationMs", reference.durationMs());
    }
    plan.put("outputName", run.outputName());
    plan.put("targetResourceId", run.targetResourceId().toString());
    root.put("stage", run.stage());
    JsonNode adapterState = mapper.valueToTree(run.adapterState());
    if (!adapterState.isObject()) {
      throw new IllegalArgumentException("adapterState must be a JSON object");
    }
    root.set("adapterState", adapterState);
    try {
      byte[] adapterBytes = mapper.writeValueAsBytes(adapterState);
      if (adapterBytes.length > MAX_ADAPTER_STATE_BYTES) {
        throw new IllegalArgumentException(
            "adapterState must be at most " + MAX_ADAPTER_STATE_BYTES + " bytes");
      }
      return mapper.writeValueAsString(root);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("adapterState must be JSON serializable", exception);
    }
  }

  public CanvasFunctionFrozenRun decode(String json, CanvasFunctionModel model) {
    try {
      ObjectNode root = object(mapper.readTree(json), "state");
      requireExactFields(root, ROOT_FIELDS, "state");
      JsonNode version = required(root, "version");
      if (!version.isIntegralNumber() || version.intValue() != VERSION) {
        throw invalid("state.version must be " + VERSION);
      }
      ObjectNode plan = object(required(root, "plan"), "state.plan");
      requireExactFields(plan, PLAN_FIELDS, "state.plan");
      String modelKey = text(plan, "modelKey");
      if (!model.key().equals(modelKey)) {
        throw invalid("state modelKey does not match registry model");
      }
      CanvasResourceKind outputKind = enumKind(text(plan, "outputKind"), "outputKind");
      if (outputKind != model.outputKind()) {
        throw invalid("state outputKind does not match registry model");
      }
      String stage = text(root, "stage");
      validateStage(stage);
      ObjectNode adapterStateNode = object(required(root, "adapterState"), "state.adapterState");
      if (mapper.writeValueAsBytes(adapterStateNode).length > MAX_ADAPTER_STATE_BYTES) {
        throw invalid("state.adapterState exceeds maximum size");
      }
      Map<String, Object> adapterState =
          mapper.convertValue(
              adapterStateNode, new TypeReference<LinkedHashMap<String, Object>>() {});
      String configJson = mapper.writeValueAsString(required(plan, "config"));
      CanvasFunctionConfig config = configCodec.decode(configJson, model);
      return new CanvasFunctionFrozenRun(
          uuid(plan, "canvasId"),
          uuid(plan, "nodeId"),
          text(plan, "nodeName"),
          uuid(plan, "requestId"),
          model,
          config,
          decodeManifest(required(plan, "manifest")),
          text(plan, "outputName"),
          uuid(plan, "targetResourceId"),
          stage,
          adapterState);
    } catch (JsonProcessingException exception) {
      throw invalid("FunctionRun stateJson must be valid typed JSON", exception);
    }
  }

  public String stage(String json) {
    try {
      ObjectNode root = object(mapper.readTree(json), "state");
      String stage = text(root, "stage");
      validateStage(stage);
      return stage;
    } catch (JsonProcessingException exception) {
      throw invalid("FunctionRun stateJson must be valid typed JSON", exception);
    }
  }

  public String modelKey(String json) {
    try {
      ObjectNode root = object(mapper.readTree(json), "state");
      ObjectNode plan = object(required(root, "plan"), "state.plan");
      return text(plan, "modelKey");
    } catch (JsonProcessingException exception) {
      throw invalid("FunctionRun stateJson must be valid typed JSON", exception);
    }
  }

  public CanvasFunctionFrozenRun checkpoint(
      CanvasFunctionFrozenRun run, String stage, Map<String, Object> adapterState) {
    validateStage(stage);
    return new CanvasFunctionFrozenRun(
        run.canvasId(),
        run.nodeId(),
        run.nodeName(),
        run.requestId(),
        run.model(),
        run.config(),
        run.manifest(),
        run.outputName(),
        run.targetResourceId(),
        stage,
        adapterState);
  }

  private List<CanvasFunctionFrozenReference> decodeManifest(JsonNode value) {
    if (!(value instanceof ArrayNode array)) {
      throw invalid("state.plan.manifest must be an array");
    }
    List<CanvasFunctionFrozenReference> manifest = new ArrayList<>();
    for (int index = 0; index < array.size(); index++) {
      ObjectNode item = object(array.get(index), "state.plan.manifest[" + index + "]");
      requireExactFields(item, REFERENCE_FIELDS, "state.plan.manifest[" + index + "]");
      manifest.add(
          new CanvasFunctionFrozenReference(
              uuid(item, "sourceNodeId"),
              nonnegativeInt(item, "sourceIndex"),
              uuid(item, "resourceId"),
              uuid(item, "blobId"),
              enumKind(text(item, "kind"), "kind"),
              text(item, "name"),
              text(item, "mediaType"),
              nonnegativeLong(item, "sizeBytes"),
              nullablePositiveLong(item, "width"),
              nullablePositiveLong(item, "height"),
              nullablePositiveLong(item, "durationMs")));
    }
    return List.copyOf(manifest);
  }

  public static void validateStage(String stage) {
    if (stage == null || !STAGE.matcher(stage).matches()) {
      throw invalid("stage must match " + STAGE.pattern());
    }
  }

  private static CanvasResourceKind enumKind(String value, String field) {
    try {
      return CanvasResourceKind.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw invalid(field + " is invalid", exception);
    }
  }

  private static UUID uuid(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual()) {
      throw invalid(field + " must be a canonical UUID string");
    }
    try {
      return UUID.fromString(value.textValue());
    } catch (IllegalArgumentException exception) {
      throw invalid(field + " must be a canonical UUID string", exception);
    }
  }

  private static long nonnegativeLong(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0L) {
      throw invalid(field + " must be a nonnegative integer");
    }
    return value.longValue();
  }

  private static Long nullablePositiveLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0L) {
      throw invalid(field + " must be null or a positive integer");
    }
    return value.longValue();
  }

  private static int nonnegativeInt(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw invalid(field + " must be a nonnegative integer");
    }
    return value.intValue();
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw invalid(field + " must be non-blank text");
    }
    return value.textValue();
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
}
