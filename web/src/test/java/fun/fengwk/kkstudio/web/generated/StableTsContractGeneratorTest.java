package fun.fengwk.kkstudio.web.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.generated.StableTsContractGenerator.ContractItem;
import fun.fengwk.kkstudio.web.generated.StableTsContractGenerator.Direction;
import fun.fengwk.kkstudio.web.generated.StableTsContractGenerator.EnumSpec;
import fun.fengwk.kkstudio.web.generated.StableTsContractGenerator.ObjectSpec;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 生成器通用分支的实测覆盖：用真实 HTTP mapper 的正反序列化行为验证 Long/string、Instant 秒、NON_NULL、ALWAYS 必要
 * null、方向/WRITE_ONLY、枚举 {@code @JsonValue}、集合/Map，并确认未知类型与未支持的定制形态固定失败。只覆盖本范围真正 需要的分支。
 */
class StableTsContractGeneratorTest {

  private static final JsonMapper MAPPER = StrictHttpJsonMapperTestSupport.get();

  /** 把 String 属性写成 number 的定制序列化器：wire 与 JavaType 不再一致，生成器必须拒绝。 */
  static final class NameAsLengthSerializer extends ValueSerializer<String> {

    @Override
    public void serialize(String value, JsonGenerator gen, SerializationContext ctxt) {
      gen.writeNumber(value.length());
    }
  }

  /** fixture 枚举：wire 值是小驼峰而非 name()，用来证明生成器走 mapper 序列化。 */
  enum FixtureKind {
    ALPHA("alpha"),
    BETA("beta");

    private final String wire;

