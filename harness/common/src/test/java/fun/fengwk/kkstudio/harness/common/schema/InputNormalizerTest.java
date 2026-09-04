package fun.fengwk.kkstudio.harness.common.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link InputNormalizer} 的 schema 驱动数字容错归一化测试。 */
class InputNormalizerTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** integer 字段的十进制数字字符串被改写为 JSON integer；非数字字符串保持原样。 */
  @Test
  void parsesIntegerTextIntoJsonInteger() {
    InputSchema schema =
        new InputSchema(null, Map.of("offset", new IntegerSchema(null)), Set.of(), false);
    assertEquals("{\"offset\":12}", InputNormalizer.normalize("{\"offset\":\"12\"}", schema));
    assertEquals("{\"offset\":-3}", InputNormalizer.normalize("{\"offset\":\"-3\"}", schema));
    // 非十进制数字字符串不改写，仍由校验器以 type mismatch 拒绝。
    assertEquals("{\"offset\":\"1.5\"}", InputNormalizer.normalize("{\"offset\":\"1.5\"}", schema));
    assertEquals("{\"offset\":\"abc\"}", InputNormalizer.normalize("{\"offset\":\"abc\"}", schema));
  }

  /** number 字段接受十进制数字字符串（含小数/指数）并改写为 JSON number；非数字字符串不改写。 */
  @Test
  void parsesNumberTextIntoJsonNumber() {
    InputSchema schema =
        new InputSchema(null, Map.of("ratio", new NumberSchema(null)), Set.of(), false);
    assertEquals("{\"ratio\":1.5}", InputNormalizer.normalize("{\"ratio\":\"1.5\"}", schema));
    assertEquals("{\"ratio\":-2.0}", InputNormalizer.normalize("{\"ratio\":\"-2e0\"}", schema));
    assertEquals("{\"ratio\":\"abc\"}", InputNormalizer.normalize("{\"ratio\":\"abc\"}", schema));
  }

  /** string/boolean/enum 字段的数字文本不转换；整数字段的真实 JSON number 不转换。 */
  @Test
  void leavesNonNumericSchemasAndRealNumbersUntouched() {
    InputSchema schema =
        new InputSchema(
            null,
            Map.of(
                "name", new StringSchema(null),
                "flag", new BooleanSchema(null),
                "kind", new EnumSchema(null, List.of("12")),
                "count", new IntegerSchema(null)),
            Set.of(),
            false);
    String arguments = "{\"name\":\"12\",\"flag\":\"true\",\"kind\":\"12\",\"count\":7}";
    assertEquals(arguments, InputNormalizer.normalize(arguments, schema));
  }

  /** 递归归一化数组与嵌套对象中的整数字符串。 */
  @Test
  void normalizesRecursivelyIntoArraysAndNestedObjects() throws Exception {
    InputSchema schema =
        new InputSchema(
            null,
            Map.of(
                "items",
                new ArraySchema(
                    null,
                    new ObjectSchema(
                        null,
                        Map.of(
                            "path", new StringSchema(null),
                            "limit", new IntegerSchema(null)),
                        Set.of(),
                        false))),
            Set.of(),
            false);
    String arguments =
        "{\"items\":[{\"path\":\"a.txt\",\"limit\":\"10\"},{\"path\":\"b.txt\",\"limit\":5}]}";
    String normalized = InputNormalizer.normalize(arguments, schema);
    JsonNode items = OBJECT_MAPPER.readTree(normalized).get("items");
    assertEquals(2, items.size());
    assertEquals("a.txt", items.get(0).get("path").asText());
    assertEquals(10, items.get(0).get("limit").asInt());
    assertEquals("b.txt", items.get(1).get("path").asText());
    assertEquals(5, items.get(1).get("limit").asInt());
  }

  /** 整数溢出或超 double 范围的文本不改写，仍由校验器拒绝；这是归一化不吞掉非法输入的关键。 */
  @Test
  void leavesOutOfRangeNumericTextUntouched() {
    InputSchema intSchema =
        new InputSchema(null, Map.of("offset", new IntegerSchema(null)), Set.of(), false);
    String overflow = "{\"offset\":\"9223372036854775808\"}";
    assertEquals(overflow, InputNormalizer.normalize(overflow, intSchema));
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate(InputNormalizer.normalize(overflow, intSchema), intSchema));

    InputSchema numberSchema =
        new InputSchema(null, Map.of("ratio", new NumberSchema(null)), Set.of(), false);
    // "1e999" 超出 double 可表示范围，改写会变成 Infinity，因此不改写，由校验器拒绝。
    String tooLarge = "{\"ratio\":\"1e999\"}";
    assertEquals(tooLarge, InputNormalizer.normalize(tooLarge, numberSchema));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InputValidator.validate(
                InputNormalizer.normalize(tooLarge, numberSchema), numberSchema));
  }

  /** 验证原始入参字符串不可变，归一化不会对原输入产生任何破坏性副作用。 */
  @Test
  void preservesInputImmutability() {
    InputSchema schema =
        new InputSchema(null, Map.of("offset", new IntegerSchema(null)), Set.of(), false);
    String raw = "{\"offset\":\"12\"}";
    String normalized = InputNormalizer.normalize(raw, schema);
    assertEquals("{\"offset\":12}", normalized);
    assertEquals("{\"offset\":\"12\"}", raw);
  }

  /** 归一化后的数字参数仍必须通过严格 schema 校验；未声明字段与非法非数字文本由校验器拒绝。 */
  @Test
  void normalizedArgumentsStillPassStrictSchemaValidation() {
    InputSchema schema =
        new InputSchema(
            null,
            Map.of(
                "path", new StringSchema(null),
                "offset", new IntegerSchema(null)),
            Set.of("path"),
            false);
    String valid = "{\"path\":\"README.md\",\"offset\":\"12\"}";
    String normalizedValid = InputNormalizer.normalize(valid, schema);
    InputValidator.validate(normalizedValid, schema);

    // 未声明的附加字段不会被归一化移除，归一化后校验器以 additionalProperties 严格拒绝
    String extra = "{\"path\":\"README.md\",\"offset\":\"12\",\"extra\":true}";
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate(InputNormalizer.normalize(extra, schema), schema));

    // 非数字字符串不改写，校验器以类型不匹配严格拒绝
    String invalidNumeric = "{\"path\":\"README.md\",\"offset\":\"not-a-number\"}";
    assertEquals(invalidNumeric, InputNormalizer.normalize(invalidNumeric, schema));
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate(InputNormalizer.normalize(invalidNumeric, schema), schema));
  }

  /** 空白参数归一为 {}；顶层非 object 仍被拒绝。 */
  @Test
  void requiresTopLevelJsonObject() {
    InputSchema schema = new InputSchema(null, Map.of(), Set.of(), false);
    assertEquals("{}", InputNormalizer.normalize(" ", schema));
    assertThrows(IllegalArgumentException.class, () -> InputNormalizer.normalize("[]", schema));
    assertThrows(
        IllegalArgumentException.class, () -> InputNormalizer.normalize("not-json", schema));
  }
}
