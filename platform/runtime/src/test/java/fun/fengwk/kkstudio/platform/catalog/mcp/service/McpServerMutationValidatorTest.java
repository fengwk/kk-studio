package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator.HttpConfig;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Server 创建与更新输入校验器测试。
 *
 * <p>只接受显式 HTTP 配置：name 必须满足 {@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符，url 必须是绝对 http/https 地址且不含
 * user-info 凭据，headers 允许 {@code ${VAR}} 占位符但拒绝控制字符，timeout 缺省为 60 秒。
 */
class McpServerMutationValidatorTest {

  @Test
  void normalizesValidCreateDto() {
    // 意图：合法 create 输入被规范化，enabled 缺省为 true，timeout 缺省为 60 秒
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("github_mcp");
    dto.setUrl("https://mcp.github.com/api");
    dto.setHeaders(Map.of("Authorization", "Bearer ${GITHUB_TOKEN}"));

    McpServerMutationValidator.NormalizedCreate normalized =
        McpServerMutationValidator.normalizeCreate(dto);

    assertEquals("github_mcp", normalized.name());
    assertEquals("https://mcp.github.com/api", normalized.config().url());
    assertEquals("Bearer ${GITHUB_TOKEN}", normalized.config().headers().get("Authorization"));
    assertTrue(normalized.config().enabled());
    assertEquals(McpConfigParser.DEFAULT_TIMEOUT_MILLIS, normalized.config().timeoutMillis());

    dto.setEnabled(false);
    dto.setTimeoutMillis(1500L);
    McpServerMutationValidator.NormalizedCreate explicit =
        McpServerMutationValidator.normalizeCreate(dto);
    assertFalse(explicit.config().enabled());
    assertEquals(1500L, explicit.config().timeoutMillis());
  }

  @Test
  void normalizesNullHeadersToEmptyMapAndRejectsBlankBody() {
    // 意图：headers 缺省归一化为空映射；null body 确定性拒绝
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("empty_headers");
    dto.setUrl("https://a.example.com/mcp");

    assertTrue(McpServerMutationValidator.normalizeCreate(dto).config().headers().isEmpty());
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(null));
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeUpdate(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Tools", "1tools", "to ols", "tools-x", "tools.x", "工具"})
  @NullSource
  void rejectsInvalidName(String invalidName) {
    // 意图：name 必须是 ^[a-z][a-z0-9_]*$，不接受大写、分隔符、非 ASCII 与空值
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName(invalidName);
    dto.setUrl("https://a.example.com/mcp");

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsOverlongName() {
    // 意图：name 长度上限为 32 字符
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("a".repeat(33));
    dto.setUrl("https://a.example.com/mcp");

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
    assertEquals(32, McpServerMutationValidator.NAME_MAX_LENGTH);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "example.com/mcp",
        "ftp://example.com/mcp",
        "http:///no-host",
        "http://user:pw@example.com/mcp",
        " https://a.example.com/mcp"
      })
  @NullSource
  void rejectsInvalidUrl(String invalidUrl) {
    // 意图：url 必须是绝对 http/https 地址、含 host 且不含 user-info 凭据或首尾空白
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("valid_name");
    dto.setUrl(invalidUrl);

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsOverlongUrl() {
    // 意图：url 长度上限为 2048 字符
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("valid_name");
    dto.setUrl("https://a.example.com/" + "x".repeat(McpConfigParser.URL_MAX_LENGTH));

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void normalizesHeadersAndRejectsControlCharacters() {
    // 意图：header 名不能为空、值缺省为空串；任一方向的控制字符都必须拒绝
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Trace", null);
    headers.put("X-Multi", "${A}${B}");

    HttpConfig config =
        McpServerMutationValidator.normalizeHttpConfig(
            "https://a.example.com", headers, true, 1000L);
    assertEquals("", config.headers().get("X-Trace"));
    assertEquals("${A}${B}", config.headers().get("X-Multi"));

    Map<String, String> blankName = new LinkedHashMap<>();
    blankName.put("  ", "value");
    assertThrows(
        AiValidationException.class,
        () ->
            McpServerMutationValidator.normalizeHttpConfig(
                "https://a.example.com", blankName, true, 1000L));

    assertThrows(
        AiValidationException.class,
        () ->
            McpServerMutationValidator.normalizeHttpConfig(
                "https://a.example.com", Map.of("X-Bad", "value\r\ninjected"), true, 1000L));
    assertThrows(
        AiValidationException.class,
        () ->
            McpServerMutationValidator.normalizeHttpConfig(
                "https://a.example.com", Map.of("X\u0000", "value"), true, 1000L));
  }

  @Test
  void rejectsNonPositiveTimeout() {
    // 意图：timeout 必须为正整数毫秒
    assertThrows(
        AiValidationException.class,
        () ->
            McpServerMutationValidator.normalizeHttpConfig(
                "https://a.example.com", Map.of(), true, 0L));
    assertThrows(
        AiValidationException.class,
        () ->
            McpServerMutationValidator.normalizeHttpConfig(
                "https://a.example.com", Map.of(), true, -1L));
  }

  @Test
  void resolvesWholeValueEnvironmentPlaceholdersOnly() {
    // 意图：仅整值 ${VAR} 被替换；不会替换内嵌片段，缺失变量确定性拒绝且不回显变量值
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "${TOKEN}");
    headers.put("X-Literal", "Bearer ${TOKEN}");
    headers.put("X-Plain", "plain");

    Map<String, String> resolved =
        McpConfigParser.resolveHeaders(headers, name -> "TOKEN".equals(name) ? "resolved" : null);
    assertEquals("resolved", resolved.get("Authorization"));
    assertEquals("Bearer ${TOKEN}", resolved.get("X-Literal"));
    assertEquals("plain", resolved.get("X-Plain"));

    assertThrows(
        AiValidationException.class,
        () -> McpConfigParser.resolveHeaders(Map.of("Authorization", "${MISSING}"), name -> null));
  }

  @Test
  void updateNormalizationIgnoresNameBecauseNameIsImmutable() {
    // 意图：update 只包含可变配置；name 是路径身份，不参与归一化
    McpServerUpdateDTO dto = new McpServerUpdateDTO();
    dto.setExpectedVersion("3");
    dto.setUrl("https://b.example.com/mcp");
    dto.setTimeoutMillis(2000L);

    McpServerMutationValidator.NormalizedUpdate normalized =
        McpServerMutationValidator.normalizeUpdate(dto);
    assertEquals("https://b.example.com/mcp", normalized.config().url());
    assertEquals(2000L, normalized.config().timeoutMillis());
    assertNull(normalized.config().headers().get("Authorization"));
  }
}
