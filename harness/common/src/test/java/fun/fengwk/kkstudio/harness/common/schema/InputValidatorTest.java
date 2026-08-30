package fun.fengwk.kkstudio.harness.common.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link InputValidator} 对各种 SchemaElement（字符串、整数、数值、布尔、枚举、数组、对象）的递归匹配与非法输入拦截测试。 */
class InputValidatorTest {

  /** 验证基本标量字段类型不匹配时均被明确拒绝。 */
  @Test
  void rejectsScalarTypeMismatches() {
    InputSchema schema =
        new InputSchema(
            "scalars",
            Map.of(
                "str", new StringSchema("s"),
                "num", new NumberSchema("n"),
                "bool", new BooleanSchema("b")),
            Set.of(),
            false);

    assertThrows(
        IllegalArgumentException.class, () -> InputValidator.validate("{\"str\":123}", schema));
    assertThrows(
        IllegalArgumentException.class, () -> InputValidator.validate("{\"num\":\"abc\"}", schema));
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate("{\"bool\":\"true\"}", schema));

    assertDoesNotThrow(
        () -> InputValidator.validate("{\"str\":\"ok\",\"num\":3.14,\"bool\":true}", schema));
  }

  /** 验证 integer 字段只接受整型，拒绝浮点数与非数字。 */
  @Test
  void validatesIntegerField() {
    InputSchema schema =
        new InputSchema("int", Map.of("count", new IntegerSchema("c")), Set.of("count"), false);

    assertDoesNotThrow(() -> InputValidator.validate("{\"count\":10}", schema));
    assertThrows(
        IllegalArgumentException.class, () -> InputValidator.validate("{\"count\":10.5}", schema));
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate("{\"count\":\"10\"}", schema));
  }

  /** 验证枚举字段必须为 string 且值必须在 enum values 内。 */
  @Test
  void validatesEnumValues() {
    InputSchema schema =
        new InputSchema(
            "enum",
            Map.of("choice", new EnumSchema("c", List.of("A", "B"))),
            Set.of("choice"),
            false);

    assertDoesNotThrow(() -> InputValidator.validate("{\"choice\":\"A\"}", schema));
    assertDoesNotThrow(() -> InputValidator.validate("{\"choice\":\"B\"}", schema));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> InputValidator.validate("{\"choice\":\"C\"}", schema));
    assertTrue(error.getMessage().contains("must be one of [A, B]"));

    assertThrows(
        IllegalArgumentException.class, () -> InputValidator.validate("{\"choice\":1}", schema));
  }

  /** 验证数组与嵌套对象的递归校验，以及必填项与未知字段约束。 */
  @Test
  void validatesRecursiveArraysAndObjects() {
    InputSchema schema =
        new InputSchema(
            "nested",
            Map.of(
                "items",
                new ArraySchema(
                    "arr",
                    new ObjectSchema(
                        "obj",
                        Map.of(
                            "id", new IntegerSchema("id"),
                            "name", new StringSchema("name")),
                        Set.of("id"),
                        false))),
            Set.of("items"),
            false);

    // 合法嵌套
    assertDoesNotThrow(
        () ->
            InputValidator.validate(
                "{\"items\":[{\"id\":1,\"name\":\"alpha\"},{\"id\":2}]}", schema));

    // 缺少数组元素的必填属性 id
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate("{\"items\":[{\"name\":\"alpha\"}]}", schema));

    // 数组元素含有未声明的额外字段（additionalProperties=false）
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate("{\"items\":[{\"id\":1,\"extra\":true}]}", schema));

    // items 本身不是数组
    assertThrows(
        IllegalArgumentException.class,
        () -> InputValidator.validate("{\"items\":{\"id\":1}}", schema));

    // 顶层缺少必填项 items
    assertThrows(IllegalArgumentException.class, () -> InputValidator.validate("{}", schema));
  }
}
