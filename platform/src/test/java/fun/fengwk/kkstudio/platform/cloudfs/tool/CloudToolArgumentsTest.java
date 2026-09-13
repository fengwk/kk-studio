package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** 验证 {@link CloudToolArguments} 的严格类型校验与边界防御。 */
class CloudToolArgumentsTest {

  @Test
  void parseValidAndInvalidJson() {
    // 意图：验证有效对象被解析，null/blank/非对象/语法错误被拒绝
    JsonNode node = CloudToolArguments.parse("{\"k\":\"v\"}");
    assertEquals("v", node.get("k").asText());

    assertThrows(IllegalArgumentException.class, () -> CloudToolArguments.parse(null));
    assertThrows(IllegalArgumentException.class, () -> CloudToolArguments.parse("   "));
    assertThrows(IllegalArgumentException.class, () -> CloudToolArguments.parse("not a json"));
    assertThrows(IllegalArgumentException.class, () -> CloudToolArguments.parse("[1, 2, 3]"));
  }

  @Test
  void requireAndOptionalString() {
    // 意图：严格文本类型校验
    JsonNode node = CloudToolArguments.parse("{\"str\":\"hello\",\"num\":123,\"nil\":null}");

    assertEquals("hello", CloudToolArguments.requireString(node, "str"));
    assertThrows(
        IllegalArgumentException.class, () -> CloudToolArguments.requireString(node, "missing"));
    assertThrows(
        IllegalArgumentException.class, () -> CloudToolArguments.requireString(node, "nil"));
    assertThrows(
        IllegalArgumentException.class, () -> CloudToolArguments.requireString(node, "num"));

    assertEquals("hello", CloudToolArguments.optionalString(node, "str", "def"));
    assertEquals("def", CloudToolArguments.optionalString(node, "missing", "def"));
    assertEquals("def", CloudToolArguments.optionalString(node, "nil", "def"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.optionalString(node, "num", "def"));
  }

  @Test
  void requirePositiveAndNonNegativeLong() {
    // 意图：严格长整型类型与正负性校验，防御 BigInteger 截断回绕为合法正数
    JsonNode node =
        CloudToolArguments.parse(
            "{\"zero\":0,\"pos\":42,\"neg\":-1,\"str\":\"42\",\"float\":3.14,\"nil\":null,\"overflow\":18446744073709551617}");

    assertEquals(42L, CloudToolArguments.requirePositiveLong(node, "pos"));
    assertThrows(
        IllegalArgumentException.class, () -> CloudToolArguments.requirePositiveLong(node, "zero"));
    assertThrows(
        IllegalArgumentException.class, () -> CloudToolArguments.requirePositiveLong(node, "neg"));
    assertThrows(
        IllegalArgumentException.class, () -> CloudToolArguments.requirePositiveLong(node, "str"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requirePositiveLong(node, "float"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requirePositiveLong(node, "overflow"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requirePositiveLong(node, "missing"));
    assertThrows(
        IllegalArgumentException.class, () -> CloudToolArguments.requirePositiveLong(node, "nil"));

    assertEquals(0L, CloudToolArguments.requireNonNegativeLong(node, "zero"));
    assertEquals(42L, CloudToolArguments.requireNonNegativeLong(node, "pos"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requireNonNegativeLong(node, "neg"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requireNonNegativeLong(node, "str"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requireNonNegativeLong(node, "float"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requireNonNegativeLong(node, "overflow"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.requireNonNegativeLong(node, "missing"));
  }

  @Test
  void optionalBoundedIntAndBoolean() {
    // 意图：严格整型区间与布尔值校验，防御 32 位溢出回绕至合法区间的 BigInteger
    JsonNode node =
        CloudToolArguments.parse(
            "{\"count\":5,\"str\":\"5\",\"float\":5.0,\"flag\":true,\"flagStr\":\"true\",\"nil\":null,\"overflow\":4294967301}");

    assertEquals(5, CloudToolArguments.optionalBoundedInt(node, "count", 1, 1, 10));
    assertEquals(1, CloudToolArguments.optionalBoundedInt(node, "missing", 1, 1, 10));
    assertEquals(1, CloudToolArguments.optionalBoundedInt(node, "nil", 1, 1, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.optionalBoundedInt(node, "count", 1, 6, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.optionalBoundedInt(node, "overflow", 1, 1, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.optionalBoundedInt(node, "str", 1, 1, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.optionalBoundedInt(node, "float", 1, 1, 10));

    assertTrue(CloudToolArguments.optionalBoolean(node, "flag", false));
    assertFalse(CloudToolArguments.optionalBoolean(node, "missing", false));
    assertFalse(CloudToolArguments.optionalBoolean(node, "nil", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudToolArguments.optionalBoolean(node, "flagStr", false));
  }
}
