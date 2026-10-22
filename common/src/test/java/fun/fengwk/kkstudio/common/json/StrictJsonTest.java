package fun.fengwk.kkstudio.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class StrictJsonTest {

  record Sample(String text, UUID id, Instant at, String note, int count) {}

  record Decimal(BigDecimal value) {}

  record Renamed(String value) {}

  record Derived(int value) {
    boolean positive() {
      return value > 0;
    }

    public boolean isLarge() {
      return value > 100;
    }

    public int getDouble() {
      return value * 2;
    }
  }

  abstract static class RenamedMixin {
    @JsonProperty("v")
    abstract String value();
  }

  private static final String VALID =
      "{\"text\":\"x\",\"id\":\"11111111-1111-1111-1111-111111111111\","
          + "\"at\":\"2026-10-21T12:00:00Z\",\"note\":null,\"count\":3}";

  private final StrictJson json = StrictJson.create(builder -> {});

  @Test
  void roundTripsRecordsInComponentOrderWithExplicitNulls() {
    // 意图：写出按 record 组件顺序且显式写出 null，读回得到相等的值。
    Sample sample =
        new Sample(
            "x",
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-10-21T12:00:00Z"),
            null,
            3);

    assertEquals(VALID, json.write(sample, "sample"));
    assertEquals(sample, json.read(VALID, Sample.class, "sample"));
    JsonNode tree = json.tree(sample);
    assertEquals(sample, json.read(tree, Sample.class, "sample"));
    assertThrows(
        IllegalArgumentException.class,
        () -> json.read(json.tree(new Renamed("a")), Sample.class, "sample"));
  }

  @Test
  void rejectsStructuralDeviations() {
    // 意图：缺失字段（含可空字段）、未知字段、重复键、尾随内容与空文档都拒绝。
    String missingNullable = VALID.replace(",\"note\":null", "");
    String unknown = VALID.replace("}", ",\"extra\":1}");
    String duplicate = VALID.replace("{\"text\":\"x\"", "{\"text\":\"x\",\"text\":\"y\"");
    for (String input : new String[] {missingNullable, unknown, duplicate, VALID + " x", " "}) {
      assertThrows(IllegalArgumentException.class, () -> json.read(input, Sample.class, "sample"));
    }
  }

  @Test
  void rejectsScalarCoercion() {
    // 意图：字符串数字、小数与布尔都不会被强制转换为整数或文本；时间只接受 ISO-8601 文本。
    for (String input :
        new String[] {
          VALID.replace("\"count\":3", "\"count\":\"3\""),
          VALID.replace("\"count\":3", "\"count\":3.0"),
          VALID.replace("\"count\":3", "\"count\":null"),
          VALID.replace("\"text\":\"x\"", "\"text\":1"),
          VALID.replace("\"text\":\"x\"", "\"text\":true"),
          VALID.replace("\"2026-10-21T12:00:00Z\"", "1792584000"),
          VALID.replace("2026-10-21T12:00:00Z", "2026-10-21 12:00:00")
        }) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> json.read(input, Sample.class, "sample"));
      assertEquals("malformed sample JSON", error.getMessage());
    }
  }

  @Test
  void keepsDecimalPrecision() {
    // 意图：高精度小数与超出 double 范围的数值不经 double 丢失精度或数量级。
    assertEquals(
        new BigDecimal("1e309"), json.read("{\"value\":1e309}", Decimal.class, "decimal").value());
    assertInstanceOf(
        BigDecimal.class,
        json.read("{\"value\":0.10000000000000000001}", JsonNode.class, "decimal")
            .get("value")
            .decimalValue());
  }

  @Test
  void serializesOnlyRecordComponents() {
    // 意图：record 上的派生方法（含 is/get 前缀）不会变成 wire 字段。
    assertEquals("{\"value\":101}", StrictJson.standard().write(new Derived(101), "derived"));
  }

  @Test
  void readsListsStrictly() {
    // 意图：数组按元素类型严格绑定并返回不可变列表；元素结构错误或非数组都拒绝。
    StrictJson standard = StrictJson.standard();
    List<Renamed> values = standard.readList("[{\"value\":\"a\"}]", Renamed.class, "values");

    assertEquals(List.of(new Renamed("a")), values);
    assertThrows(UnsupportedOperationException.class, () -> values.add(new Renamed("b")));
    for (String input : new String[] {"[{\"value\":1}]", "{\"value\":\"a\"}", " "}) {
      assertThrows(
          IllegalArgumentException.class, () -> standard.readList(input, Renamed.class, "values"));
    }
  }

  @Test
  void customizerRegistersMixins() {
    // 意图：wire 名称差异由 mixin 表达，领域 record 不携带注解。
    StrictJson renamed =
        StrictJson.create(builder -> builder.addMixIn(Renamed.class, RenamedMixin.class));

    assertEquals("{\"v\":\"a\"}", renamed.write(new Renamed("a"), "renamed"));
    assertEquals(new Renamed("a"), renamed.read("{\"v\":\"a\"}", Renamed.class, "renamed"));
  }
}
