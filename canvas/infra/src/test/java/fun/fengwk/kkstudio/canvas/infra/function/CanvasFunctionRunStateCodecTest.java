package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned state codec 冻结完整 plan，并严格拒绝 schema、definition 与类型漂移。 */
class CanvasFunctionRunStateCodecTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SOURCE_NODE = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000006");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000007");

  private ObjectMapper mapper;
  private CanvasFunctionRunStateCodec codec;
  private CanvasFunctionDefinition definition;
  private CanvasFunctionFrozenRun run;

  @BeforeEach
  void setUp() {
    mapper = JsonMapper.builder().build();
    codec = new CanvasFunctionRunStateCodec();
    JsonObject schema =
        CanvasJson.parseObject(
            """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["prompt", "source"],
              "properties": {
                "prompt": { "type": "string" },
                "ratio": { "type": "string", "enum": ["16:9", "9:16"], "default": "16:9" },
                "duration": { "type": "integer", "minimum": 4, "maximum": 15, "default": 5 },
                "source": { "type": "resourceReference" }
              }
            }
            """);
    definition =
        CanvasFunctionDefinition.of(
            "test.video",
            "Test Video",
            schema,
            CanvasResourceKind.VIDEO,
            new CanvasFunctionReferencePolicy(
                Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.AUDIO),
                3,
                Map.of(CanvasResourceKind.AUDIO, 1)));
    JsonObject args =
        CanvasJson.parseObject(
            """
            {
              "prompt": "prompt",
              "ratio": "16:9",
              "duration": 5,
              "source": {
                "type": "resource",
                "nodeId": "00000000-0000-0000-0000-000000000003",
                "index": 0
              }
            }
            """);
    run =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            definition,
            args,
            List.of(
                new CanvasFunctionFrozenReference(
                    SOURCE_NODE,
                    0,
                    RESOURCE,
                    BLOB,
                    CanvasResourceKind.IMAGE,
                    "source.png",
                    "image/png",
                    12L,
                    640L,
                    480L,
                    null)),
            List.of(
                new CanvasFunctionFrozenOutput(TARGET, 0, CanvasResourceKind.VIDEO, "output.mp4")),
            CanvasFunctionSubmitState.SUBMITTED,
            "SUBMITTED",
            Map.of("jobId", "job-1", "attempt", 1));
  }

  /** round-trip 编解码冻结计划并提取 stage/functionName，验证状态迁移与 checkpoint 方法。 */
  @Test
  void roundTripsTheTypedFrozenPlan() {
    String json = codec.encode(run);

    assertEquals(run, codec.decode(json, definition));
    assertEquals("SUBMITTED", codec.stage(json));
    assertEquals("test.video", codec.functionName(json));
    assertEquals(json, codec.initial(run));
    assertEquals("POLLING", codec.checkpoint(run, "POLLING", Map.of("jobId", "job-1")).stage());
    assertEquals(
        CanvasFunctionSubmitState.SUBMITTING,
        codec
            .transition(
                run, CanvasFunctionSubmitState.SUBMITTING, "SUBMITTING", Map.of("jobId", "job-2"))
            .submitState());
  }

  /** 拒绝非版本 4、未知根字段、未知计划字段以及与已注册定义不匹配的函数名或输出类型。 */
  @Test
  void rejectsSchemaVersionAndRegistryDrift() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(run));
    root.put("extra", true);
    String unknownField = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(unknownField, definition));

    root.remove("extra");
    root.put("version", 3);
    String unsupportedVersion = root.toString();
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(unsupportedVersion, definition));

    CanvasFunctionDefinition otherDefinition =
        CanvasFunctionDefinition.of(
            "other.video",
            "Other Video",
            definition.argsSchema(),
            CanvasResourceKind.VIDEO,
            definition.referencePolicy());
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(codec.encode(run), otherDefinition));

    CanvasFunctionDefinition imageDefinition =
        CanvasFunctionDefinition.of(
            "test.video",
            "Test Video",
            definition.argsSchema(),
            CanvasResourceKind.IMAGE,
            definition.referencePolicy());
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(codec.encode(run), imageDefinition));
  }

  /** 拒绝非法 manifest、非法 stage 命名、非法 submitState 以及重复字段与超大 adapterState。 */
  @Test
  void rejectsMalformedManifestConfigStageAndDuplicateFields() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(run));
    ObjectNode plan = (ObjectNode) root.get("plan");
    ObjectNode reference = (ObjectNode) ((ArrayNode) plan.get("manifest")).get(0);
    reference.put("sourceIndex", -1);
    String invalidManifest = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidManifest, definition));

    root = (ObjectNode) mapper.readTree(codec.encode(run));
    root.put("stage", "polling");
    String invalidStage = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidStage, definition));

    root = (ObjectNode) mapper.readTree(codec.encode(run));
    ((ObjectNode) root.get("plan")).put("submitState", "INVALID_STATE");
    String invalidSubmitState = root.toString();
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(invalidSubmitState, definition));

    String duplicate =
        codec.encode(run).replaceFirst("\"version\":4", "\"version\":4,\"version\":4");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate, definition));
    assertThrows(IllegalArgumentException.class, () -> codec.stage("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.functionName("{}"));

    // adapterState 超过 64KB 限制
    Map<String, Object> oversized = Map.of("large", "x".repeat(65 * 1024));
    CanvasFunctionFrozenRun oversizedRun =
        new CanvasFunctionFrozenRun(
            run.canvasId(),
            run.nodeId(),
            run.nodeName(),
            run.requestId(),
            run.definition(),
            run.args(),
            run.manifest(),
            run.outputs(),
            run.submitState(),
            run.stage(),
            oversized);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(oversizedRun));
  }

  /** Jackson 3 对空白输入返回 MissingNode，必须显式拒绝，不能当作空对象静默通过。 */
  @Test
  void rejectsBlankMissingNodeInput() {
    for (String blank : List.of("", "   ", "\n\t")) {
      assertThrows(IllegalArgumentException.class, () -> codec.decode(blank, definition));
      assertThrows(IllegalArgumentException.class, () -> codec.stage(blank));
      assertThrows(IllegalArgumentException.class, () -> codec.functionName(blank));
    }
    // JSON 字面量 null 解析为 NullNode 而非 MissingNode，同样不是合法 state。
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null", definition));
  }

  /** 拒绝 plan 内 manifest/outputs 的类型与数量漂移，以及 manifest 成员的 kind/UUID/数值类型漂移。 */
  @Test
  void rejectsPlanShapeAndManifestMemberDrift() {
    ObjectNode root = encodedRoot();
    ((ObjectNode) root.get("plan")).put("manifest", "x");
    String manifestNotArray = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(manifestNotArray, definition));

    root = encodedRoot();
    ((ObjectNode) root.get("plan")).put("outputs", "x");
    String outputsNotArray = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(outputsNotArray, definition));

    root = encodedRoot();
    ((ArrayNode) ((ObjectNode) root.get("plan")).get("outputs")).remove(0);
    String outputsMismatch = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(outputsMismatch, definition));

    root = encodedRoot();
    firstManifestReference(root).put("kind", "NOT_A_KIND");
    String invalidKind = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidKind, definition));

    root = encodedRoot();
    firstManifestReference(root).put("blobId", 5);
    String nonStringUuid = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(nonStringUuid, definition));

    root = encodedRoot();
    firstManifestReference(root).put("sourceNodeId", "not-a-uuid");
    String malformedUuid = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(malformedUuid, definition));

    root = encodedRoot();
    firstManifestReference(root).put("sizeBytes", -1);
    String negativeSize = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(negativeSize, definition));

    root = encodedRoot();
    firstManifestReference(root).put("width", 0);
    String nonPositiveWidth = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(nonPositiveWidth, definition));
  }

  /** 拒绝缺失 plan 字段、空白文本、decode 侧超限 adapterState、超深 args 以及非法 stage/functionName JSON。 */
  @Test
  void rejectsMissingFieldsBlankTextOversizedAdapterStateAndDeepArgs() {
    ObjectNode root = encodedRoot();
    ((ObjectNode) root.get("plan")).remove("outputs");
    String missingOutputs = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missingOutputs, definition));

    root = encodedRoot();
    ((ObjectNode) root.get("plan")).put("nodeName", "");
    String blankNodeName = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(blankNodeName, definition));

    root = encodedRoot();
    ObjectNode oversized = mapper.createObjectNode();
    oversized.put("large", "x".repeat(65 * 1024));
    root.set("adapterState", oversized);
    String oversizedAdapterState = root.toString();
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(oversizedAdapterState, definition));

    // args 超过 Core 的嵌套深度上限：Jackson 允许解析，但严格 args 解析必须拒绝。
    root = encodedRoot();
    ((ObjectNode) root.get("plan")).set("args", nestedObject(CanvasJson.MAX_DEPTH + 1));
    String deepArgs = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(deepArgs, definition));

    assertThrows(IllegalArgumentException.class, () -> codec.functionName("{"));
  }

  private ObjectNode encodedRoot() {
    return (ObjectNode) mapper.readTree(codec.encode(run));
  }

  private static ObjectNode firstManifestReference(ObjectNode root) {
    return (ObjectNode) ((ArrayNode) ((ObjectNode) root.get("plan")).get("manifest")).get(0);
  }

  private ObjectNode nestedObject(int depth) {
    ObjectNode node = mapper.createObjectNode();
    ObjectNode current = node;
    for (int index = 0; index < depth; index++) {
      current = current.putObject("nested");
    }
    return node;
  }
}
