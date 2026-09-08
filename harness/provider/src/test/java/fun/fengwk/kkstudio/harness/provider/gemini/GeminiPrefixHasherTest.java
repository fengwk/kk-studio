package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** 验证 Gemini 前缀哈希生成的确定性、字典序规范化与 SHA-256 计算。 */
class GeminiPrefixHasherTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** 验证生成结果是 64 字符的小写十六进制字符串。 */
  @Test
  void generatesDeterministic64CharHex() {
    ObjectNode system = NODES.objectNode();
    ArrayNode tools = NODES.arrayNode();
    ArrayNode contents = NODES.arrayNode();

    String hash1 = GeminiPrefixHasher.calculateHash(system, tools, contents);
    String hash2 = GeminiPrefixHasher.calculateHash(system, tools, contents);

    assertEquals(hash1, hash2);
    assertEquals(64, hash1.length());
    assertTrue(hash1.matches("^[0-9a-f]{64}$"));
  }

  /** 验证 JSON 对象键顺序不同时能够按字典序规范化，产生完全相同的输出与哈希。 */
  @Test
  void sortsObjectKeysInAlphabeticalOrder() {
    ObjectNode n1 = NODES.objectNode();
    n1.put("z", "val_z");
    n1.put("a", "val_a");

    ObjectNode n2 = NODES.objectNode();
    n2.put("a", "val_a");
    n2.put("z", "val_z");

    StringBuilder b1 = new StringBuilder();
    GeminiPrefixHasher.writeCanonical(n1, b1);

    StringBuilder b2 = new StringBuilder();
    GeminiPrefixHasher.writeCanonical(n2, b2);

    assertEquals("{\"a\":\"val_a\",\"z\":\"val_z\"}", b1.toString());
    assertEquals(b1.toString(), b2.toString());
  }

  /** 验证任何内容差异（无论是系统提示、工具还是上下文）都会改变前缀哈希。 */
  @Test
  void hashChangesWhenInputsVary() {
    ObjectNode sys1 = NODES.objectNode();
    sys1.putObject("parts").put("text", "rule A");

    ObjectNode sys2 = NODES.objectNode();
    sys2.putObject("parts").put("text", "rule B");

    ArrayNode tools = NODES.arrayNode();
    ArrayNode contents = NODES.arrayNode();

    String h1 = GeminiPrefixHasher.calculateHash(sys1, tools, contents);
    String h2 = GeminiPrefixHasher.calculateHash(sys2, tools, contents);

    assertNotEquals(h1, h2);
  }

  /** 验证 null 输入安全默认规范化。 */
  @Test
  void handlesNullSafely() {
    String h1 = GeminiPrefixHasher.calculateHash(null, null, null);
    assertEquals(64, h1.length());
    assertTrue(h1.matches("^[0-9a-f]{64}$"));
  }

  /** 验证各种转义字符与控制字符规范化写入。 */
  @Test
  void handlesSpecialEscapeCharacters() {
    ObjectNode n = NODES.objectNode();
    n.put("key", "test\b\f\r\t\"\\\u0001end");
    StringBuilder sb = new StringBuilder();
    GeminiPrefixHasher.writeCanonical(n, sb);
    assertTrue(sb.toString().contains("\\b"));
    assertTrue(sb.toString().contains("\\f"));
    assertTrue(sb.toString().contains("\\r"));
    assertTrue(sb.toString().contains("\\t"));
    assertTrue(sb.toString().contains("\\\""));
    assertTrue(sb.toString().contains("\\\\"));
    assertTrue(sb.toString().contains("\\u0001"));
  }
}
