package fun.fengwk.kkstudio.common.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** {@link SchemaJsonCodec} 的 canonical 确定性编解码、字段排序与非法 schema 拦截测试。 */
class SchemaJsonCodecTest {

  private final SchemaJsonCodec codec = new SchemaJsonCodec();

  private static final JsonMapper FIXTURE_MAPPER = JsonMapper.builder().build();

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
    List<String> propertyKeys = new ArrayList<>(node.get("properties").propertyNames());
    assertEquals(List.of("alpha", "beta", "zeta"), propertyKeys);

    // 校验 required 元素顺序
    List<String> requiredList = new ArrayList<>();
    node.get("required").forEach(item -> requiredList.add(item.asString()));
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

  /**
   * 空白输入必须抛公开契约声明的 {@link IllegalArgumentException}（Jackson 3 对空/纯空白返回 MissingNode， 不允许其穿透）；null
   * 入参保持原有的 IllegalArgumentException 契约。
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\n", "\t", "\n\t", "\t \r\n"})
  void rejectsBlankInputWithIllegalArgument(String json) {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }

  /** JSON 字面量 {@code null} 与其它非对象根节点都无法构造出 InputSchema，必须抛 IllegalArgumentException 而非 NPE。 */
  @Test
  void rejectsJsonNullLiteralAndNonObjectRoot() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("1"));
  }

  /** 尾随 token 必须在解析阶段被拒绝：只解码首个对象而静默忽略剩余内容会破坏 canonical 契约。 */
  @Test
  void rejectsTrailingTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false} {}"));
  }

  /**
   * 受控非法 schema fixture：每条用例给出 schema JSON 与期望的错误片段，覆盖 type / properties / required /
   * additionalProperties / enum / items / 嵌套 object 等真实可达的拒绝分支。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSchemas")
  void rejectsInvalidSchemaFixtures(String name, String schemaJson, String errorFragment) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(schemaJson));
    assertTrue(
        error.getMessage().contains(errorFragment),
        () -> name + ": expected <" + errorFragment + "> but was <" + error.getMessage() + ">");
  }

  /** 从测试资源加载非法 schema 用例，转成 (name, schema JSON 文本, 期望错误片段) 参数。 */
  private static Stream<Arguments> invalidSchemas() {
    JsonNode fixtures = readFixture("invalid-schemas.json");
    List<Arguments> cases = new ArrayList<>();
    for (JsonNode fixture : fixtures) {
      cases.add(
          Arguments.of(
              fixture.get("name").asString(),
              FIXTURE_MAPPER.writeValueAsString(fixture.get("schema")),
              fixture.get("error").asString()));
    }
    return cases.stream();
  }

  private static JsonNode readFixture(String name) {
    try (InputStream in = SchemaJsonCodecTest.class.getResourceAsStream(name)) {
      assertNotNull(in, name + " must exist in test resources");
      return FIXTURE_MAPPER.readTree(in);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
