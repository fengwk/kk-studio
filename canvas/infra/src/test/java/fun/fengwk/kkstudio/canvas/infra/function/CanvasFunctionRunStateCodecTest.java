package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** State codec 冻结完整函数定义，并严格拒绝 schema 与类型漂移。 */
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
  private CanvasFunctionBinding binding;
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
    binding = new CanvasFunctionBinding(definition);
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
            binding,
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

  /** round-trip 编解码完整定义与冻结计划，并覆盖 stage/functionName/transition。 */
  @Test
  void roundTripsTheTypedFrozenPlan() {
    String json = codec.encode(run);
    CanvasFunctionFrozenRun decoded = codec.decode(json);

    assertEquals(run, decoded);
    assertEquals(definition, decoded.definition());
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

    // 线文本必须完整携带定义，而不是只留名称或摘要。
    assertTrue(json.contains("\"description\":\"Test Video\""));
    assertTrue(json.contains("\"maxReferences\":3"));
  }

  /** 未类型化 checkpoint 数字也须保留 BigDecimal 精度、scale、极值与宽整数。 */
  @Test
  void preservesExactNumbersInAdapterStateAcrossDecodeAndReencode() {
    Map<String, Object> state =
        Map.of(
            "precise", new BigDecimal("0.123456789012345678901234567890"),
            "scaled", new BigDecimal("1.23000"),
            "huge", new BigDecimal("1e1000"),
            "tiny", new BigDecimal("1e-1000"),
            "wide", new BigInteger("9007199254740993123456789"));
    CanvasFunctionFrozenRun checkpoint = codec.checkpoint(run, "POLLING", state);
    CanvasFunctionFrozenRun decoded = codec.decode(codec.encode(checkpoint));

    for (String field : List.of("precise", "scaled", "huge", "tiny")) {
      assertInstanceOf(BigDecimal.class, decoded.adapterState().get(field));
      assertEquals(state.get(field), decoded.adapterState().get(field), field);
    }
    assertEquals(state.get("wide"), decoded.adapterState().get("wide"));
    assertEquals(decoded, codec.decode(codec.encode(decoded)));
  }

  /** 拒绝未知字段与损坏的完整定义。 */
  @Test
  void rejectsSchemaAndDefinitionDrift() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(run));
    root.put("extra", true);
    String unknownField = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(unknownField));

    root = encodedRoot();
    ObjectNode definitionNode = (ObjectNode) firstPlan(root).get("definition");
    definitionNode.put("name", "");
    String blankName = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(blankName));

    root = encodedRoot();
    ((ObjectNode) firstPlan(root).get("definition")).remove("referencePolicy");
    String missingPolicy = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missingPolicy));

    root = encodedRoot();
    ((ObjectNode) firstPlan(root).get("definition")).put("description", " ");
    String blankDescription = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(blankDescription));
  }

  /** 冻结 plan 的输出槽位必须与持久定义逐位一致：数量、index、kind 任一漂移都拒绝。 */
  @Test
  void rejectsFrozenOutputPlanDriftAgainstPersistedDefinition() throws Exception {
    ObjectNode root = encodedRoot();
    ((ArrayNode) firstPlan(root).get("outputs")).remove(0);
    String missingOutputSlot = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missingOutputSlot));

    root = encodedRoot();
    ((ObjectNode) ((ArrayNode) firstPlan(root).get("outputs")).get(0)).put("kind", "IMAGE");
    String kindDrift = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(kindDrift));

    root = encodedRoot();
    ((ObjectNode) ((ArrayNode) firstPlan(root).get("outputs")).get(0)).put("index", 1);
    String indexDrift = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(indexDrift));

    root = encodedRoot();
    ((ArrayNode) firstPlan(root).get("definition").get("outputs")).remove(0);
    String definitionPlanDrift = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(definitionPlanDrift));
  }

  /** 拒绝非法 manifest、非法 stage 命名、非法 submitState 以及重复字段与超大 adapterState。 */
  @Test
  void rejectsMalformedManifestConfigStageAndDuplicateFields() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(run));
    ObjectNode plan = (ObjectNode) root.get("plan");
    ObjectNode reference = (ObjectNode) ((ArrayNode) plan.get("manifest")).get(0);
    reference.put("sourceIndex", -1);
    String invalidManifest = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidManifest));

    root = (ObjectNode) mapper.readTree(codec.encode(run));
    root.put("stage", "polling");
    String invalidStage = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidStage));

    root = (ObjectNode) mapper.readTree(codec.encode(run));
    ((ObjectNode) root.get("plan")).put("submitState", "INVALID_STATE");
    String invalidSubmitState = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidSubmitState));

    String duplicate = codec.encode(run).replaceFirst("\\{\"plan\":", "{\"stage\":\"X\",\"plan\":");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
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
            run.binding(),
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
      assertThrows(IllegalArgumentException.class, () -> codec.decode(blank));
      assertThrows(IllegalArgumentException.class, () -> codec.stage(blank));
      assertThrows(IllegalArgumentException.class, () -> codec.functionName(blank));
    }
    // JSON 字面量 null 解析为 NullNode 而非 MissingNode，同样不是合法 state。
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
  }

  /** 拒绝 plan 内 manifest/outputs 的类型与数量漂移，以及 manifest 成员的 kind/UUID/数值类型漂移。 */
  @Test
  void rejectsPlanShapeAndManifestMemberDrift() {
    ObjectNode root = encodedRoot();
    firstPlan(root).put("manifest", "x");
    String manifestNotArray = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(manifestNotArray));

    root = encodedRoot();
    firstPlan(root).put("outputs", "x");
    String outputsNotArray = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(outputsNotArray));

    root = encodedRoot();
    ((ArrayNode) firstPlan(root).get("outputs")).remove(0);
    String outputsMismatch = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(outputsMismatch));

    root = encodedRoot();
    firstManifestReference(root).put("kind", "NOT_A_KIND");
    String invalidKind = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidKind));

    root = encodedRoot();
    firstManifestReference(root).put("blobId", 5);
    String nonStringUuid = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(nonStringUuid));

    root = encodedRoot();
    firstManifestReference(root).put("sourceNodeId", "not-a-uuid");
    String malformedUuid = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(malformedUuid));

    root = encodedRoot();
    firstManifestReference(root).put("sizeBytes", -1);
    String negativeSize = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(negativeSize));

    root = encodedRoot();
    firstManifestReference(root).put("width", 0);
    String nonPositiveWidth = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(nonPositiveWidth));
  }

  /** 拒绝缺失 plan 字段、空白文本、decode 侧超限 adapterState、超深 args 以及非法 stage/functionName JSON。 */
  @Test
  void rejectsMissingFieldsBlankTextOversizedAdapterStateAndDeepArgs() {
    ObjectNode root = encodedRoot();
    firstPlan(root).remove("outputs");
    String missingOutputs = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missingOutputs));

    root = encodedRoot();
    firstPlan(root).put("nodeName", "");
    String blankNodeName = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(blankNodeName));

    root = encodedRoot();
    ObjectNode oversized = mapper.createObjectNode();
    oversized.put("large", "x".repeat(65 * 1024));
    root.set("adapterState", oversized);
    String oversizedAdapterState = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(oversizedAdapterState));

    // args 超过 Core 的嵌套深度上限：Jackson 允许解析，但严格 args 解析必须拒绝。
    root = encodedRoot();
    firstPlan(root).set("args", nestedObject(CanvasJson.MAX_DEPTH + 1));
    String deepArgs = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(deepArgs));

    // 定义的 argsSchema 与 args 一样走严格解析，超深同样拒绝。
    root = encodedRoot();
    ((ObjectNode) firstPlan(root).get("definition"))
        .set("argsSchema", nestedObject(CanvasJson.MAX_DEPTH + 1));
    String deepSchema = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(deepSchema));

    assertThrows(IllegalArgumentException.class, () -> codec.functionName("{"));
  }

  /**
   * 高精度与极值数字 args 必须在 encode/decode 与 checkpoint 后保持 Core 的 BigDecimal 语义：不因 double 化而丢精度、 下溢或变成
   * Infinity，长整数也不退化成 double。
   */
  @Test
  void preservesBigDecimalArgsAcrossEncodeDecodeAndCheckpoint() {
    JsonObject args =
        CanvasJson.parseObject(
            """
            {
              "precise": 1.23456789012345678901234567890123456789,
              "hugeFraction": 1e309,
              "tiny": 1e-324,
              "hugeInteger": 1e10000,
              "negativeScale": 1e-10000
            }
            """);
    CanvasFunctionFrozenRun bigDecimalRun =
        new CanvasFunctionFrozenRun(
            run.canvasId(),
            run.nodeId(),
            run.nodeName(),
            run.requestId(),
            run.binding(),
            args,
            run.manifest(),
            run.outputs(),
            run.submitState(),
            run.stage(),
            run.adapterState());

    String state = codec.encode(bigDecimalRun);
    // 线文本保持完整精度：高精度小数逐位保留，10001 位整数既未被截断也未被 double 吞掉。
    assertTrue(state.contains("1.23456789012345678901234567890123456789"));
    assertTrue(state.contains("1" + "0".repeat(10000)));
    assertFalse(state.contains("Infinity"));
    assertFalse(state.contains("NaN"));

    // decode 与 checkpoint 必须还原完全相等的 Core args（BigDecimal 值语义），而不是被改写的近似值。
    assertEquals(args, codec.decode(state).args());
    assertEquals(args, codec.checkpoint(bigDecimalRun, "POLLING", Map.of()).args());

    // state.plan.args 的数值节点必须仍是 BigDecimal/BigInteger，而不是 DoubleNode。
    JsonNode argsNode = ((ObjectNode) profileMapper().readTree(state)).get("plan").get("args");
    assertTrue(argsNode.get("precise").isBigDecimal());
    assertTrue(argsNode.get("hugeFraction").isBigInteger());
    assertTrue(argsNode.get("tiny").isBigDecimal());
    assertTrue(argsNode.get("hugeInteger").isBigInteger());
    assertTrue(argsNode.get("negativeScale").isBigDecimal());
  }

  /** 与生产 codec 相同的数字读取 profile：BigDecimal 浮点并把 number 预算对齐 CanvasJson。 */
  private static ObjectMapper profileMapper() {
    return JsonMapper.builder(
            JsonFactory.builder()
                .streamReadConstraints(
                    StreamReadConstraints.builder().maxNumberLength(CanvasJson.MAX_LENGTH).build())
                .build())
        .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();
  }

  private ObjectNode encodedRoot() {
    return (ObjectNode) mapper.readTree(codec.encode(run));
  }

  private static ObjectNode firstPlan(ObjectNode root) {
    return (ObjectNode) root.get("plan");
  }

  private static ObjectNode firstManifestReference(ObjectNode root) {
    return (ObjectNode) ((ArrayNode) firstPlan(root).get("manifest")).get(0);
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
