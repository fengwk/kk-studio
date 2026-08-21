package fun.fengwk.kkstudio.harness.tool.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link ToolArgumentsNormalizer} 的 schema 驱动静默归一化测试。 */
class ToolArgumentsNormalizerTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** filePath 且无 path 时，仅当 schema 声明 path 才改写为 path 并删除 filePath。 */
  @Test
  void mapsFilePathToPathOnlyWhenSchemaDeclaresPath() {
    ToolParamsSchema withPath =
        new ToolParamsSchema(null, Map.of("path", new ToolStringSchema(null)), Set.of(), false);
    assertEquals(
        "{\"path\":\"README.md\"}",
        ToolArgumentsNormalizer.normalize("{\"filePath\":\"README.md\"}", withPath));

    // schema 未声明 path 时 filePath 保持原样，由校验器以 additionalProperties 拒绝。
    ToolParamsSchema withoutPath =
        new ToolParamsSchema(null, Map.of("filePath", new ToolStringSchema(null)), Set.of(), false);
    assertEquals(
        "{\"filePath\":\"README.md\"}",
        ToolArgumentsNormalizer.normalize("{\"filePath\":\"README.md\"}", withoutPath));
  }

  /** path 与 filePath 同时存在时不改写，filePath 仍由校验器以 additionalProperties 拒绝。 */
  @Test
  void keepsFilePathWhenPathIsAlsoPresent() {
    ToolParamsSchema schema =
        new ToolParamsSchema(null, Map.of("path", new ToolStringSchema(null)), Set.of(), false);
    String arguments = "{\"filePath\":\"a.txt\",\"path\":\"b.txt\"}";
    assertEquals(arguments, ToolArgumentsNormalizer.normalize(arguments, schema));
  }

  /** integer 字段的十进制数字字符串被改写为 JSON integer；非数字字符串保持原样。 */
  @Test
  void parsesIntegerTextIntoJsonInteger() {
    ToolParamsSchema schema =
        new ToolParamsSchema(null, Map.of("offset", new ToolIntegerSchema(null)), Set.of(), false);
    assertEquals(
        "{\"offset\":12}", ToolArgumentsNormalizer.normalize("{\"offset\":\"12\"}", schema));
    assertEquals(
        "{\"offset\":-3}", ToolArgumentsNormalizer.normalize("{\"offset\":\"-3\"}", schema));
    // 非十进制数字字符串不改写，仍由校验器以 type mismatch 拒绝。
    assertEquals(
        "{\"offset\":\"1.5\"}", ToolArgumentsNormalizer.normalize("{\"offset\":\"1.5\"}", schema));
    assertEquals(
        "{\"offset\":\"abc\"}", ToolArgumentsNormalizer.normalize("{\"offset\":\"abc\"}", schema));
  }

  /** number 字段接受十进制数字字符串（含小数/指数）并改写为 JSON number；非数字字符串不改写。 */
  @Test
  void parsesNumberTextIntoJsonNumber() {
    ToolParamsSchema schema =
        new ToolParamsSchema(null, Map.of("ratio", new ToolNumberSchema(null)), Set.of(), false);
    assertEquals(
        "{\"ratio\":1.5}", ToolArgumentsNormalizer.normalize("{\"ratio\":\"1.5\"}", schema));
    assertEquals(
        "{\"ratio\":-2.0}", ToolArgumentsNormalizer.normalize("{\"ratio\":\"-2e0\"}", schema));
    assertEquals(
        "{\"ratio\":\"abc\"}", ToolArgumentsNormalizer.normalize("{\"ratio\":\"abc\"}", schema));
  }

  /** string/boolean/enum 字段的数字文本不转换；整数字段的真实 JSON number 不转换。 */
  @Test
  void leavesNonNumericSchemasAndRealNumbersUntouched() {
    ToolParamsSchema schema =
        new ToolParamsSchema(
            null,
            Map.of(
                "name", new ToolStringSchema(null),
                "flag", new ToolBooleanSchema(null),
                "kind", new ToolEnumSchema(null, List.of("12")),
                "count", new ToolIntegerSchema(null)),
            Set.of(),
            false);
    String arguments = "{\"name\":\"12\",\"flag\":\"true\",\"kind\":\"12\",\"count\":7}";
    assertEquals(arguments, ToolArgumentsNormalizer.normalize(arguments, schema));
  }

  /** 递归归一化数组与嵌套对象中的 filePath 别名和整数字符串。 */
  @Test
  void normalizesRecursivelyIntoArraysAndNestedObjects() throws Exception {
    ToolParamsSchema schema =
        new ToolParamsSchema(
            null,
            Map.of(
                "items",
                new ToolArraySchema(
                    null,
                    new ToolObjectSchema(
                        null,
                        Map.of(
                            "path", new ToolStringSchema(null),
                            "limit", new ToolIntegerSchema(null)),
                        Set.of(),
                        false))),
            Set.of(),
            false);
    String arguments =
        "{\"items\":[{\"filePath\":\"a.txt\",\"limit\":\"10\"},{\"path\":\"b.txt\",\"limit\":5}]}";
    String normalized = ToolArgumentsNormalizer.normalize(arguments, schema);
    // 字段改写可能改变 JSON 字段顺序，按解析后的节点做语义等值断言。
    JsonNode items = OBJECT_MAPPER.readTree(normalized).get("items");
    assertEquals(2, items.size());
    assertEquals("a.txt", items.get(0).get("path").asText());
    assertEquals(10, items.get(0).get("limit").asInt());
    assertEquals("b.txt", items.get(1).get("path").asText());
    assertEquals(5, items.get(1).get("limit").asInt());
    assertFalse(items.get(0).has("filePath"));
  }

  /** 整数溢出或超 double 范围的文本不改写，仍由校验器拒绝；这是归一化不吞掉非法输入的关键。 */
  @Test
  void leavesOutOfRangeNumericTextUntouched() {
    ToolParamsSchema intSchema =
        new ToolParamsSchema(null, Map.of("offset", new ToolIntegerSchema(null)), Set.of(), false);
    String overflow = "{\"offset\":\"9223372036854775808\"}";
    assertEquals(overflow, ToolArgumentsNormalizer.normalize(overflow, intSchema));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolArgumentsValidator.validate(
                ToolArgumentsNormalizer.normalize(overflow, intSchema), intSchema));

    ToolParamsSchema numberSchema =
        new ToolParamsSchema(null, Map.of("ratio", new ToolNumberSchema(null)), Set.of(), false);
    // "1e999" 超出 double 可表示范围，改写会变成 Infinity，因此不改写，由校验器拒绝。
    String tooLarge = "{\"ratio\":\"1e999\"}";
    assertEquals(tooLarge, ToolArgumentsNormalizer.normalize(tooLarge, numberSchema));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolArgumentsValidator.validate(
                ToolArgumentsNormalizer.normalize(tooLarge, numberSchema), numberSchema));
  }

  /** 归一化后参数仍必须通过 schema 校验；两者都存在的 filePath 归一化不改写、校验必然失败。 */
  @Test
  void normalizedArgumentsStillPassSchemaValidation() {
    ToolParamsSchema schema =
        new ToolParamsSchema(null, Map.of("path", new ToolStringSchema(null)), Set.of(), false);
    ToolArgumentsValidator.validate(
        ToolArgumentsNormalizer.normalize("{\"filePath\":\"README.md\"}", schema), schema);

    // path 与 filePath 同时存在时归一化不改写，校验器以 additionalProperties 拒绝。
    String both = "{\"filePath\":\"a.txt\",\"path\":\"b.txt\"}";
    assertEquals(both, ToolArgumentsNormalizer.normalize(both, schema));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolArgumentsValidator.validate(
                ToolArgumentsNormalizer.normalize(both, schema), schema));
  }

  /** 空白参数归一为 {}；顶层非 object 仍被拒绝。 */
  @Test
  void requiresTopLevelJsonObject() {
    ToolParamsSchema schema = new ToolParamsSchema(null, Map.of(), Set.of(), false);
    assertEquals("{}", ToolArgumentsNormalizer.normalize(" ", schema));
    assertThrows(
        IllegalArgumentException.class, () -> ToolArgumentsNormalizer.normalize("[]", schema));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolArgumentsNormalizer.normalize("not-json", schema));
  }
}
