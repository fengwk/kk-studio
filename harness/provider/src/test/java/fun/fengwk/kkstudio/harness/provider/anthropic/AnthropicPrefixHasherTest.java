package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** 验证 Anthropic prefix hasher 的规范化与 SHA-256 计算。 */
class AnthropicPrefixHasherTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  @Test
  void generatesDeterministic64CharHex() {
    ArrayNode system = NODES.arrayNode();
    ArrayNode tools = NODES.arrayNode();
    ArrayNode messages = NODES.arrayNode();

    String hash1 = AnthropicPrefixHasher.calculateHash(system, tools, messages);
    String hash2 = AnthropicPrefixHasher.calculateHash(system, tools, messages);

    assertEquals(hash1, hash2);
    assertEquals(64, hash1.length());
    assertTrue(hash1.matches("^[0-9a-f]{64}$"));
  }

  @Test
  void sortsObjectKeysInAlphabeticalOrder() {
    ObjectNode node1 = NODES.objectNode();
    node1.put("z", "last");
    node1.put("a", "first");

    ObjectNode node2 = NODES.objectNode();
    node2.put("a", "first");
    node2.put("z", "last");

    StringBuilder out1 = new StringBuilder();
    AnthropicPrefixHasher.writeCanonical(node1, out1);

    StringBuilder out2 = new StringBuilder();
    AnthropicPrefixHasher.writeCanonical(node2, out2);

    assertEquals("{\"a\":\"first\",\"z\":\"last\"}", out1.toString());
    assertEquals(out1.toString(), out2.toString());
  }

  @Test
  void completelyExcludesCacheControlFromHash() {
    ArrayNode system1 = NODES.arrayNode();
    ObjectNode block1 = system1.addObject();
    block1.put("type", "text");
    block1.put("text", "system prompt");

    ArrayNode system2 = NODES.arrayNode();
    ObjectNode block2 = system2.addObject();
    block2.put("type", "text");
    block2.put("text", "system prompt");
    ObjectNode cacheCtrl = block2.putObject("cache_control");
    cacheCtrl.put("type", "ephemeral");

    ArrayNode tools = NODES.arrayNode();
    ArrayNode messages = NODES.arrayNode();

    String hash1 = AnthropicPrefixHasher.calculateHash(system1, tools, messages);
    String hash2 = AnthropicPrefixHasher.calculateHash(system2, tools, messages);

    assertEquals(hash1, hash2, "cache_control must be completely excluded from canonical hash");
  }

  @Test
  void hashChangesWhenContentOrOrderChanges() {
    ArrayNode system = NODES.arrayNode();
    ArrayNode tools = NODES.arrayNode();

    ArrayNode messages1 = NODES.arrayNode();
    ObjectNode m1 = messages1.addObject();
    m1.put("role", "user");
    m1.putArray("content").addObject().put("type", "text").put("text", "hello");

    ArrayNode messages2 = NODES.arrayNode();
    ObjectNode m2 = messages2.addObject();
    m2.put("role", "user");
    m2.putArray("content").addObject().put("type", "text").put("text", "world");

    String hash1 = AnthropicPrefixHasher.calculateHash(system, tools, messages1);
    String hash2 = AnthropicPrefixHasher.calculateHash(system, tools, messages2);

    assertNotEquals(hash1, hash2);
  }

  @Test
  void handlesSpecialEscapedCharactersAndPrimitives() {
    ObjectNode node = NODES.objectNode();
    node.putNull("nullKey");
    node.put("boolTrue", true);
    node.put("boolFalse", false);
    node.put("num", 123.45);
    node.put(
        "escapes",
        "quote: \", backslash: \\, bs: \b, ff: \f, nl: \n, cr: \r, tab: \t, ctrl: \u0007");

    StringBuilder out = new StringBuilder();
    AnthropicPrefixHasher.writeCanonical(node, out);
    String canonical = out.toString();

    assertTrue(canonical.contains("\"nullKey\":null"));
    assertTrue(canonical.contains("\"boolTrue\":true"));
    assertTrue(canonical.contains("\"boolFalse\":false"));
    assertTrue(canonical.contains("\"num\":123.45"));
    assertTrue(canonical.contains("\\\""));
    assertTrue(canonical.contains("\\\\"));
    assertTrue(canonical.contains("\\b"));
    assertTrue(canonical.contains("\\f"));
    assertTrue(canonical.contains("\\n"));
    assertTrue(canonical.contains("\\r"));
    assertTrue(canonical.contains("\\t"));
    assertTrue(canonical.contains("\\u0007"));
  }

  @Test
  void calculatesHashWithNullNodes() {
    String hash = AnthropicPrefixHasher.calculateHash(null, null, null);
    assertEquals(64, hash.length());
  }
}
