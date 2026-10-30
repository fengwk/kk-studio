package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;

import java.math.BigInteger;

/** 测试意图：验证 ProviderJsonHelper 的严格 JSON 对象解析、宽松对象解析与非负 usage 整数解析。 */
class ProviderJsonHelperTest {

  @Test
  @DisplayName("parseStrictJsonObject: 正常对象解析与非法输入拒绝")
  void testParseStrictJsonObject() {
    String errorMsg = "invalid object";

    // 正常对象
    JsonNode ok = ProviderJsonHelper.parseStrictJsonObject("{\"key\":\"value\"}", errorMsg);
    assertNotNull(ok);
    assertTrue(ok.isObject());
    assertEquals("value", ok.get("key").asText());

    // null 与 blank
    assertInvalidRequest(errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject(null, errorMsg));
    assertInvalidRequest(errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject("   ", errorMsg));

    // 非法语法
    assertInvalidRequest(
        errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject("{broken", errorMsg));

    // 非对象（数组、数字、字符串）
    assertInvalidRequest(
        errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject("[1, 2]", errorMsg));
    assertInvalidRequest(errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject("123", errorMsg));
    assertInvalidRequest(
        errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject("\"str\"", errorMsg));

    // 尾随 token
    assertInvalidRequest(
        errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject("{\"a\":1} trailing", errorMsg));

    // 重复键
    assertInvalidRequest(
        errorMsg, () -> ProviderJsonHelper.parseStrictJsonObject("{\"a\":1,\"a\":2}", errorMsg));
  }

  @Test
  @DisplayName("parseStrictJson: 允许非对象 JSON 但拒绝尾随 token")
  void testParseStrictJson() {
    String errorMsg = "invalid json";

    // 对象与数组
    assertNotNull(ProviderJsonHelper.parseStrictJson("{\"a\":1}", errorMsg));
    assertNotNull(ProviderJsonHelper.parseStrictJson("[1, 2, 3]", errorMsg));
    assertNotNull(ProviderJsonHelper.parseStrictJson("\"hello\"", errorMsg));

    // null / blank / broken
    assertInvalidRequest(errorMsg, () -> ProviderJsonHelper.parseStrictJson(null, errorMsg));
    assertInvalidRequest(errorMsg, () -> ProviderJsonHelper.parseStrictJson(" ", errorMsg));
    assertInvalidRequest(errorMsg, () -> ProviderJsonHelper.parseStrictJson("{", errorMsg));

    // 尾随 token
    assertInvalidRequest(
        errorMsg, () -> ProviderJsonHelper.parseStrictJson("[1, 2] trailing", errorMsg));

    // 重复键
    assertInvalidRequest(
        errorMsg, () -> ProviderJsonHelper.parseStrictJson("{\"a\":1,\"a\":2}", errorMsg));
  }

  @Test
  @DisplayName("tryParseJsonObject: 宽容解析对象")
  void testTryParseJsonObject() {
    // 正常对象
    JsonNode node = ProviderJsonHelper.tryParseJsonObject("{\"foo\": 123}");
    assertNotNull(node);
    assertTrue(node.isObject());

    // null、空串、非法、数组、标量全返回 null
    assertNull(ProviderJsonHelper.tryParseJsonObject(null));
    assertNull(ProviderJsonHelper.tryParseJsonObject(""));
    assertNull(ProviderJsonHelper.tryParseJsonObject("   "));
    assertNull(ProviderJsonHelper.tryParseJsonObject("{broken"));
    assertNull(ProviderJsonHelper.tryParseJsonObject("[1, 2]"));
    assertNull(ProviderJsonHelper.tryParseJsonObject("\"string\""));
    assertNull(ProviderJsonHelper.tryParseJsonObject("{\"a\":1,\"a\":2}"));
  }

  @Test
  @DisplayName("parseSseJson: SSE 载荷解析与异常映射")
  void testParseSseJson() {
    String okJson = "{\"event\":\"test\"}";
    JsonNode node = ProviderJsonHelper.parseSseJson(okJson, "sse parse err");
    assertEquals("test", node.get("event").asText());

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> ProviderJsonHelper.parseSseJson("not json", "sse parse err"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    assertEquals("sse parse err", ex.getMessage());
  }

  @Test
  @DisplayName("parseNonNegativeLong: 非负整数与逐字文案模板")
  void testParseNonNegativeLong() {
    String notInteger = "usage %s must be an integer";
    String negative = "usage %s must not be negative";

    // 正常数值
    assertEquals(
        0L,
        ProviderJsonHelper.parseNonNegativeLong(
            new LongNode(0L), "input_tokens", notInteger, negative));
    assertEquals(
        100L,
        ProviderJsonHelper.parseNonNegativeLong(
            new IntNode(100), "input_tokens", notInteger, negative));

    // null 节点
    ProviderException exNull =
        assertThrows(
            ProviderException.class,
            () -> ProviderJsonHelper.parseNonNegativeLong(null, "tokens", notInteger, negative));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exNull.kind());
    assertEquals("usage tokens must be an integer", exNull.getMessage());

    // 非整数节点（浮点数、文本）
    ProviderException exDouble =
        assertThrows(
            ProviderException.class,
            () ->
                ProviderJsonHelper.parseNonNegativeLong(
                    new DoubleNode(12.34), "tokens", notInteger, negative));
    assertEquals("usage tokens must be an integer", exDouble.getMessage());

    ProviderException exText =
        assertThrows(
            ProviderException.class,
            () ->
                ProviderJsonHelper.parseNonNegativeLong(
                    JsonNodeFactory.instance.textNode("100"), "tokens", notInteger, negative));
    assertEquals("usage tokens must be an integer", exText.getMessage());

    // 负数节点
    ProviderException exNeg =
        assertThrows(
            ProviderException.class,
            () ->
                ProviderJsonHelper.parseNonNegativeLong(
                    new LongNode(-5L), "tokens", notInteger, negative));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exNeg.kind());
    assertEquals("usage tokens must not be negative", exNeg.getMessage());

    // 超出 long 范围的大整数
    BigInteger outOfRange = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2));
    ProviderException exOver =
        assertThrows(
            ProviderException.class,
            () ->
                ProviderJsonHelper.parseNonNegativeLong(
                    new BigIntegerNode(outOfRange), "tokens", notInteger, negative));
    assertEquals("usage tokens must be an integer", exOver.getMessage());

    // 双占位模板同时生效：非整数先于负数判定
    ProviderException exSecondStyle =
        assertThrows(
            ProviderException.class,
            () ->
                ProviderJsonHelper.parseNonNegativeLong(
                    new LongNode(-1L),
                    "reasoning_tokens",
                    "invalid usage token count in field %s: must be an integral number within long range",
                    "invalid usage token count in field %s: must be non-negative"));
    assertEquals(
        "invalid usage token count in field reasoning_tokens: must be non-negative",
        exSecondStyle.getMessage());
  }

  private static void assertInvalidRequest(String expectedMessage, Runnable action) {
    ProviderException ex = assertThrows(ProviderException.class, action::run);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals(expectedMessage, ex.getMessage());
  }
}
