package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

/** 测试意图：验证 OpenAI Chat 前缀哈希计算的确定性、规范化键排序以及自动排除 prompt cache 标记。 */
class OpenAiChatPrefixHasherTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

  @Test
  @DisplayName("计算出合法的 64 位小写十六进制 SHA-256 哈希")
  void calculateValidHash() {
    ArrayNode tools = MAPPER.createArrayNode();
    ArrayNode messages = MAPPER.createArrayNode();
    ObjectNode msg = messages.addObject();
    msg.put("role", "user");
    msg.put("content", "Hello");

    String hash = OpenAiChatPrefixHasher.calculateHash(tools, messages);
    assertNotNull(hash);
    assertTrue(HASH_PATTERN.matcher(hash).matches());
  }

  @Test
  @DisplayName("排除 prompt_cache 字段保证哈希一致性")
  void excludeCacheFields() {
    ArrayNode tools = MAPPER.createArrayNode();

    ArrayNode messages1 = MAPPER.createArrayNode();
    ObjectNode msg1 = messages1.addObject();
    msg1.put("role", "system");
    msg1.put("content", "You are helpful.");

    ArrayNode messages2 = MAPPER.createArrayNode();
    ObjectNode msg2 = messages2.addObject();
    msg2.put("role", "system");
    msg2.put("content", "You are helpful.");
    msg2.put("prompt_cache_breakpoint", true);
    msg2.put("prompt_cache_key", "key-123");

    String hash1 = OpenAiChatPrefixHasher.calculateHash(tools, messages1);
    String hash2 = OpenAiChatPrefixHasher.calculateHash(tools, messages2);
    assertEquals(hash1, hash2);
  }

  @Test
  @DisplayName("键顺序不同但内容相同时产生相同的 canonical hash")
  void stableKeyOrdering() {
    ArrayNode tools = MAPPER.createArrayNode();

    ArrayNode messages1 = MAPPER.createArrayNode();
    ObjectNode msg1 = messages1.addObject();
    msg1.put("a", "1");
    msg1.put("b", "2");

    ArrayNode messages2 = MAPPER.createArrayNode();
    ObjectNode msg2 = messages2.addObject();
    msg2.put("b", "2");
    msg2.put("a", "1");

    String hash1 = OpenAiChatPrefixHasher.calculateHash(tools, messages1);
    String hash2 = OpenAiChatPrefixHasher.calculateHash(tools, messages2);
    assertEquals(hash1, hash2);
  }

  @Test
  @DisplayName("特殊字符转义、数值与布尔以及 null tools/messages 处理")
  void testSpecialCharactersAndTypes() throws Exception {
    // null 传参兜底
    String nullHash = OpenAiChatPrefixHasher.calculateHash(null, null);
    assertNotNull(nullHash);

    // 特殊字符转义：\b, \f, \r, \t, \n, \", \\, \u0001
    StringBuilder sb = new StringBuilder();
    ObjectNode specialNode = MAPPER.createObjectNode();
    specialNode.put("str", "a\bb\fc\rd\te\nf\"g\\h\u0001i");
    specialNode.put("num", 123.45);
    specialNode.put("boolTrue", true);
    specialNode.put("boolFalse", false);
    specialNode.putNull("nullField");

    OpenAiChatPrefixHasher.writeCanonical(specialNode, sb);
    String canonical = sb.toString();
    assertTrue(canonical.contains("\\b"));
    assertTrue(canonical.contains("\\f"));
    assertTrue(canonical.contains("\\r"));
    assertTrue(canonical.contains("\\t"));
    assertTrue(canonical.contains("\\n"));
    assertTrue(canonical.contains("\\\""));
    assertTrue(canonical.contains("\\\\"));
    assertTrue(canonical.contains("\\u0001"));
    assertTrue(canonical.contains("123.45"));
    assertTrue(canonical.contains("true"));
    assertTrue(canonical.contains("false"));
    assertTrue(canonical.contains("null"));

    // 单独测试 writeCanonical null node
    StringBuilder sbNull = new StringBuilder();
    OpenAiChatPrefixHasher.writeCanonical(null, sbNull);
    assertEquals("null", sbNull.toString());

    // 私有构造器反射
    var ctor = OpenAiChatPrefixHasher.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertNotNull(ctor.newInstance());
  }
}
