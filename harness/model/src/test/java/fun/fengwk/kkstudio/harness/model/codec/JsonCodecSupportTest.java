package fun.fengwk.kkstudio.harness.model.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/** {@link JsonCodecSupport} 的字段集合与 object 校验契约：合并后各 codec 共享的唯一实现。 */
class JsonCodecSupportTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static ObjectNode object(String json) {
    return (ObjectNode) MAPPER.readTree(json);
  }

  /** object 同时拒绝 null 与标量/数组，并原样返回 object 节点。 */
  @Test
  void objectRejectsNullAndNonObject() {
    ObjectNode node = MAPPER.createObjectNode().put("a", 1);
    assertSame(node, JsonCodecSupport.object(node, "payload"));

    IllegalArgumentException nullError =
        assertThrows(
            IllegalArgumentException.class, () -> JsonCodecSupport.object(null, "payload"));
    assertEquals("payload must be a JSON object", nullError.getMessage());

    JsonNode array = MAPPER.readTree("[]");
    IllegalArgumentException arrayError =
        assertThrows(
            IllegalArgumentException.class, () -> JsonCodecSupport.object(array, "payload"));
    assertEquals("payload must be a JSON object", arrayError.getMessage());
  }

  /** 精确集合：缺失、未知与两者混合都拒绝；仅字段完全一致时通过。 */
  @Test
  void requireFieldsAcceptsOnlyExactSet() {
    Set<String> expected = JsonCodecSupport.orderedSet("a", "b");
    JsonCodecSupport.requireFields(object("{\"b\":1,\"a\":2}"), expected, "payload");

    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> JsonCodecSupport.requireFields(object("{\"a\":1}"), expected, "payload"));
    assertTrue(
        missing.getMessage().startsWith("payload unexpected fields: [a]"), missing.getMessage());

    IllegalArgumentException extra =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                JsonCodecSupport.requireFields(
                    object("{\"a\":1,\"b\":2,\"c\":3}"), expected, "payload"));
    assertTrue(extra.getMessage().contains("(expected [a, b])"), extra.getMessage());
  }

  /** 必填加可选：可选字段可缺失也可出现，未知字段与缺失必填仍然拒绝。 */
  @Test
  void requireFieldsAcceptsRequiredPlusOptional() {
    Set<String> required = JsonCodecSupport.orderedSet("a");
    Set<String> optional = JsonCodecSupport.orderedSet("b");

    JsonCodecSupport.requireFields(object("{\"a\":1}"), required, optional, "payload");
    JsonCodecSupport.requireFields(object("{\"a\":1,\"b\":2}"), required, optional, "payload");

    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                JsonCodecSupport.requireFields(object("{\"b\":2}"), required, optional, "payload"));
    assertTrue(missing.getMessage().contains("optional [b]"), missing.getMessage());

    IllegalArgumentException unknown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                JsonCodecSupport.requireFields(
                    object("{\"a\":1,\"c\":3}"), required, optional, "payload"));
    assertTrue(unknown.getMessage().startsWith("payload unexpected fields: [a, c]"));
  }

  /** replay/diagnostic 变体只在消息里报告数量与期望字段，绝不回显可能来自上游的实际字段名。 */
  @Test
  void requireFieldsWithoutEchoNeverRepeatsActualFieldNames() {
    Set<String> expected = Set.of("a", "b");

    IllegalArgumentException extra =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                JsonCodecSupport.requireFieldsWithoutEcho(
                    object("{\"a\":1,\"b\":2,\"SECRET_TOKEN_KEY\":3}"), expected, "payload"));
    assertFalse(extra.getMessage().contains("SECRET_TOKEN_KEY"), extra.getMessage());
    assertEquals("payload field count mismatch: expected 2 fields", extra.getMessage());
  }
}
