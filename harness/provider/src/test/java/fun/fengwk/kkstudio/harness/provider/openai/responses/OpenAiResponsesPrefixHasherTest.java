package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

/** 验证 OpenAI Responses 前缀哈希计算的确定性、规范化字典序与缓存控制字段排除。 */
class OpenAiResponsesPrefixHasherTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

  /** 验证空 tools 与空 input 时哈希稳定且符合 64 位小写十六进制规范。 */
  @Test
  void test_emptyInputHash() {
    String hash1 =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), NODES.arrayNode());
    String hash2 =
        OpenAiResponsesPrefixHasher.calculateHash("Test system instruction.", null, null);
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

    String hash1 =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), input1);
    String hash2 =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), input2);
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

    String hashNoCache =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), inputNoCache);
    String hashWithCache =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), inputWithCache);
    assertEquals(hashNoCache, hashWithCache);
  }

  /**
   * 测试意图：验证 prompt_cache_key、prompt_cache_options、prompt_cache_breakpoint、prompt_cache_retention
   * 这四个动态缓存控制字段，在根层级、工具定义层级、消息层级以及任意深层嵌套对象中出现时，均被确定性排除， 计算出的前缀哈希与不带任何缓存字段的基准完全一致。
   */
  @Test
  void test_fourCacheControlFieldsExcludedAtAllNestingLevels() {
    // 1. 基准结构：包含工具定义与用户消息
    ArrayNode baseTools = NODES.arrayNode();
    ObjectNode tool = baseTools.addObject();
    tool.put("type", "function");
    tool.put("name", "get_weather");
    ObjectNode params = tool.putObject("parameters");
    params.put("type", "object");
    ObjectNode props = params.putObject("properties");
    props.putObject("location").put("type", "string");

    ArrayNode baseInput = NODES.arrayNode();
    ObjectNode msg = baseInput.addObject();
    msg.put("type", "message");
    msg.put("role", "user");
    ArrayNode content = msg.putArray("content");
    ObjectNode block = content.addObject();
    block.put("type", "input_text");
    block.put("text", "weather in Shanghai");

    String baseHash =
        OpenAiResponsesPrefixHasher.calculateHash("Test system instruction.", baseTools, baseInput);

    // 2. 在工具层级及深层参数中注入四个字段
    ArrayNode toolsWithCache = NODES.arrayNode();
    ObjectNode toolWithCache = toolsWithCache.addObject();
    toolWithCache.put("type", "function");
    toolWithCache.put("name", "get_weather");
    toolWithCache.put("prompt_cache_key", "tool_cache_key");
    toolWithCache.putObject("prompt_cache_options").put("mode", "explicit").put("ttl", "1h");
    ObjectNode paramsWithCache = toolWithCache.putObject("parameters");
    paramsWithCache.put("type", "object");
    paramsWithCache.put("prompt_cache_retention", "24h");
    ObjectNode propsWithCache = paramsWithCache.putObject("properties");
    ObjectNode loc = propsWithCache.putObject("location");
    loc.put("type", "string");
    loc.putObject("prompt_cache_breakpoint").put("mode", "explicit");

    assertEquals(
        baseHash,
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", toolsWithCache, baseInput));

    // 3. 在消息层级及多层深层嵌套子对象中注入四个字段
    ArrayNode inputWithDeepCache = NODES.arrayNode();
    ObjectNode msgWithCache = inputWithDeepCache.addObject();
    msgWithCache.put("type", "message");
    msgWithCache.put("role", "user");
    msgWithCache.put("prompt_cache_key", "msg_key");
    msgWithCache.put("prompt_cache_retention", "in_memory");
    ArrayNode contentWithCache = msgWithCache.putArray("content");
    ObjectNode blockWithCache = contentWithCache.addObject();
    blockWithCache.put("type", "input_text");
    blockWithCache.put("text", "weather in Shanghai");
    blockWithCache.putObject("prompt_cache_breakpoint").put("mode", "explicit");
    ObjectNode deepMeta = blockWithCache.putObject("meta");
    deepMeta.put("prompt_cache_key", "nested_key");
    deepMeta.putObject("prompt_cache_options").put("ttl", "5m");
    // 移除无语义字段 meta 以保持与 base 一致，仅保留四个缓存字段
    blockWithCache.remove("meta");
    blockWithCache.putObject("prompt_cache_options").put("ttl", "30m");

    assertEquals(
        baseHash,
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", baseTools, inputWithDeepCache));

    // 4. 四个字段单独逐一添加，均不改变哈希
    for (String field :
        List.of(
            "prompt_cache_key",
            "prompt_cache_options",
            "prompt_cache_breakpoint",
            "prompt_cache_retention")) {
      ArrayNode singleInput = NODES.arrayNode();
      ObjectNode singleMsg = singleInput.addObject();
      singleMsg.put("type", "message").put("role", "user");
      if ("prompt_cache_options".equals(field) || "prompt_cache_breakpoint".equals(field)) {
        singleMsg.putObject(field).put("mode", "explicit");
      } else {
        singleMsg.put(field, "value");
      }
      singleMsg
          .putArray("content")
          .addObject()
          .put("type", "input_text")
          .put("text", "weather in Shanghai");

      assertEquals(
          baseHash,
          OpenAiResponsesPrefixHasher.calculateHash(
              "Test system instruction.", baseTools, singleInput));
    }
  }

  /** 测试意图：验证缓存控制字段排除不会影响语义字段敏感性； 文本、角色、类型、工具定义或非白名单字段发生变动时，前缀哈希必然改变。 */
  @Test
  void test_semanticFieldSensitivityPreserved() {
    ArrayNode baseTools = NODES.arrayNode();
    ObjectNode tool = baseTools.addObject();
    tool.put("type", "function").put("name", "calc");

    ArrayNode baseInput = NODES.arrayNode();
    ObjectNode msg = baseInput.addObject();
    msg.put("type", "message").put("role", "user");
    msg.putArray("content").addObject().put("type", "input_text").put("text", "1+1");

    String baseHash =
        OpenAiResponsesPrefixHasher.calculateHash("Test system instruction.", baseTools, baseInput);

    // 1. 语义文本改变
    ArrayNode changedTextInput = NODES.arrayNode();
    ObjectNode msg1 = changedTextInput.addObject();
    msg1.put("type", "message").put("role", "user");
    msg1.putArray("content").addObject().put("type", "input_text").put("text", "1+2");
    assertNotEquals(
        baseHash,
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", baseTools, changedTextInput));

    // 2. 消息角色改变
    ArrayNode changedRoleInput = NODES.arrayNode();
    ObjectNode msg2 = changedRoleInput.addObject();
    msg2.put("type", "message").put("role", "assistant");
    msg2.putArray("content").addObject().put("type", "input_text").put("text", "1+1");
    assertNotEquals(
        baseHash,
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", baseTools, changedRoleInput));

    // 3. 工具名称改变
    ArrayNode changedTool = NODES.arrayNode();
    changedTool.addObject().put("type", "function").put("name", "calculator");
    assertNotEquals(
        baseHash,
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", changedTool, baseInput));

    // 4. 非排除的类似前缀属性添加（如 prompt_cache_other）
    ArrayNode extraPropInput = NODES.arrayNode();
    ObjectNode msg4 = extraPropInput.addObject();
    msg4.put("type", "message").put("role", "user");
    msg4.put("prompt_cache_other", "should_not_be_excluded");
    msg4.putArray("content").addObject().put("type", "input_text").put("text", "1+1");
    assertNotEquals(
        baseHash,
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", baseTools, extraPropInput));
  }

  /** 验证不同内容、不同参数或不同工具定义产生互不相同的哈希值。 */
  @Test
  void test_differentInputsYieldDifferentHashes() {
    ArrayNode input1 = NODES.arrayNode();
    input1.addObject().put("type", "message").put("text", "hello");

    ArrayNode input2 = NODES.arrayNode();
    input2.addObject().put("type", "message").put("text", "world");

    String hash1 =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), input1);
    String hash2 =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), input2);
    assertNotEquals(hash1, hash2);
  }

  /** 验证特殊字符转义在规范化字符串构建中的确定性。 */
  @Test
  void test_specialCharactersEscaping() {
    ArrayNode input = NODES.arrayNode();
    ObjectNode obj = input.addObject();
    obj.put("text", "line1\nline2\t\"quoted\"\\backslash\b\f\r\u0001");

    String hash =
        OpenAiResponsesPrefixHasher.calculateHash(
            "Test system instruction.", NODES.arrayNode(), input);
    assertTrue(HASH_PATTERN.matcher(hash).matches());
  }
}
