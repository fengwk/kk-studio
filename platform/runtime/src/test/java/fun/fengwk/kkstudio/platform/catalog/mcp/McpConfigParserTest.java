package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpConfigParser.HttpConfig;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Server 名称与显式 HTTP 配置的校验测试。
 *
 * <p>只接受显式 HTTP 配置：name 必须满足 {@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符，url 必须是绝对 http/https 地址且不含
 * user-info 凭据，headers 以 JSON 对象文本提交并允许 {@code ${VAR}} 占位符但拒绝控制字符，timeout 缺省为 60 秒。
 */
class McpConfigParserTest {

  @Test
  void normalizesValidCreateDto() {
    // 意图：合法 create 输入被规范化，enabled 缺省为 true，timeout 缺省为 60 秒
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("github_mcp");
    dto.setUrl("https://mcp.github.com/api");
    dto.setHeaders(SecretUpdate.set("{\"Authorization\":\"Bearer ${GITHUB_TOKEN}\"}"));

    HttpConfig normalized = normalizeCreate(dto);

    assertEquals("https://mcp.github.com/api", normalized.url());
    assertEquals("Bearer ${GITHUB_TOKEN}", normalized.headers().get("Authorization"));
    assertTrue(normalized.enabled());
    assertEquals(McpConfigParser.DEFAULT_TIMEOUT_MILLIS, normalized.timeoutMillis());
    assertFalse(normalized.toString().contains("GITHUB_TOKEN"), "headers must not be printed");

    dto.setEnabled(false);
    dto.setTimeoutMillis(1500L);
    HttpConfig explicit = normalizeCreate(dto);
    assertFalse(explicit.enabled());
    assertEquals(1500L, explicit.timeoutMillis());
  }

  @Test
  void normalizesNullHeadersToEmptyMap() {
    // 意图：headers 缺省归一化为空映射
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("empty_headers");
    dto.setUrl("https://a.example.com/mcp");

    assertTrue(normalizeCreate(dto).headers().isEmpty());
  }

  @Test
  void parsesHeadersSecretAsJsonObjectOfStrings() {
    // 意图：秘密更新的 SET 值是 header 映射的 JSON 对象文本；数组、非字符串值与非法 JSON 都确定性拒绝
    Map<String, String> headers =
        McpConfigParser.parseHeaders("{\"Authorization\":\"Bearer x\",\"X-Trace\":\"\"}");

    assertEquals("Bearer x", headers.get("Authorization"));
    assertEquals("", headers.get("X-Trace"));
    assertTrue(McpConfigParser.parseHeaders("{}").isEmpty());
    assertThrows(DomainException.class, () -> McpConfigParser.parseHeaders(null));
    assertThrows(DomainException.class, () -> McpConfigParser.parseHeaders("  "));
    assertThrows(DomainException.class, () -> McpConfigParser.parseHeaders("not-json"));
    assertThrows(DomainException.class, () -> McpConfigParser.parseHeaders("[]"));
    assertThrows(DomainException.class, () -> McpConfigParser.parseHeaders("{\"X-Count\":1}"));
    assertThrows(
        DomainException.class, () -> McpConfigParser.parseHeaders("{\"X-Bad\":\"value\\r\\n\"}"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Tools", "1tools", "to ols", "tools-x", "tools.x", "工具"})
  @NullSource
  void rejectsInvalidName(String invalidName) {
    // 意图：name 必须是 ^[a-z][a-z0-9_]*$，不接受大写、分隔符、非 ASCII 与空值
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName(invalidName);
    dto.setUrl("https://a.example.com/mcp");

    assertThrows(DomainException.class, () -> normalizeCreate(dto));
  }

  @Test
  void rejectsOverlongName() {
    // 意图：name 长度上限为 32 字符
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("a".repeat(33));
    dto.setUrl("https://a.example.com/mcp");

    assertThrows(DomainException.class, () -> normalizeCreate(dto));
    assertEquals("a".repeat(32), McpConfigParser.requireName("a".repeat(32)));
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

    assertThrows(DomainException.class, () -> normalizeCreate(dto));
  }

  @Test
  void rejectsOverlongUrl() {
    // 意图：url 长度上限为 2048 字符
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("valid_name");
    dto.setUrl("https://a.example.com/" + "x".repeat(McpConfigParser.URL_MAX_LENGTH));

    assertThrows(DomainException.class, () -> normalizeCreate(dto));
  }

  @Test
  void normalizesHeadersAndRejectsControlCharacters() {
    // 意图：header 名不能为空、值缺省为空串；任一方向的控制字符都必须拒绝
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Trace", null);
    headers.put("X-Multi", "${A}${B}");

    HttpConfig config =
        McpConfigParser.normalizeHttpConfig("https://a.example.com", headers, true, 1000L);
    assertEquals("", config.headers().get("X-Trace"));
    assertEquals("${A}${B}", config.headers().get("X-Multi"));

    Map<String, String> blankName = new LinkedHashMap<>();
    blankName.put("  ", "value");
    assertThrows(
        DomainException.class,
        () -> McpConfigParser.normalizeHttpConfig("https://a.example.com", blankName, true, 1000L));

    assertThrows(
        DomainException.class,
        () ->
            McpConfigParser.normalizeHttpConfig(
                "https://a.example.com", Map.of("X-Bad", "value\r\ninjected"), true, 1000L));
    assertThrows(
        DomainException.class,
        () ->
            McpConfigParser.normalizeHttpConfig(
                "https://a.example.com", Map.of("X\u0000", "value"), true, 1000L));
  }

  @Test
  void rejectsNonPositiveTimeout() {
    // 意图：timeout 必须为正整数毫秒
    assertThrows(
        DomainException.class,
        () -> McpConfigParser.normalizeHttpConfig("https://a.example.com", Map.of(), true, 0L));
    assertThrows(
        DomainException.class,
        () -> McpConfigParser.normalizeHttpConfig("https://a.example.com", Map.of(), true, -1L));
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
        DomainException.class,
        () -> McpConfigParser.resolveHeaders(Map.of("Authorization", "${MISSING}"), name -> null));
  }

  /** 与 create 入口一致：先校验名称，再把秘密更新解析为 header 映射并规范化。 */
  private static HttpConfig normalizeCreate(McpServerCreateDTO dto) {
    McpConfigParser.requireName(dto.getName());
    return McpConfigParser.normalizeHttpConfig(
        dto.getUrl(), headersOf(dto.getHeaders()), dto.getEnabled(), dto.getTimeoutMillis());
  }

  private static Map<String, String> headersOf(SecretUpdate update) {
    if (update == null || update.operation() != SecretUpdate.Operation.SET) {
      return Map.of();
    }
    return McpConfigParser.parseHeaders(update.value());
  }
}
