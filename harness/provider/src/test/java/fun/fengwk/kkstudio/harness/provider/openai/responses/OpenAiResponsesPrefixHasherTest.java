package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

/** 验证 OpenAI Responses 前缀哈希计算的确定性、规范化字典序与缓存控制字段排除。 */
class OpenAiResponsesPrefixHasherTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

  /** 验证空 tools 与空 input 时哈希稳定且符合 64 位小写十六进制规范。 */
  @Test
  void test_emptyInputHash() {
    String hash1 = OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), NODES.arrayNode());
    String hash2 = OpenAiResponsesPrefixHasher.calculateHash(null, null);
    assertEquals(hash1, hash2);
    assertTrue(HASH_PATTERN.matcher(hash1).matches());
  }

  /** 验证 JSON 对象中 key 无论以何种顺序插入，计算出的前缀哈希完全相同。 */
  @Test
  void test_canonicalKeyOrderConsistency() {
    ArrayNode input1 = NODES.arrayNode();
    ObjectNode obj1 = input1.addObject();
    obj1.put("type", "message");
    obj1.put("role", "user");

    ArrayNode input2 = NODES.arrayNode();
    ObjectNode obj2 = input2.addObject();
    obj2.put("role", "user");
    obj2.put("type", "message");

    String hash1 = OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), input1);
    String hash2 = OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), input2);
    assertEquals(hash1, hash2);
  }

  /**
   * 验证排除 prompt_cache_breakpoint、prompt_cache_key、prompt_cache_options 与 prompt_cache_retention 字段。
   */
  @Test
  void test_excludesCacheControlFields() {
    ArrayNode inputNoCache = NODES.arrayNode();
    ObjectNode msg1 = inputNoCache.addObject();
    msg1.put("type", "message");
    msg1.put("role", "user");
    ArrayNode content1 = msg1.putArray("content");
    ObjectNode block1 = content1.addObject();
    block1.put("type", "input_text");
    block1.put("text", "hello");

    ArrayNode inputWithCache = NODES.arrayNode();
    ObjectNode msg2 = inputWithCache.addObject();
    msg2.put("type", "message");
    msg2.put("role", "user");
    msg2.put("prompt_cache_key", "affinity_key_123");
    msg2.put("prompt_cache_retention", "in_memory");
    ArrayNode content2 = msg2.putArray("content");
    ObjectNode block2 = content2.addObject();
    block2.put("type", "input_text");
    block2.put("text", "hello");
    ObjectNode bp = block2.putObject("prompt_cache_breakpoint");
    bp.put("mode", "explicit");

    String hashNoCache = OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), inputNoCache);
    String hashWithCache =
        OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), inputWithCache);
    assertEquals(hashNoCache, hashWithCache);
  }

  /** 验证不同内容、不同参数或不同工具定义产生互不相同的哈希值。 */
  @Test
  void test_differentInputsYieldDifferentHashes() {
    ArrayNode input1 = NODES.arrayNode();
    input1.addObject().put("type", "message").put("text", "hello");

    ArrayNode input2 = NODES.arrayNode();
    input2.addObject().put("type", "message").put("text", "world");

    String hash1 = OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), input1);
    String hash2 = OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), input2);
    assertNotEquals(hash1, hash2);
  }

  /** 验证特殊字符转义在规范化字符串构建中的确定性。 */
  @Test
  void test_specialCharactersEscaping() {
    ArrayNode input = NODES.arrayNode();
    ObjectNode obj = input.addObject();
    obj.put("text", "line1\nline2\t\"quoted\"\\backslash\b\f\r\u0001");

    String hash = OpenAiResponsesPrefixHasher.calculateHash(NODES.arrayNode(), input);
    assertTrue(HASH_PATTERN.matcher(hash).matches());
  }
}
