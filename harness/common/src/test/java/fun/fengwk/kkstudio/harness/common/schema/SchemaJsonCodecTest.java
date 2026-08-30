package fun.fengwk.kkstudio.harness.common.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link SchemaJsonCodec} 的 canonical 确定性编解码、字段排序与非法 schema 拦截测试。 */
class SchemaJsonCodecTest {

  private final SchemaJsonCodec codec = new SchemaJsonCodec();

  /** 验证各类型 SchemaElement 组成的复杂 InputSchema 能被正确 round-trip 还原。 */
  @Test
  void roundTripsComplexInputSchema() {
    InputSchema original =
        new InputSchema(
            "complex input",
            Map.of(
                "str",
                new StringSchema("str desc"),
                "intVal",
                new IntegerSchema("int desc"),
                "numVal",
                new NumberSchema("num desc"),
                "flag",
                new BooleanSchema("bool desc"),
                "choice",
                new EnumSchema("enum desc", List.of("X", "Y", "Z")),
                "tags",
                new ArraySchema("arr desc", new StringSchema("item desc")),
                "nested",
                new ObjectSchema(
                    "obj desc",
                    Map.of("subKey", new StringSchema("sub desc")),
                    Set.of("subKey"),
                    false)),
            Set.of("str", "intVal"),
            true);

    String json = codec.encode(original);
    InputSchema decoded = codec.decode(json);

    assertEquals(original, decoded);
  }

  /** 验证 object schema 编码输出严格保证 properties 与 required 字段的字典序排序。 */
  @Test
  void encodingEnforcesDeterministicCanonicalOrdering() {
    InputSchema schema =
        new InputSchema(
            "order test",
            Map.of(
                "zeta", new StringSchema("z"),
                "alpha", new IntegerSchema("a"),
                "beta", new BooleanSchema("b")),
            Set.of("zeta", "alpha"),
            false);

    ObjectNode node = codec.encodeNode(schema);

    // 校验 properties key 顺序
    List<String> propertyKeys = new ArrayList<>();
    node.get("properties").fieldNames().forEachRemaining(propertyKeys::add);
    assertEquals(List.of("alpha", "beta", "zeta"), propertyKeys);

    // 校验 required 元素顺序
    List<String> requiredList = new ArrayList<>();
    node.get("required").forEach(item -> requiredList.add(item.asText()));
    assertEquals(List.of("alpha", "zeta"), requiredList);
  }

  /** 验证非法 schema 结构在解码期被拒绝。 */
  @Test
  void rejectsInvalidSchemaStructures() {
    // 缺失必选的 properties
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"type\":\"object\",\"required\":[],\"additionalProperties\":false}"));

    // 缺失 additionalProperties
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false,\"unknown\":1}"));

    // required 引用了未在 properties 声明的属性
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"type\":\"object\",\"properties\":{},\"required\":[\"missing\"],\"additionalProperties\":false}"));

    // enum 存在重复项
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"type\":\"object\",\"properties\":{\"c\":{\"type\":\"string\",\"enum\":[\"A\",\"A\"]}},\"required\":[],\"additionalProperties\":false}"));
  }
}
