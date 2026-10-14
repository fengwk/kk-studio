package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.NumberSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaElement;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 受控配置 schema：默认值唯一来源、sparse 校验、只补缺失字段、严格类型与 scoped secrets。 */
class PluginConfigSchemaTest {

  private static final Map<String, SchemaElement> PROPERTIES =
      Map.of(
          "enabled", new BooleanSchema(null),
          "count", new IntegerSchema(null),
          "name", new StringSchema(null),
          "tags", new ArraySchema(null, new StringSchema(null)),
          "nested",
              new ObjectSchema(
                  null, Map.of("mode", new EnumSchema(null, List.of("a", "b"))), Set.of(), false));

  private static final String DEFAULTS =
      "{\"count\":5,\"enabled\":true,\"nested\":{\"mode\":\"a\"},\"tags\":[\"a\",\"b\"]}";

  private static PluginConfigSchema schema(String defaultsJson, Map<String, String> secrets) {
    return new PluginConfigSchema(
        new InputSchema(null, PROPERTIES, Set.of(), false), defaultsJson, secrets);
  }

  private static void assertJsonEquals(String expected, String actual) {
    assertEquals(JsonValues.readTree(expected), JsonValues.readTree(actual));
  }

  @Test
  void exposesDefaultsAndFrozenSecrets() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of("apiKey", "static API key"));

    assertJsonEquals(DEFAULTS, schema.defaultsJson());
    assertEquals(PROPERTIES, schema.valuesSchema().properties());
    assertEquals(Map.of("apiKey", "static API key"), schema.secrets());
  }

  /** 默认值必须类型正确且无未知字段：默认值是唯一来源，因此其自身错误必须在构造期失败。 */
  @Test
  void rejectsIllegalDefaults() {
    assertThrows(IllegalArgumentException.class, () -> schema("{\"unknown\":1}", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> schema("{\"count\":\"5\"}", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> schema("[]", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> schema(null, Map.of()));
  }

  /** 默认值同样受 64 KiB 上限，越界即构造期失败。 */
  @Test
  void rejectsDefaultsBeyondTheUtf8ByteBudget() {
    String oversized = "{\"name\":\"" + "a".repeat(PluginConfig.MAX_VALUES_UTF8_BYTES) + "\"}";

    assertThrows(IllegalArgumentException.class, () -> schema(oversized, Map.of()));
  }

  @Test
  void rejectsSchemaThatAllowsAdditionalPropertiesOrSecretCollisions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PluginConfigSchema(
                new InputSchema(null, PROPERTIES, Set.of(), true), "{}", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> schema("{}", Map.of("name", "collision")));
    assertThrows(IllegalArgumentException.class, () -> schema("{}", Map.of(" ", "blank field")));
    assertThrows(IllegalArgumentException.class, () -> schema("{}", Map.of("apiKey", " ")));
    InputSchema openNested =
        new InputSchema(
            null,
            Map.of("nested", new ObjectSchema(null, Map.of(), Set.of(), true)),
            Set.of(),
            false);
    assertThrows(
        IllegalArgumentException.class, () -> new PluginConfigSchema(openNested, "{}", Map.of()));
  }

  /** 解析只给缺失字段递归补默认，顶层与嵌套都生效，用户已提供的字段保持不变。 */
  @Test
  void fillsMissingFieldsRecursivelyWithSchemaDefaults() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of());

    assertJsonEquals(DEFAULTS, schema.resolveValues("{}"));
    assertJsonEquals(
        "{\"count\":5,\"enabled\":true,\"nested\":{\"mode\":\"a\"},\"tags\":[\"a\",\"b\"]}",
        schema.resolveValues("{\"nested\":{}}"));
    assertJsonEquals(
        "{\"count\":5,\"enabled\":false,\"nested\":{\"mode\":\"b\"},\"tags\":[\"a\",\"b\"]}",
        schema.resolveValues("{\"enabled\":false,\"nested\":{\"mode\":\"b\"}}"));
  }

  /** 嵌套对象默认值只在默认值声明了同名子对象时才递归补，缺失声明时保持用户输入不变。 */
  @Test
  void appliesObjectDefaultsOnlyWhenDeclared() {
    Map<String, SchemaElement> properties =
        Map.of(
            "obj",
            new ObjectSchema(
                null,
                Map.of("x", new IntegerSchema(null), "y", new IntegerSchema(null)),
                Set.of(),
                false));
    PluginConfigSchema schema =
        new PluginConfigSchema(new InputSchema(null, properties, Set.of(), false), "{}", Map.of());

    assertJsonEquals("{}", schema.resolveValues("{}"));
    assertJsonEquals("{\"obj\":{\"x\":1}}", schema.resolveValues("{\"obj\":{\"x\":1}}"));
  }

  /** 显式 null 不算缺失：既不会被默认值替换，也会被同一 schema 判为类型错误。 */
  @Test
  void treatsExplicitNullAsProvidedAndRejectsIt() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of());

    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("{\"enabled\":null}"));
    assertThrows(
        IllegalArgumentException.class, () -> schema.resolveValues("{\"nested\":{\"mode\":null}}"));
  }

  /** 不做数字/布尔字符串容错：非法标量直接失败。 */
  @Test
  void rejectsStringEncodedScalars() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of());

    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("{\"count\":\"5\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> schema.resolveValues("{\"enabled\":\"true\"}"));
    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("{\"nested\":\"a\"}"));
  }

  /** 默认数组整体作为默认值，用户提供数组不与默认逐项合并。 */
  @Test
  void doesNotMergeArraysWithDefaults() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of());

    assertJsonEquals(
        "{\"count\":5,\"enabled\":true,\"nested\":{\"mode\":\"a\"},\"tags\":[\"x\"]}",
        schema.resolveValues("{\"tags\":[\"x\"]}"));
    assertJsonEquals(DEFAULTS, schema.resolveValues("{}"));
  }

  /** 未知字段在顶层与嵌套对象都被拒绝，不允许 additionalProperties 放行。 */
  @Test
  void rejectsUnknownFieldsAtEveryLevel() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of());

    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("{\"other\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> schema.resolveValues("{\"nested\":{\"mode\":\"a\",\"extra\":1}}"));
    assertThrows(
        IllegalArgumentException.class, () -> schema.resolveValues("{\"name\":{\"a\":1}}"));
  }

  @Test
  void rejectsMalformedJson() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> schema.resolveValues("{\"enabled\":true,\"enabled\":false}"));
    assertThrows(
        IllegalArgumentException.class, () -> schema.resolveValues("{\"enabled\":true}{}"));
    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("[]"));
    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("  "));
    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues(null));
  }

  /** 归一化后的文本同受 64 KiB 上限：默认值与用户值来自不同字段时真实叠加越界。 */
  @Test
  void rejectsResolvedValuesBeyondTheUtf8ByteBudget() {
    String big = "x".repeat(40_000);
    Map<String, SchemaElement> properties =
        Map.of("left", new StringSchema(null), "right", new StringSchema(null));
    PluginConfigSchema schema =
        new PluginConfigSchema(
            new InputSchema(null, properties, Set.of(), false),
            "{\"right\":\"" + big + "\"}",
            Map.of());

    assertThrows(
        IllegalArgumentException.class, () -> schema.resolveValues("{\"left\":\"" + big + "\"}"));
  }

  /** sparse 默认值可以缺省嵌套 required，但最终配置仍必须满足全部 required。 */
  @Test
  void dropsRequiredForSparseDefaultsButNotForResolvedValues() {
    Map<String, SchemaElement> properties =
        Map.of(
            "nested",
            new ObjectSchema(
                null,
                Map.of("mode", new StringSchema(null), "limit", new IntegerSchema(null)),
                Set.of("mode", "limit"),
                false));
    PluginConfigSchema schema =
        new PluginConfigSchema(
            new InputSchema(null, properties, Set.of(), false),
            "{\"nested\":{\"mode\":\"a\"}}",
            Map.of());

    assertJsonEquals("{\"nested\":{\"mode\":\"a\"}}", schema.defaultsJson());
    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("{}"));
    assertJsonEquals(
        "{\"nested\":{\"mode\":\"b\",\"limit\":1}}",
        schema.resolveValues("{\"nested\":{\"limit\":1,\"mode\":\"b\"}}"));
  }

  /** 数组中的对象也必须满足必填约束；sparse 默认值不放宽最终配置。 */
  @Test
  void validatesRequiredFieldsInsideArrays() {
    InputSchema values =
        new InputSchema(
            null,
            Map.of(
                "items",
                new ArraySchema(
                    null,
                    new ObjectSchema(
                        null, Map.of("name", new StringSchema(null)), Set.of("name"), false))),
            Set.of(),
            false);
    PluginConfigSchema schema = new PluginConfigSchema(values, "{\"items\":[{}]}", Map.of());
    assertThrows(IllegalArgumentException.class, () -> schema.resolveValues("{}"));
    assertJsonEquals(
        "{\"items\":[{\"name\":\"provided\"}]}",
        schema.resolveValues("{\"items\":[{\"name\":\"provided\"}]}"));
  }

  /** Number 值按精确 BigDecimal/BigInteger 读回：超出 double 范围、高精度与超大整数都不失真。 */
  @Test
  void preservesExactNumbers() {
    Map<String, SchemaElement> properties =
        Map.of(
            "ratio", new NumberSchema(null),
            "tiny", new NumberSchema(null),
            "precise", new NumberSchema(null),
            "big", new NumberSchema(null));
    PluginConfigSchema schema =
        new PluginConfigSchema(new InputSchema(null, properties, Set.of(), false), "{}", Map.of());

    String resolved =
        schema.resolveValues(
            "{\"ratio\":1e309,\"tiny\":1e-324,"
                + "\"precise\":0.12345678901234567890123,"
                + "\"big\":123456789012345678901234567890}");
    JsonNode node = JsonValues.readTree(resolved);

    assertEquals(0, new BigDecimal("1e309").compareTo(node.get("ratio").decimalValue()));
    assertEquals(0, new BigDecimal("1e-324").compareTo(node.get("tiny").decimalValue()));
    assertEquals(
        0,
        new BigDecimal("0.12345678901234567890123").compareTo(node.get("precise").decimalValue()));
    assertEquals(
        0,
        new BigDecimal("123456789012345678901234567890").compareTo(node.get("big").decimalValue()));
  }

  @Test
  void staticKeyMustNameADeclaredSecret() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of("apiKey", "static API key"));

    schema.requireDeclaredSecrets(new PluginAuthSpec.StaticKey("apiKey", Set.of()));
    schema.requireDeclaredSecrets(new PluginAuthSpec.None());
    schema.requireDeclaredSecrets(new PluginAuthSpec.DeepLink(List.of("CN")));

    assertThrows(
        IllegalStateException.class,
        () -> schema.requireDeclaredSecrets(new PluginAuthSpec.StaticKey("other", Set.of())));
    assertThrows(NullPointerException.class, () -> schema.requireDeclaredSecrets(null));
  }

  /** StaticKey 的 contextFields 只能命名 valuesSchema 已声明的普通顶层字段。 */
  @Test
  void staticKeyContextFieldsMustBeDeclaredProperties() {
    PluginConfigSchema schema = schema(DEFAULTS, Map.of("apiKey", "static API key"));

    schema.requireDeclaredSecrets(new PluginAuthSpec.StaticKey("apiKey", Set.of("name")));

    assertThrows(
        IllegalStateException.class,
        () ->
            schema.requireDeclaredSecrets(
                new PluginAuthSpec.StaticKey("apiKey", Set.of("apiKey"))));
    assertThrows(
        IllegalStateException.class,
        () ->
            schema.requireDeclaredSecrets(
                new PluginAuthSpec.StaticKey("apiKey", Set.of("unknown"))));
  }
}
