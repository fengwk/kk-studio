package fun.fengwk.kkstudio.harness.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** {@link JsonValues} 的严格 JSON 校验、顶层对象约束、重复字段与尾随 token 拦截测试。 */
class JsonValuesTest {

  /** 验证合法 JSON 原样返回，空白或非 JSON 抛出 IllegalArgumentException。 */
  @Test
  void requireValidJsonAcceptsValidAndRejectsInvalidOrBlank() {
    assertEquals("{\"a\":1}", JsonValues.requireValidJson("{\"a\":1}"));
    assertEquals("[1,2,3]", JsonValues.requireValidJson("[1,2,3]"));
    assertEquals("\"text\"", JsonValues.requireValidJson("\"text\""));
    assertEquals("42", JsonValues.requireValidJson("42"));

    assertThrows(IllegalArgumentException.class, () -> JsonValues.requireValidJson(null));
    assertThrows(IllegalArgumentException.class, () -> JsonValues.requireValidJson(""));
    assertThrows(IllegalArgumentException.class, () -> JsonValues.requireValidJson("   "));
    assertThrows(IllegalArgumentException.class, () -> JsonValues.requireValidJson("invalid"));
  }

  /** 验证严格拒绝对象中存在的重复 key（STRICT_DUPLICATE_DETECTION）。 */
  @Test
  void rejectsDuplicateObjectKeys() {
    IllegalArgumentException error1 =
        assertThrows(
            IllegalArgumentException.class,
            () -> JsonValues.requireValidJson("{\"key\":1,\"key\":2}"));
    assertTrue(error1.getMessage().contains("json must be valid"));

    IllegalArgumentException error2 =
        assertThrows(
            IllegalArgumentException.class,
            () -> JsonValues.requireJsonObject("{\"key\":1,\"key\":2}"));
    assertTrue(error2.getMessage().contains("json must be valid"));
  }

  /** 验证严格拒绝合法 JSON 之后的尾随 token 与文本（FAIL_ON_TRAILING_TOKENS）。 */
  @Test
  void rejectsTrailingTokensAndCharacters() {
    assertThrows(
        IllegalArgumentException.class, () -> JsonValues.requireValidJson("{\"a\":1} trailing"));
    assertThrows(
        IllegalArgumentException.class, () -> JsonValues.requireValidJson("{\"a\":1} 123"));
    assertThrows(
        IllegalArgumentException.class, () -> JsonValues.requireValidJson("\"hello\" extra"));
    assertThrows(
        IllegalArgumentException.class, () -> JsonValues.requireJsonObject("{\"a\":1} {\"b\":2}"));
  }

  /** 验证 requireJsonObject 正常接受对象，空白规范化为 {}，非对象类型严格拒绝。 */
  @Test
  void requireJsonObjectNormalizesBlankAndRejectsNonObjects() {
    assertEquals("{}", JsonValues.requireJsonObject(null));
    assertEquals("{}", JsonValues.requireJsonObject(""));
    assertEquals("{}", JsonValues.requireJsonObject("   "));
    assertEquals("{\"a\":1}", JsonValues.requireJsonObject("{\"a\":1}"));

    IllegalArgumentException arrayError =
        assertThrows(IllegalArgumentException.class, () -> JsonValues.requireJsonObject("[1, 2]"));
    assertTrue(arrayError.getMessage().contains("must be a JSON object"));

    IllegalArgumentException scalarError =
        assertThrows(
            IllegalArgumentException.class,
            () -> JsonValues.requireJsonObject("\"str\"", "fieldA"));
    assertTrue(scalarError.getMessage().contains("fieldA must be a JSON object"));
  }

  /** 验证 readTree 与 write 能正确解析与紧凑序列化 JsonNode。 */
  @Test
  void readTreeAndWriteRoundTrip() {
    JsonNode node = JsonValues.readTree("{\"k\": \"v\"}");
    assertNotNull(node);
    assertTrue(node.isObject());
    assertEquals("v", node.get("k").asText());

    String written = JsonValues.write(node);
    assertEquals("{\"k\":\"v\"}", written);
  }
}