    FixtureKind(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  /** 扩展枚举：用于证明枚举取值集合变化会让生成产物改变（漂移）。 */
  enum ExtendedFixtureKind {
    ALPHA("alpha"),
    BETA("beta"),
    GAMMA("gamma");

    private final String wire;

    ExtendedFixtureKind(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  record NestedFixture(String id) {}

  /** 两个常量映射到同一 wire 值：必须固定失败，绝不产出有歧义的 enum。 */
  enum DuplicateWireKind {
    FIRST("same"),
    SECOND("same");

    private final String wire;

    DuplicateWireKind(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  /** 仅 string 键的 Map 受支持；其它键类型固定失败。 */
  record NonStringKeyMapFixture(Map<Integer, String> labels) {}

  /** 同时作为响应与请求的类型不得有方向专属属性。 */
  record WriteOnlyBothFixture(
      String id, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String secret) {}

  record UnregisteredEnumFixture(UnsupportedKind kind) {}

  enum UnsupportedKind {
    ONLY
  }

  record UnsupportedTypeFixture(Object payload) {}

  /** 属性级定制序列化：String 被写成 number，生成器必须拒绝而不是当 string。 */
  record CustomSerializerFixture(
      @JsonSerialize(using = NameAsLengthSerializer.class) String name) {}

  /** 属性级 {@code @JsonFormat} 形态：String 被写成 number，当前不支持，必须拒绝。 */
  record JsonFormatShapeFixture(@JsonFormat(shape = JsonFormat.Shape.NUMBER) String name) {}

  /** 属性级日期时间格式化：当前不支持，必须拒绝。 */
  record JsonFormatPatternFixture(@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") Instant at) {}

  /** class 级 ALWAYS：wire 上键必然存在且可为 null，真实 mapper 已验证。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  record ClassLevelAlwaysFixture(String id) {}

  /** class 级 NON_EMPTY：当前不支持，必须拒绝而不是被当成 NON_NULL。 */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  record ClassLevelNonEmptyFixture(String id) {}

  /** class 级定制序列化：会改变整条 wire，当前不支持，必须拒绝。 */
  @JsonSerialize(using = NameAsLengthSerializer.class)
  record ClassLevelSerializerFixture(String name) {}

  /** class 级类型信息：会新增类型判别键，当前不支持，必须拒绝。 */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  record TypeInfoFixture(String id) {}

  /** class 级 {@code @JsonValue}：整个对象退化成标量，当前不支持，必须拒绝。 */
  record JsonValueFixture(String id) {

    @JsonValue
    public String wire() {
      return id;
    }
  }

  /** 属性级 {@code @JsonUnwrapped}：会打平嵌套结构，当前不支持，必须拒绝。 */
  record UnwrappedFixture(@JsonUnwrapped NestedFixture nested) {}

  /** wire 值含换行：无法内联成单引号 TS 字面量，必须固定失败。 */
  enum UnsafeWireKind {
    NEWLINE("null\nvalue"),
    LINE_SEPARATOR("a\u2028b");

    private final String wire;

    UnsafeWireKind(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  /** wire 值含单引号与反斜线：必须被正确转义成合法 TS 字面量。 */
  enum EscapedWireKind {
    QUOTED("it's"),
    BACKSLASH("a\\b");

    private final String wire;

    EscapedWireKind(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  /** 多分支 fixture：name 是 NON_NULL 可省略，always 是 ALWAYS 必要 null，secret 是 WRITE_ONLY。 */
  record SampleFixture(
      String name,
      Long epoch,
      Instant at,
      long prim,
      boolean flag,
      List<String> tags,
      Map<String, Integer> counts,
      FixtureKind kind,
      NestedFixture nested,
      @JsonInclude(JsonInclude.Include.ALWAYS) String always,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String secret) {}

  private static final List<ContractItem> FIXTURE_ITEMS =
      List.of(
          new EnumSpec("FixtureKind", "FIXTURE_KIND_VALUES", FixtureKind.class),
          new ObjectSpec("NestedFixture", NestedFixture.class, Direction.RESPONSE, List.of("id")),
          new ObjectSpec(
              "SampleFixture",
              SampleFixture.class,
              Direction.RESPONSE,
              List.of("epoch", "at", "tags", "counts", "kind", "nested", "always")));

  private static StableTsContractGenerator generator(List<ContractItem> items) {
    return new StableTsContractGenerator(MAPPER, items);
  }

  /** 测试意图：Long/long 走 LongToString 配置成为 string，Instant 是 epoch 秒 number，集合/Map/嵌套按需投影。 */
  @Test
  void mapsLongInstantCollectionsAndNestedObjects() {
    String ts = generator(FIXTURE_ITEMS).generate();

    assertTrue(ts.contains("  epoch: string\n"), ts);
    assertTrue(ts.contains("  prim: string\n"), ts);
    assertTrue(ts.contains("  at: number\n"), ts);
    assertTrue(ts.contains("  flag: boolean\n"), ts);
    assertTrue(ts.contains("  tags: string[]\n"), ts);
    assertTrue(ts.contains("  counts: Record<string, number>\n"), ts);
    assertTrue(ts.contains("  nested: NestedFixture\n"), ts);

    SampleFixture sample =
        new SampleFixture(
            null,
            5L,
            Instant.parse("2026-03-01T12:00:00Z"),
            7L,
            true,
            List.of("a"),
            Map.of("k", 1),
            FixtureKind.ALPHA,
            new NestedFixture("nested-id"),
            null,
            "sec");
    String json = MAPPER.writeValueAsString(sample);
    JsonNode wire = MAPPER.readTree(json);
    assertEquals("5", wire.get("epoch").asString());
    assertEquals("7", wire.get("prim").asString());
    // Instant 是 epoch 秒 number：不是 ISO 文本，也不是毫秒。
    assertTrue(wire.get("at").isNumber(), json);
    assertFalse(wire.get("at").isString(), json);
    assertEquals(1772366400d, wire.get("at").doubleValue());
    assertFalse(json.contains("2026-03-01"), json);
    assertEquals("[\"a\"]", wire.get("tags").toString());
    assertEquals("{\"k\":1}", wire.get("counts").toString());
    assertEquals("{\"id\":\"nested-id\"}", wire.get("nested").toString());
  }

  /** 测试意图：NON_NULL 可省略与 ALWAYS 必要 null 与真实序列化一致。 */
  @Test
  void nonNullOmittableAndAlwaysIncludedMatchTheRealMapper() {
    String ts = generator(FIXTURE_ITEMS).generate();

    assertTrue(ts.contains("  name?: string\n"), ts);
    assertTrue(ts.contains("  always: string | null\n"), ts);

    SampleFixture probe =
        new SampleFixture(
            null,
            1L,
            null,
            1L,
            false,
            List.of(),
            Map.of(),
            FixtureKind.BETA,
            new NestedFixture("x"),
            null,
            "sec");
    JsonNode wire = MAPPER.readTree(MAPPER.writeValueAsString(probe));
    assertFalse(wire.has("name"), "NON_NULL nullable property must be omitted, not null");
    assertFalse(wire.has("at"), "NON_NULL null Instant must be omitted");
    assertTrue(wire.has("always"), "ALWAYS property key must always be present");
    assertTrue(wire.get("always").isNull());
  }

  /** 测试意图：枚举 wire 值由 mapper 序列化真实常量得到，小驼峰而非 name()。 */
  @Test
  void enumValuesComeFromRealSerializationNotEnumNames() {
    String ts = generator(FIXTURE_ITEMS).generate();

    assertTrue(ts.contains("| 'alpha'\n"), ts);
    assertTrue(ts.contains("| 'beta'\n"), ts);
    assertTrue(
        ts.contains(
            "export const FIXTURE_KIND_VALUES: readonly FixtureKind[] = [\n  'alpha',\n  'beta',\n]\n"),
        ts);
    assertTrue(ts.contains("  kind: FixtureKind\n"), ts);
    assertEquals("\"alpha\"", MAPPER.writeValueAsString(FixtureKind.ALPHA));
  }

  /** 测试意图：WRITE_ONLY 属性在响应方向被排除、在请求方向存在，并由真实序列化证明。 */
  @Test
  void writeOnlyPropertyIsExcludedFromTheResponseAndKeptInTheRequest() {
    String response = generator(FIXTURE_ITEMS).generate();
    String request =
        generator(
                List.of(
                    new EnumSpec("FixtureKind", "FIXTURE_KIND_VALUES", FixtureKind.class),
                    new ObjectSpec(
                        "NestedFixture", NestedFixture.class, Direction.RESPONSE, List.of("id")),
                    new ObjectSpec(
                        "SampleFixture", SampleFixture.class, Direction.REQUEST, List.of())))
            .generate();

    assertFalse(response.contains("secret"), response);
    assertTrue(request.contains("  secret?: string\n"), request);

    SampleFixture probe =
        new SampleFixture(
            "n",
            1L,
            null,
            1L,
            false,
            List.of(),
            Map.of(),
            FixtureKind.BETA,
            new NestedFixture("x"),
            null,
            "sec");
    JsonNode wire = MAPPER.readTree(MAPPER.writeValueAsString(probe));
    assertFalse(wire.has("secret"), "WRITE_ONLY property must not appear in the response");
  }

  /** 测试意图：未注册的枚举与不支持的类型固定失败，绝不静默回退成 any。 */
  @Test
  void unregisteredEnumAndUnsupportedTypeFailClosed() {
    IllegalArgumentException enumFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "UnregisteredEnumFixture",
                                UnregisteredEnumFixture.class,
                                Direction.RESPONSE,
                                List.of("kind"))))
                    .generate());
    assertTrue(
        enumFailure.getMessage().contains("not part of the generated contract"),
        enumFailure.getMessage());

    IllegalArgumentException typeFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "UnsupportedTypeFixture",
                                UnsupportedTypeFixture.class,
                                Direction.RESPONSE,
                                List.of("payload"))))
                    .generate());
    assertTrue(
        typeFailure.getMessage().contains("unsupported wire type"), typeFailure.getMessage());
  }

  /** 测试意图：必填清单里出现不存在的属性（例如 Java 字段被改名或删除）时立即失败。 */
  @Test
  void requiredManifestRejectsUnknownPropertyNames() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "NestedFixture",
                                NestedFixture.class,
                                Direction.RESPONSE,
                                List.of("identifier"))))
                    .generate());
    assertTrue(failure.getMessage().contains("not real wire properties"), failure.getMessage());
  }

  /** 测试意图：枚举取值集合变化会改变生成产物，因此漂移门禁必然失败。 */
  @Test
  void enumValueChangesAlterTheGeneratedArtifact() {
    String before =
        generator(List.of(new EnumSpec("FixtureKind", "FIXTURE_KIND_VALUES", FixtureKind.class)))
            .generate();
    String after =
        generator(
                List.of(
                    new EnumSpec("FixtureKind", "FIXTURE_KIND_VALUES", ExtendedFixtureKind.class)))
            .generate();

    assertFalse(
        before.equals(after), "a changed enum value set must change the generated artifact");
    assertFalse(before.contains("| 'gamma'\n"), before);
    assertTrue(after.contains("| 'gamma'\n"), after);
  }

  /** 测试意图：同一枚举内两个常量映射到同一 wire 值必须固定失败，绝不产出有歧义的 enum。 */
  @Test
  void duplicateEnumWireValuesFailClosed() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new EnumSpec(
                                "DuplicateWireKind",
                                "DUPLICATE_WIRE_KIND_VALUES",
                                DuplicateWireKind.class)))
                    .generate());
    assertTrue(failure.getMessage().contains("duplicate wire value"), failure.getMessage());
  }

  /** 测试意图：Direction.BOTH 的类型一旦出现方向专属属性（WRITE_ONLY）就固定失败。 */
  @Test
  void bothDirectionsRejectDirectionSpecificProperties() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "WriteOnlyBothFixture",
                                WriteOnlyBothFixture.class,
                                Direction.BOTH,
                                List.of("id"))))
                    .generate());
    assertTrue(
        failure.getMessage().contains("differ from deserialization properties"),
        failure.getMessage());
  }

  /** 测试意图：只有 string 键的 Map 受支持，其它键类型固定失败。 */
  @Test
  void nonStringMapKeyFailsClosed() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "NonStringKeyMapFixture",
                                NonStringKeyMapFixture.class,
                                Direction.RESPONSE,
                                List.of("labels"))))
                    .generate());
    assertTrue(failure.getMessage().contains("string-keyed maps"), failure.getMessage());
  }

  /** 测试意图：清单之外的 ContractItem 类型必须固定失败，而不是被静默跳过。 */
  @Test
  void unsupportedContractItemsFailClosed() {
    ContractItem unknown = () -> "UnknownItem";

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> generator(List.of(unknown)).generate());
    assertTrue(failure.getMessage().contains("unsupported contract item"), failure.getMessage());
  }

  /** 测试意图：去掉 Long→String 的 mapper 与生成器的标量预设不再一致，构造时必须固定失败。 */
  @Test
  void mapperWithoutLongToStringFailsClosed() {
    JsonMapper drifted =
        JsonMapper.builder().enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    assertEquals("1", drifted.writeValueAsString(1L), "fixture mapper must write long as a number");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> new StableTsContractGenerator(drifted));

    assertTrue(failure.getMessage().contains("wire profile drifted"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("Long -> number, expected string"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("long -> number, expected string"), failure.getMessage());
  }

  /** 测试意图：Instant 变成 ISO 文本（非 epoch 秒 number）时，构造必须固定失败。 */
  @Test
  void mapperWithIsoInstantFailsClosed() {
    SimpleModule longToString = new SimpleModule("LongToStringModule");
    longToString.addSerializer(Long.class, new ToStringSerializer(Long.class));
    longToString.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
    JsonMapper drifted = JsonMapper.builder().addModule(longToString).build();
    assertTrue(
        drifted.writeValueAsString(Instant.ofEpochSecond(1L)).startsWith("\""),
        "fixture mapper must write Instant as ISO text");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> new StableTsContractGenerator(drifted));

    assertTrue(failure.getMessage().contains("wire profile drifted"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("Instant -> string, expected number"), failure.getMessage());
  }

  /** 测试意图：属性级定制序列化把 String 写成 number 时必须失败，而不是静默按 JavaType 当 string。 */
  @Test
  void propertyLevelCustomSerializerFailsClosed() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "CustomSerializerFixture",
                                CustomSerializerFixture.class,
                                Direction.RESPONSE,
                                List.of("name"))))
                    .generate());
    assertTrue(failure.getMessage().contains("custom @JsonSerialize"), failure.getMessage());
    assertTrue(failure.getMessage().contains("CustomSerializerFixture.name"), failure.getMessage());
  }

  /** 测试意图：属性级 @JsonFormat 的 shape/日期时间定制当前不支持，必须失败。 */
  @Test
  void propertyLevelJsonFormatFailsClosed() {
    IllegalStateException shapeFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "JsonFormatShapeFixture",
                                JsonFormatShapeFixture.class,
                                Direction.RESPONSE,
                                List.of("name"))))
                    .generate());
    assertTrue(
        shapeFailure.getMessage().contains("@JsonFormat shape/date customization"),
        shapeFailure.getMessage());

    IllegalStateException patternFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "JsonFormatPatternFixture",
                                JsonFormatPatternFixture.class,
                                Direction.RESPONSE,
                                List.of("at"))))
                    .generate());
    assertTrue(
        patternFailure.getMessage().contains("@JsonFormat shape/date customization"),
        patternFailure.getMessage());
  }

  /** 测试意图：class 级 ALWAYS 与真实 wire 一致——键必然出现且可为 null。 */
  @Test
  void classLevelAlwaysMatchesTheRealWire() {
    String ts =
        generator(
                List.of(
                    new ObjectSpec(
                        "ClassLevelAlwaysFixture",
                        ClassLevelAlwaysFixture.class,
                        Direction.RESPONSE,
                        List.of())))
            .generate();

    assertTrue(ts.contains("  id: string | null\n"), ts);
    JsonNode wire = MAPPER.readTree(MAPPER.writeValueAsString(new ClassLevelAlwaysFixture(null)));
    assertTrue(wire.has("id"), "class-level ALWAYS key must always be present");
    assertTrue(wire.get("id").isNull());
  }

  /** 测试意图：class 级非默认且未支持的 @JsonInclude 形态必须失败，不能被当成 NON_NULL。 */
  @Test
  void unsupportedClassLevelJsonIncludeFailsClosed() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new ObjectSpec(
                                "ClassLevelNonEmptyFixture",
                                ClassLevelNonEmptyFixture.class,
                                Direction.RESPONSE,
                                List.of())))
                    .generate());
    assertTrue(failure.getMessage().contains("unsupported @JsonInclude"), failure.getMessage());
    assertTrue(failure.getMessage().contains("NON_EMPTY"), failure.getMessage());
  }

  /** 测试意图：class 级定制序列化/@JsonTypeInfo/@JsonValue 改变整条 wire，必须失败。 */
  @Test
  void unsupportedRootShapesFailClosed() {
    assertRootFailure(
        "ClassLevelSerializerFixture",
        ClassLevelSerializerFixture.class,
        "class-level @JsonSerialize");
    assertRootFailure("TypeInfoFixture", TypeInfoFixture.class, "@JsonTypeInfo");
    assertRootFailure("JsonValueFixture", JsonValueFixture.class, "@JsonValue");
  }

  /** 测试意图：属性级 @JsonUnwrapped 会打平嵌套结构，必须失败。 */
  @Test
  void unwrappedPropertyFailsClosed() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new EnumSpec("FixtureKind", "FIXTURE_KIND_VALUES", FixtureKind.class),
                            new ObjectSpec(
                                "NestedFixture",
                                NestedFixture.class,
                                Direction.RESPONSE,
                                List.of("id")),
                            new ObjectSpec(
                                "UnwrappedFixture",
                                UnwrappedFixture.class,
                                Direction.RESPONSE,
                                List.of("nested"))))
                    .generate());
    assertTrue(failure.getMessage().contains("@JsonUnwrapped"), failure.getMessage());
    assertTrue(failure.getMessage().contains("UnwrappedFixture.nested"), failure.getMessage());
  }

  /** 测试意图：enum wire 值含换行/行分隔符时无法内联成 TS 字面量，必须失败并指出字符码点。 */
  @Test
  void enumWireValuesWithUnsupportedCharactersFailClosed() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(
                        List.of(
                            new EnumSpec(
                                "UnsafeWireKind", "UNSAFE_WIRE_KIND_VALUES", UnsafeWireKind.class)))
                    .generate());

    assertTrue(failure.getMessage().contains("unsupported character U+000A"), failure.getMessage());
  }

  /** 测试意图：单引号与反斜线必须被正确转义，生成合法 TS 字面量。 */
  @Test
  void enumWireValuesWithQuotesAndBackslashesAreEscaped() {
    String ts =
        generator(
                List.of(
                    new EnumSpec(
                        "EscapedWireKind", "ESCAPED_WIRE_KIND_VALUES", EscapedWireKind.class)))
            .generate();

    assertTrue(ts.contains("| 'it\\'s'\n"), ts);
    assertTrue(ts.contains("| 'a\\\\b'\n"), ts);
  }

  private static void assertRootFailure(String tsName, Class<?> javaType, String expectedMessage) {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                generator(List.of(new ObjectSpec(tsName, javaType, Direction.RESPONSE, List.of())))
                    .generate());
    assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
  }
}
