package fun.fengwk.kkstudio.harness.tool.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolDescriptorJsonCodec} 的契约与 round-trip 测试。
 *
 * <p>覆盖：round-trip、canonical fixture、所有 schema element、未知字段、trailing token、duplicate field、错误类型、非法
 * required/property、location / sideEffect / timeout 边界。
 */
class ToolDescriptorJsonCodecTest {

  private final ToolDescriptorJsonCodec codec = new ToolDescriptorJsonCodec();

  /** round-trip：简单 string descriptor 必须原值还原。 */
  @Test
  void roundTripsStringDescriptor() {
    ToolDescriptor original = simpleDescriptor("search", "v1", "search helper");

    String json = codec.encode(original);
    ToolDescriptor decoded = codec.decode(json);

    assertEquals(original, decoded);
  }

  /** round-trip：复杂 nested object descriptor 必须原值还原。 */
  @Test
  void roundTripsNestedDescriptor() {
    ToolDescriptor original = nestedDescriptor();

    String json = codec.encode(original);
    ToolDescriptor decoded = codec.decode(json);

    assertEquals(original, decoded);
  }

  /** round-trip：空 properties + 空 required + additionalProperties=true。 */
  @Test
  void roundTripsEmptyPropertiesDescriptor() {
    ToolDescriptor original =
        new ToolDescriptor(
            "noop",
            "v1",
            "no params",
            "noop",
            new ToolParamsSchema(null, Map.of(), Set.of(), true),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);

    String json = codec.encode(original);
    ToolDescriptor decoded = codec.decode(json);

    assertEquals(original, decoded);
  }

