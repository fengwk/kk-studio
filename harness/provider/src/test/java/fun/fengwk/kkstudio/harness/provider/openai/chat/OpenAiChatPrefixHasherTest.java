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
  @DisplayName("四个动态 cache 字段在任意嵌套层级均被完整排除")
  void testAllFourCacheFieldsExcludedAtArbitraryNestingLevels() {
    // 测试意图：验证 prompt_cache_key, prompt_cache_options, prompt_cache_breakpoint,
    // prompt_cache_retention
    // 四个动态缓存字段在任意层级（工具定义深层、消息内容数组内部、多层嵌套对象内部）均被完整排除，
    // 不影响前缀哈希的确定性计算。
    ArrayNode toolsBase = MAPPER.createArrayNode();
    ObjectNode toolObj = toolsBase.addObject();
    toolObj.put("type", "function");
    ObjectNode fn = toolObj.putObject("function");
    fn.put("name", "calc");
    ObjectNode params = fn.putObject("parameters");
    params.put("type", "object");
    ObjectNode props = params.putObject("properties");
    props.putObject("x").put("type", "number");

    ArrayNode messagesBase = MAPPER.createArrayNode();
    ObjectNode msg = messagesBase.addObject();
    msg.put("role", "user");
    ArrayNode content = msg.putArray("content");
    ObjectNode part = content.addObject();
    part.put("type", "text");
    part.put("text", "hello");

    String baseHash = OpenAiChatPrefixHasher.calculateHash(toolsBase, messagesBase);

    // 1. 在 tool 的不同嵌套层级分别注入 prompt_cache_key, prompt_cache_options, prompt_cache_retention,
    // prompt_cache_breakpoint
    ArrayNode toolsWithCache = MAPPER.createArrayNode();
    ObjectNode toolWithCache = toolsWithCache.addObject();
    toolWithCache.put("type", "function");
    toolWithCache.put("prompt_cache_key", "tool-key");
    ObjectNode fnWithCache = toolWithCache.putObject("function");
    fnWithCache.put("name", "calc");
    fnWithCache.put("prompt_cache_retention", "in_memory");
    ObjectNode paramsWithCache = fnWithCache.putObject("parameters");
    paramsWithCache.put("type", "object");
    ObjectNode cacheOptions = paramsWithCache.putObject("prompt_cache_options");
    cacheOptions.put("mode", "explicit");
    cacheOptions.put("ttl", "30m");
    ObjectNode propsWithCache = paramsWithCache.putObject("properties");
    ObjectNode xNode = propsWithCache.putObject("x");
    xNode.put("type", "number");
    xNode.put("prompt_cache_breakpoint", true);

    // 2. 在 messages 的不同嵌套层级分别注入四字段
    ArrayNode messagesWithCache = MAPPER.createArrayNode();
    ObjectNode msgWithCache = messagesWithCache.addObject();
    msgWithCache.put("role", "user");
    msgWithCache.put("prompt_cache_key", "msg-key");
    msgWithCache.put("prompt_cache_retention", "24h");
    ArrayNode contentWithCache = msgWithCache.putArray("content");
    ObjectNode partWithCache = contentWithCache.addObject();
    partWithCache.put("type", "text");
    partWithCache.put("text", "hello");
    partWithCache.put("prompt_cache_breakpoint", true);
    ObjectNode nestedOptions = partWithCache.putObject("prompt_cache_options");
    nestedOptions.put("mode", "explicit");

    String hashWithCache = OpenAiChatPrefixHasher.calculateHash(toolsWithCache, messagesWithCache);
    assertEquals(baseHash, hashWithCache);
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
