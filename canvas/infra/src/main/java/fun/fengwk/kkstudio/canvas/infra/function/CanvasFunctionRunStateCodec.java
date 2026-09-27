package fun.fengwk.kkstudio.canvas.infra.function;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

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
public final class CanvasFunctionRunStateCodec implements CanvasFunctionRunStateCodecPort {

  public static final int VERSION = 3;
  public static final int MAX_ADAPTER_STATE_BYTES = 64 * 1024;

  private static final Pattern STAGE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Set<String> ROOT_FIELDS = Set.of("version", "plan", "stage", "adapterState");
  private static final Set<String> PLAN_FIELDS =
      Set.of(
          "canvasId",
          "nodeId",
          "nodeName",
          "requestId",
          "functionName",
          "outputKind",
          "args",
          "manifest",
          "outputName",
          "targetResourceId",
          "submitState");
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

  public CanvasFunctionRunStateCodec(ObjectMapper objectMapper) {
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  @Override
  public String initial(CanvasFunctionFrozenRun run) {
    return encode(run);
  }

  @Override
  public String encode(CanvasFunctionFrozenRun run) {
    validateStage(run.stage());
    ObjectNode root = mapper.createObjectNode();
    root.put("version", VERSION);
    ObjectNode plan = root.putObject("plan");
    plan.put("canvasId", run.canvasId().toString());
    plan.put("nodeId", run.nodeId().toString());
    plan.put("nodeName", run.nodeName());
    plan.put("requestId", run.requestId().toString());
    plan.put("functionName", run.definition().name());
    plan.put("outputKind", run.definition().outputKind().name());
    plan.set("args", argsNode(run.args()));
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
    plan.put("submitState", run.submitState().name());
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

  @Override
  public CanvasFunctionFrozenRun decode(String json, CanvasFunctionDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    try {
      ObjectNode root = object(mapper.readTree(json), "state");
      requireExactFields(root, ROOT_FIELDS, "state");
      JsonNode version = required(root, "version");
      if (!version.isIntegralNumber() || version.intValue() != VERSION) {
        throw invalid("state.version must be " + VERSION);
      }
      ObjectNode plan = object(required(root, "plan"), "state.plan");
      requireExactFields(plan, PLAN_FIELDS, "state.plan");
      String functionName = text(plan, "functionName");
      if (!definition.name().equals(functionName)) {
        throw invalid("state functionName does not match the registered function");
      }
      CanvasResourceKind outputKind = enumKind(text(plan, "outputKind"), "outputKind");
      if (outputKind != definition.outputKind()) {
        throw invalid("state outputKind does not match the registered function");
      }
      // args 在 state 里是 JSON object（与 adapterState 一致），复用 Core 的严格解析与上限校验。
      JsonObject args =
          args(object(required(plan, "args"), "state.plan.args").toString(), "state.plan.args");
      String stage = text(root, "stage");
      validateStage(stage);
      CanvasFunctionSubmitState submitState = submitState(text(plan, "submitState"));
      ObjectNode adapterStateNode = object(required(root, "adapterState"), "state.adapterState");
      if (mapper.writeValueAsBytes(adapterStateNode).length > MAX_ADAPTER_STATE_BYTES) {
        throw invalid("state.adapterState exceeds maximum size");
      }
      Map<String, Object> adapterState =
          mapper.convertValue(
              adapterStateNode, new TypeReference<LinkedHashMap<String, Object>>() {});
      return new CanvasFunctionFrozenRun(
          uuid(plan, "canvasId"),
          uuid(plan, "nodeId"),
          text(plan, "nodeName"),
          uuid(plan, "requestId"),
          definition,
          args,
          decodeManifest(required(plan, "manifest")),
          text(plan, "outputName"),
          uuid(plan, "targetResourceId"),
          submitState,
          stage,
          adapterState);
    } catch (JsonProcessingException exception) {
      throw invalid("FunctionRun stateJson must be valid typed JSON", exception);
    }
  }

  @Override
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

  @Override
  public String functionName(String json) {
    try {
      ObjectNode root = object(mapper.readTree(json), "state");
      ObjectNode plan = object(required(root, "plan"), "state.plan");
      return text(plan, "functionName");
    } catch (JsonProcessingException exception) {
      throw invalid("FunctionRun stateJson must be valid typed JSON", exception);
    }
  }

  @Override
  public CanvasFunctionFrozenRun transition(
      CanvasFunctionFrozenRun run,
      CanvasFunctionSubmitState submitState,
      String stage,
      Map<String, Object> adapterState) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(submitState, "submitState");
    validateStage(stage);
    return new CanvasFunctionFrozenRun(
        run.canvasId(),
        run.nodeId(),
        run.nodeName(),
        run.requestId(),
        run.definition(),
        run.args(),
        run.manifest(),
        run.outputName(),
        run.targetResourceId(),
        submitState,
        stage,
        adapterState);
  }

  private JsonNode argsNode(JsonObject args) {
    try {
      return mapper.readTree(args.write());
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("args must be JSON serializable", exception);
    }
  }

  private JsonObject args(String json, String field) {
    try {
      return CanvasJson.parseObject(json);
    } catch (IllegalArgumentException error) {
      throw invalid(field + " must be a strict JSON object", error);
    }
  }

  private static CanvasFunctionSubmitState submitState(String value) {
    try {
      return CanvasFunctionSubmitState.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw invalid("state.plan.submitState is invalid", exception);
    }
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