  /** 输出必须是 deterministic canonical：相同输入产生 bit-identical JSON。 */
  @Test
  void encodingIsDeterministic() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "ord",
            "v1",
            "ord",
            "ord",
            new ToolParamsSchema(
                "params",
                Map.of(
                    "zeta", new ToolStringSchema("z"),
                    "alpha", new ToolIntegerSchema("a"),
                    "mu", new ToolBooleanSchema("m")),
                Set.of("alpha", "zeta", "mu"),
                false),
            ToolSideEffect.IDEMPOTENT,
            Duration.ofMillis(1234));

    String first = codec.encode(descriptor);
    String second = codec.encode(descriptor);
    assertEquals(first, second);

    // properties 必须按字典序输出：alpha 在 mu 之前，mu 在 zeta 之前。
    int alphaIdx = first.indexOf("\"alpha\"");
    int muIdx = first.indexOf("\"mu\"");
    int zetaIdx = first.indexOf("\"zeta\"");
    assertTrue(alphaIdx > 0 && muIdx > alphaIdx && zetaIdx > muIdx);

    // required 数组必须按字典序输出。
    int requiredIdx = first.indexOf("\"required\"");
    assertTrue(requiredIdx > 0);
    int alphaInRequired = first.indexOf("\"alpha\"", requiredIdx);
    int muInRequired = first.indexOf("\"mu\"", requiredIdx);
    int zetaInRequired = first.indexOf("\"zeta\"", requiredIdx);
    assertTrue(alphaInRequired < muInRequired && muInRequired < zetaInRequired);
  }

  /** enum values 保留输入顺序，不强制排序（语义上是枚举选项而非集合）。 */
  @Test
  void enumValuesPreserveInputOrder() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "enumTool",
            "v1",
            "enum",
            "enumTool",
            new ToolParamsSchema(
                "params",
                Map.of("color", new ToolEnumSchema(null, List.of("red", "green", "blue"))),
                Set.of("color"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);

    String json = codec.encode(descriptor);

    int redIdx = json.indexOf("\"red\"");
    int greenIdx = json.indexOf("\"green\"");
    int blueIdx = json.indexOf("\"blue\"");
    assertTrue(redIdx > 0 && greenIdx > redIdx && blueIdx > greenIdx);
  }

  /**
   * encodeInputSchema 输出必须是精确 canonical JsonNode 字符串；且必须与 descriptor 编码中的 inputSchema 子节点逐 字节相等。
   */
  @Test
  void encodeInputSchemaProducesRoundTrippableSchema() throws Exception {
    ToolParamsSchema schema =
        new ToolParamsSchema(
            "params",
            Map.of(
                "items",
                new ToolArraySchema("list", new ToolStringSchema("element")),
                "enabled",
                new ToolBooleanSchema("flag"),
                "zeta",
                new ToolIntegerSchema("z"),
                "alpha",
                new ToolNumberSchema("a")),
            Set.of("items", "alpha"),
            false);

    ObjectMapper mapper = new ObjectMapper();

    String schemaJson = codec.encodeInputSchema(schema);
    assertNotNull(schemaJson);
    JsonNode schemaNode = mapper.readTree(schemaJson);

    // 精确 canonical JsonNode 结构与字段排序：
    // properties 按字典序输出 (alpha, enabled, items, zeta)；required 按字典序输出 (alpha, items)。
    JsonNode expected =
        mapper.readTree(
            "{"
                + "\"type\":\"object\","
                + "\"description\":\"params\","
                + "\"properties\":{"
                + "\"alpha\":{\"type\":\"number\",\"description\":\"a\"},"
                + "\"enabled\":{\"type\":\"boolean\",\"description\":\"flag\"},"
                + "\"items\":{\"type\":\"array\",\"description\":\"list\","
                + "\"items\":{\"type\":\"string\",\"description\":\"element\"}},"
                + "\"zeta\":{\"type\":\"integer\",\"description\":\"z\"}"
                + "},"
                + "\"required\":[\"alpha\",\"items\"],"
                + "\"additionalProperties\":false"
                + "}");
    assertEquals(expected, schemaNode);
    assertEquals(schemaJson, mapper.writeValueAsString(schemaNode));

    // 组合成 descriptor 后，descriptor 编码中的 inputSchema 子节点必须与 encodeInputSchema 输出完全一致。
    ToolDescriptor descriptor =
        new ToolDescriptor("x", "v1", "x", "x", schema, ToolSideEffect.READ_ONLY, Duration.ZERO);
    String descriptorJson = codec.encode(descriptor);
    JsonNode descriptorRoot = mapper.readTree(descriptorJson);
    assertEquals(schemaNode, descriptorRoot.get("inputSchema"));

    // round-trip 后结构必须等价。
    assertEquals(descriptor, codec.decode(descriptorJson));

    // 显式验证 required 数组在文本中按字典序出现（"alpha" 在 "items" 之前）。
    ArrayNode required = (ArrayNode) schemaNode.get("required");
    assertEquals(
        List.of("alpha", "items"), List.of(required.get(0).asText(), required.get(1).asText()));
  }

  /** descriptor wire 只包含模型契约；旧顶层 type 按未知字段拒绝，schema 自身的 type 保留。 */
  @Test
  void descriptorWireExcludesRoutingType() {
    ObjectNode node = codec.encodeNode(simpleDescriptor("wire", "v1", "wire"));

    assertFalse(node.has("type"));
    assertEquals("object", node.path("inputSchema").path("type").asText());

    node.put("type", "PLATFORM");
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode(node));
    assertTrue(error.getMessage().contains("type"));
  }

  /** unknown top-level field 必须拒绝。 */
  @Test
  void rejectsUnknownTopLevelField() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + emptySchema()
            + ",\"extra\":\"nope\"}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  /** unknown schema field 必须拒绝。 */
  @Test
  void rejectsUnknownSchemaField() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},\"required\":[],"
            + "\"additionalProperties\":false,\"stranger\":true}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("stranger"));
  }

  /** schema element 上的未知字段必须拒绝。 */
  @Test
  void rejectsUnknownSchemaElementField() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"q\":{\"type\":\"string\",\"min\":1}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("min"));
  }

  /** 未知 schema type 必须拒绝。 */
  @Test
  void rejectsUnknownSchemaType() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"q\":{\"type\":\"weird\"}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("weird"));
  }

  /** trailing token 必须在解析阶段被拒绝。 */
  @Test
  void rejectsTrailingTokens() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + emptySchema()
            + "} {}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().toLowerCase().contains("trailing") || error.getCause() != null);
  }

  /** duplicate top-level field 必须被拒绝。 */
  @Test
  void rejectsDuplicateTopLevelField() {
    String json =
        "{"
            + "\"name\":\"x\",\"name\":\"y\",\"version\":\"v1\",\"description\":\"x\","
            + "\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\",\"timeoutMillis\":0,\"inputSchema\":"
            + emptySchema()
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().toLowerCase().contains("duplicate") || error.getCause() != null);
  }

  /** duplicate properties key 必须被拒绝。 */
  @Test
  void rejectsDuplicatePropertyKey() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"q\":{\"type\":\"string\"},\"q\":{\"type\":\"integer\"}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().toLowerCase().contains("duplicate") || error.getCause() != null);
  }

  /** additionalProperties 非 boolean 必须拒绝。 */
  @Test
  void rejectsNonBooleanAdditionalProperties() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},\"required\":[],"
            + "\"additionalProperties\":\"yes\"}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("additionalProperties"));
  }

  /** required 非 array 必须拒绝。 */
  @Test
  void rejectsNonArrayRequired() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},\"required\":\"q\","
            + "\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("required"));
  }

  /** required 中重复名称必须拒绝。 */
  @Test
  void rejectsDuplicateRequired() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"q\":{\"type\":\"string\"}},"
            + "\"required\":[\"q\",\"q\"],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("duplicate"));
  }

  /** required 中未声明的属性必须拒绝。 */
  @Test
  void rejectsUndeclaredRequired() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},\"required\":[\"q\"],"
            + "\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("not declared"));
  }

  /** enum 元素非 string 必须拒绝。 */
  @Test
  void rejectsNonStringEnumValues() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"c\":{\"type\":\"string\",\"enum\":[\"a\",1]}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("enum"));
  }

  /** array 缺 items 必须拒绝。 */
  @Test
  void rejectsArrayWithoutItems() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"xs\":{\"type\":\"array\"}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("items"));
  }

  /** object schema 上 type 不是 object 必须拒绝。 */
  @Test
  void rejectsObjectSchemaWrongType() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"string\",\"properties\":{},\"required\":[],"
            + "\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'object'"));
  }

  /** object schema 缺 properties 必须拒绝。 */
  @Test
  void rejectsObjectSchemaMissingProperties() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("properties"));
  }

  /** Tool descriptor 必须原样可编解码。 */
  @Test
  void environmentDescriptorRoundTrips() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "remoteShell",
            "v1",
            "shell",
            "remoteShell",
            new ToolParamsSchema(null, Map.of(), Set.of(), true),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMillis(500));

    String json = codec.encode(descriptor);
    ToolDescriptor decoded = codec.decode(json);

    assertEquals(descriptor, decoded);
  }

  /** tool name 不符合 {@code [A-Za-z][A-Za-z0-9_-]*} 必须拒绝（由 ToolDescriptor record 触发）。 */
  @Test
  void rejectsInvalidToolNamePattern() {
    String json =
        "{"
            + "\"name\":\"1tool\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + emptySchema()
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("descriptor validation failed"));
  }

  /** 未知 sideEffect 必须拒绝。 */
  @Test
  void rejectsUnknownSideEffect() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"UNSAFE\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + emptySchema()
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("sideEffect"));
  }

  /** timeoutMillis 负值必须拒绝。 */
  @Test
  void rejectsNegativeTimeout() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":-1,\"inputSchema\":"
            + emptySchema()
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(
        error.getMessage().toLowerCase().contains("non-negative")
            || error.getMessage().toLowerCase().contains("negative"));
  }

  /** timeoutMillis 非整数必须拒绝。 */
  @Test
  void rejectsNonIntegralTimeout() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":\"10s\",\"inputSchema\":"
            + emptySchema()
            + "}";

    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }

  /** 缺 name 必须拒绝。 */
  @Test
  void rejectsMissingName() {
    String json =
        "{"
            + "\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + emptySchema()
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("name"));
  }

  /** 缺 inputSchema 必须拒绝。 */
  @Test
  void rejectsMissingInputSchema() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("inputSchema"));
  }

  /** 顶层不是 object 必须拒绝。 */
  @Test
  void rejectsNonObjectRoot() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("\"x\""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("42"));
  }

  /** malformed JSON 必须拒绝。 */
  @Test
  void rejectsMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
  }

  /** null 输入必须拒绝。 */
  @Test
  void rejectsNullJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
  }

  /** encode / decode 不变性：连续多次 encode 不应引入额外差异。 */
  @Test
  void encodeIsIdempotent() {
    ToolDescriptor descriptor = nestedDescriptor();
    String first = codec.encode(descriptor);
    ToolDescriptor decoded = codec.decode(first);
    String second = codec.encode(decoded);
    assertEquals(first, second);
  }

  /** 不同输入必须产生不同 JSON（防止过度 canonicalization 抹平差异）。 */
  @Test
  void encodesDistinguishInputs() {
    ToolDescriptor a = simpleDescriptor("alpha", "v1", "first");
    ToolDescriptor b = simpleDescriptor("alpha", "v2", "first");
    assertNotEquals(codec.encode(a), codec.encode(b));
  }

  /** decode 必须每次返回等价对象，但不一定共享 identity。 */
  @Test
  void decodeProducesEquivalentInstance() {
    ToolDescriptor original = simpleDescriptor("search", "v1", "search helper");
    String json = codec.encode(original);
    ToolDescriptor decoded1 = codec.decode(json);
    ToolDescriptor decoded2 = codec.decode(json);
    assertEquals(decoded1, decoded2);
  }

  /**
   * String API 必须与 node API 完全等价：encode(descriptor) ==
   * writeValueAsString(encodeNode(descriptor))，decode 在 JSON 文本与 JsonNode 输入下产生等价
   * descriptor；如此调用方可安全地切换到 node API 而不破坏 wire。
   */
  @Test
  void nodeApiIsConsistentWithStringApi() throws Exception {
    ToolDescriptor descriptor = nestedDescriptor();
    ObjectMapper mapper = new ObjectMapper();

    ObjectNode node = codec.encodeNode(descriptor);
    assertEquals(codec.encode(descriptor), mapper.writeValueAsString(node));

    ToolDescriptor fromString = codec.decode(codec.encode(descriptor));
    ToolDescriptor fromNode = codec.decodeNode(node);
    assertEquals(fromString, fromNode);
    assertEquals(descriptor, fromNode);
  }

  /** enum 值不是数组（例如直接是字符串）必须拒绝。 */
  @Test
  void rejectsEnumValueNotArray() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"c\":{\"type\":\"string\",\"enum\":\"red\"}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("'enum' must be an array"));
  }

  /** enum 数组中存在空白字符串元素必须拒绝。 */
  @Test
  void rejectsBlankEnumElement() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"c\":{\"type\":\"string\",\"enum\":[\"red\",\" \"]}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("must be non-blank"));
  }

  /** enum 数组中存在重复元素必须拒绝（保持输入顺序，禁止重复值）。 */
  @Test
  void rejectsDuplicateEnumElement() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"c\":{\"type\":\"string\",\"enum\":[\"red\",\"green\",\"red\"]}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("duplicate"));
    assertTrue(error.getMessage().contains("red"));
  }

  /** 嵌套 object schema 的 enum 数组存在重复元素必须拒绝。 */
  @Test
  void rejectsDuplicateNestedEnumElement() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"outer\":{\"type\":\"object\","
            + "\"properties\":{\"c\":{\"type\":\"string\",\"enum\":[\"a\",\"a\"]}},"
            + "\"required\":[],\"additionalProperties\":false}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("duplicate"));
  }

  /** object schema 的某个 property 值不是 object 必须拒绝。 */
  @Test
  void rejectsNonObjectSchemaProperty() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{\"q\":[]},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("must be an object"));
  }

  /** description 为非 string 必须拒绝。 */
  @Test
  void rejectsNonStringSchemaDescription() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\",\"description\":1}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("description"));
  }

  /** 缺 required 字段必须拒绝。 */
  @Test
  void rejectsMissingRequiredField() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},"
            + "\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("required"));
  }

  /** 缺 additionalProperties 字段必须拒绝。 */
  @Test
  void rejectsMissingAdditionalPropertiesField() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("additionalProperties"));
  }

  /** additionalProperties 显式为 null 必须拒绝。 */
  @Test
  void rejectsNullAdditionalProperties() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},\"required\":[],"
            + "\"additionalProperties\":null}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("additionalProperties"));
  }

  /** required 显式为 null 必须拒绝（必须为 JSON array）。 */
  @Test
  void rejectsNullRequired() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{},"
            + "\"required\":null,\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("required"));
  }

  /** required 数组中存在非 string 元素必须拒绝。 */
  @Test
  void rejectsNonStringRequiredElement() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},"
            + "\"required\":[42],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("must be a string"));
  }

  /** required 数组中存在空白字符串元素必须拒绝。 */
  @Test
  void rejectsBlankRequiredElement() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},"
            + "\"required\":[\"\"],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("non-blank"));
  }

  /** 嵌套 object schema 的某个 property 值不是 object 必须拒绝。 */
  @Test
  void rejectsNestedNonObjectSchemaProperty() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"outer\":{\"type\":\"object\","
            + "\"properties\":{\"q\":42},"
            + "\"required\":[],\"additionalProperties\":false}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("must be an object"));
  }

  /** 嵌套 object schema 上 required 引用未声明的 property 必须拒绝。 */
  @Test
  void rejectsUndeclaredNestedRequired() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"outer\":{\"type\":\"object\","
            + "\"properties\":{\"q\":{\"type\":\"string\"}},"
            + "\"required\":[\"missing\"],\"additionalProperties\":false}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().contains("not declared"));
  }

  /** 嵌套 object schema 中 property 名为空白字符串必须拒绝（由 record contract 触发）。 */
  @Test
  void rejectsBlankNestedPropertyName() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\" \":{\"type\":\"string\"}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().toLowerCase().contains("non-blank"));
  }

  /** 嵌套 object schema 的 enum 值含空白字符串元素必须拒绝（由 record contract 触发）。 */
  @Test
  void rejectsBlankNestedEnumElement() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"outer\":{\"type\":\"object\","
            + "\"properties\":{\"c\":{\"type\":\"string\",\"enum\":[\"a\",\"\"]}},"
            + "\"required\":[],\"additionalProperties\":false}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().toLowerCase().contains("non-blank"));
  }

  /** 顶层 inputSchema 的 property 名为空白字符串必须拒绝。 */
  @Test
  void rejectsBlankTopLevelPropertyName() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\" \":{\"type\":\"string\"}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().toLowerCase().contains("non-blank"));
  }

  /** 顶层 inputSchema 的 enum 值含空白字符串元素必须拒绝。 */
  @Test
  void rejectsBlankTopLevelEnumElement() {
    String json =
        "{"
            + "\"name\":\"x\",\"version\":\"v1\",\"description\":\"x\",\"rendererKey\":\"x\","
            + "\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":"
            + "{\"type\":\"object\","
            + "\"properties\":{\"c\":{\"type\":\"string\",\"enum\":[\"a\",\"\"]}},"
            + "\"required\":[],\"additionalProperties\":false}"
            + "}";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    assertTrue(error.getMessage().toLowerCase().contains("non-blank"));
  }

  /** 嵌套 object schema 上 required 顺序不影响 round-trip（codec 强制排序）。 */
  @Test
  void nestedObjectRequiredRoundTripsRegardlessOfOrder() throws Exception {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "ord",
            "v1",
            "ord",
            "ord",
            new ToolParamsSchema(
                null,
                Map.of(
                    "outer",
                    new ToolObjectSchema(
                        "outer object",
                        Map.of(
                            "y", new ToolIntegerSchema("y"),
                            "x", new ToolIntegerSchema("x")),
                        // 输入顺序：y, x（违反字典序），codec 须强制排序为 x, y。
                        Set.of("y", "x"),
                        false)),
                Set.of("outer"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);

    ObjectMapper mapper = new ObjectMapper();
    String json = codec.encode(descriptor);
    ToolDescriptor decoded = codec.decode(json);
    assertEquals(descriptor, decoded);

    // 通过 JsonNode 精确断言 nested object schema 的 required 数组为 ["x","y"]，且 properties 按字典序。
    JsonNode root = mapper.readTree(json);
    JsonNode inputSchema = root.get("inputSchema");
    JsonNode outerProperties = inputSchema.get("properties").get("outer");
    JsonNode outerRequired = outerProperties.get("required");
    assertEquals(
        List.of("x", "y"), List.of(outerRequired.get(0).asText(), outerRequired.get(1).asText()));

    // 同时验证 outer.properties 字段按字典序输出：x 在 y 之前。
    ArrayNode outerPropNames = new ObjectMapper().createArrayNode();
    outerProperties.get("properties").fieldNames().forEachRemaining(outerPropNames::add);
    assertEquals(
        List.of("x", "y"), List.of(outerPropNames.get(0).asText(), outerPropNames.get(1).asText()));
  }

  private static ToolDescriptor simpleDescriptor(String name, String version, String description) {
    return new ToolDescriptor(
        name,
        version,
        description,
        name,
        new ToolParamsSchema(
            "params",
            Map.of("query", new ToolStringSchema("search query")),
            Set.of("query"),
            false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMillis(1000));
  }

  private static ToolDescriptor nestedDescriptor() {
    return new ToolDescriptor(
        "envTool",
        "v2",
        "nested env tool",
        "envTool",
        new ToolParamsSchema(
            "params",
            Map.of(
                "items",
                new ToolArraySchema("list", new ToolNumberSchema("numeric")),
                "label",
                new ToolStringSchema("label"),
                "mode",
                new ToolEnumSchema("mode", List.of("READ", "WRITE")),
                "flag",
                new ToolBooleanSchema("flag"),
                "limit",
                new ToolIntegerSchema("limit"),
                "nested",
                new ToolObjectSchema(
                    "nested",
                    Map.of("inner", new ToolStringSchema("inner")),
                    Set.of("inner"),
                    false)),
            Set.of("label", "mode", "limit", "nested"),
            false),
        ToolSideEffect.IDEMPOTENT,
        Duration.ofSeconds(5));
  }

  private static String emptySchema() {
    return "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
  }
}
