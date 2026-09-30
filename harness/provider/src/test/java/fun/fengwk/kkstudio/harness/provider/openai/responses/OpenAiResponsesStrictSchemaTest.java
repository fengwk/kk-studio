package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link OpenAiResponsesStrictSchema} 归一化契约单元测试。
 *
 * <p>与 {@code OpenAiResponsesRequestEncoderTest} 的分工：编码器测试验证 strict schema 在真实 wire 请求体中的最终形态，本类
 * 直接锁定归一化器自身的判定边界——哪些值会被改写、哪些位置会被递归、判断不出来时朝哪个方向保守失败。这些边界决定 strict 工具
 * 参数是否会被上游拒绝，属于本协议正确性的关键路径，因此该归一化器与编码器同级受 JaCoCo 行覆盖率门禁约束。
 */
class OpenAiResponsesStrictSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ObjectNode normalize(String schemaJson) throws Exception {
    return OpenAiResponsesStrictSchema.normalize((ObjectNode) MAPPER.readTree(schemaJson));
  }

  private static List<String> requiredOf(JsonNode objectSchema) {
    List<String> required = new ArrayList<>();
    objectSchema.path("required").forEach(entry -> required.add(entry.asText()));
    return required;
  }

  /** 断言 strict 形态已成立：显式写出全量 required，且 additionalProperties 明确为 false（而非缺失）。 */
  private static void assertStrictObject(JsonNode objectSchema, List<String> expectedRequired) {
    assertEquals(expectedRequired, requiredOf(objectSchema));
    assertTrue(objectSchema.has("additionalProperties"), "additionalProperties must be present");
    assertFalse(objectSchema.path("additionalProperties").asBoolean());
  }

  /**
   * 测试意图：属性值本身不是 JSON object（数字、null 字面量）时，按“不允许 null”保守处理——整体外包一层 {@code anyOf: [原值,
   * {"type":"null"}]}；原值本身原样保留，不被改写、不被丢弃。
   */
  @Test
  void wrapsNonObjectPropertyValuesWithoutRewritingThem() throws Exception {
    ObjectNode normalized =
        normalize(
            """
            {"type":"object","properties":{"scalar":5,"nil":null}}
            """);

    JsonNode properties = normalized.path("properties");
    assertStrictObject(normalized, List.of("scalar", "nil"));
    assertEquals(5, properties.path("scalar").path("anyOf").get(0).asInt());
    assertEquals("null", properties.path("scalar").path("anyOf").get(1).path("type").asText());
    assertTrue(properties.path("nil").path("anyOf").get(0).isNull());
    assertEquals("null", properties.path("nil").path("anyOf").get(1).path("type").asText());
  }

  /**
   * 测试意图：可空判定只承认 {@code type:"null"}、类型数组中的 null 与 anyOf 中的 null。已经可空的可选属性保持原 schema，不重复包装；anyOf
   * 全部非空、类型数组不含 null、或 {@code type} 形态不可识别时，一律保守地外包一层 null。
   */
  @Test
  void keepsAlreadyNullableOptionalPropertiesAndConservativelyWrapsUnrecognizedTypes()
      throws Exception {
    ObjectNode normalized =
        normalize(
            """
            {"type":"object","properties":{
              "alreadyNullable":{"anyOf":[{"type":"null"},{"type":"string"}]},
              "noNullVariant":{"anyOf":[{"type":"string"},{"type":"integer"}]},
              "typeArrayWithoutNull":{"type":["string","integer"]},
              "unrecognizedTypeShape":{"type":{"not":"a schema"}}
            }}
            """);

    JsonNode properties = normalized.path("properties");
    // anyOf 中已含 null：保持原 schema，不额外包装
    assertEquals(2, properties.path("alreadyNullable").path("anyOf").size());
    assertEquals(
        "null", properties.path("alreadyNullable").path("anyOf").get(0).path("type").asText());
    assertFalse(properties.path("alreadyNullable").has("type"));
    // anyOf 全部不可空：外包一层 anyOf+null，原 anyOf 保留在 anyOf[0]
    assertEquals(
        "null", properties.path("noNullVariant").path("anyOf").get(1).path("type").asText());
    assertEquals(2, properties.path("noNullVariant").path("anyOf").get(0).path("anyOf").size());
    // 类型数组不含 null：外包一层
    assertEquals(
        "null", properties.path("typeArrayWithoutNull").path("anyOf").get(1).path("type").asText());
    // type 形态不可识别：按不可空处理
    assertEquals(
        "null",
        properties.path("unrecognizedTypeShape").path("anyOf").get(1).path("type").asText());
    assertStrictObject(
        normalized,
        List.of(
            "alreadyNullable", "noNullVariant", "typeArrayWithoutNull", "unrecognizedTypeShape"));
  }

  /**
   * 测试意图：归一化必须进入全部显式声明的嵌套 schema 位置（单项与逻辑字段、组合数组、schema map），而 {@code enum}/{@code const}
   * 这类字面量位置即使内部长得像 object 也不得被改写。
   */
  @Test
  void recursesEveryDeclaredSchemaLocationWithoutTouchingLiterals() throws Exception {
    ObjectNode normalized =
        normalize(
            """
            {"type":"object","properties":{"holder":{"type":"string"}},
             "not":{"type":"object","properties":{"a":{"type":"string"}}},
             "if":{"type":"object","properties":{"b":{"type":"string"}}},
             "then":{"type":"object","properties":{"c":{"type":"string"}}},
             "else":{"type":"object","properties":{"d":{"type":"string"}}},
             "contains":{"type":"object","properties":{"e":{"type":"string"}}},
             "additionalItems":{"type":"object","properties":{"f":{"type":"string"}}},
             "propertyNames":{"type":"object","properties":{"g":{"type":"string"}}},
             "unevaluatedItems":{"type":"object","properties":{"h":{"type":"string"}}},
             "unevaluatedProperties":{"type":"object","properties":{"i":{"type":"string"}}},
             "prefixItems":[{"type":"object","properties":{"j":{"type":"string"}}}],
             "patternProperties":{"^k":{"type":"object","properties":{"k":{"type":"string"}}}},
             "dependentSchemas":{"l":{"type":"object","properties":{"l":{"type":"string"}}}},
             "enum":["m",{"n":{"type":"string"}}],
             "const":{"o":{"type":"string"}}}
            """);

    Map<String, String> singleSchemaLocations =
        Map.ofEntries(
            Map.entry("not", "a"),
            Map.entry("if", "b"),
            Map.entry("then", "c"),
            Map.entry("else", "d"),
            Map.entry("contains", "e"),
            Map.entry("additionalItems", "f"),
            Map.entry("propertyNames", "g"),
            Map.entry("unevaluatedItems", "h"),
            Map.entry("unevaluatedProperties", "i"));
    for (Map.Entry<String, String> location : singleSchemaLocations.entrySet()) {
      assertStrictObject(normalized.path(location.getKey()), List.of(location.getValue()));
    }

    assertStrictObject(normalized.path("prefixItems").get(0), List.of("j"));
    assertStrictObject(normalized.path("patternProperties").path("^k"), List.of("k"));
    assertStrictObject(normalized.path("dependentSchemas").path("l"), List.of("l"));

    // 字面量位置不递归：enum 内的 object 与 const 保持原样
    assertEquals("string", normalized.path("enum").get(1).path("n").path("type").asText());
    assertFalse(normalized.path("enum").get(1).path("n").has("required"));
    assertEquals("string", normalized.path("const").path("o").path("type").asText());
    assertFalse(normalized.path("const").path("o").has("required"));
  }
}
