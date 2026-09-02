package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

/**
 * MCP Server 创建与更新输入校验器测试。
 *
 * <p>验证 name（^[a-z][a-z0-9_]*$ ≤32）、url（非空白 ≤2048）、bearerToken（≤2048 且无首尾空白）、
 * timeoutMillis（正整数）的严格规则。
 */
class McpServerMutationValidatorTest {

  @Test
  void normalizesValidCreateDTO() {
    // 意图：验证合法的创建输入被正常接受
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("github_mcp");
    dto.setUrl("https://mcp.github.com/api");
    dto.setBearerToken("ghp_secretToken123");
    dto.setTimeoutMillis(30000L);

    McpServerMutationValidator.NormalizedCreate normalized =
        McpServerMutationValidator.normalizeCreate(dto);
    assertNotNull(normalized);
    assertEquals("github_mcp", normalized.name());
    assertEquals("https://mcp.github.com/api", normalized.url());
    assertEquals("ghp_secretToken123", normalized.bearerToken());
    assertEquals(30000L, normalized.timeoutMillis());
  }

  @Test
  void createAllowsNullOrEmptyBearerToken() {
    // 意图：验证创建时允许不传 bearerToken（null）或传空串（视为无 token）
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("local_mcp");
    dto.setUrl("http://127.0.0.1:8080/mcp");
    dto.setTimeoutMillis(5000L);

    McpServerMutationValidator.NormalizedCreate normalized =
        McpServerMutationValidator.normalizeCreate(dto);
    assertNull(normalized.bearerToken());

    dto.setBearerToken("   ");
    McpServerMutationValidator.NormalizedCreate normalizedEmpty =
        McpServerMutationValidator.normalizeCreate(dto);
    assertEquals("", normalizedEmpty.bearerToken());
  }

  @Test
  void rejectsNullCreateDTO() {
    // 意图：验证 null 请求体抛出 AiValidationException
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "GitHub", "123mcp", "mcp-server", "mcp.server", "mcp$server"})
  void rejectsInvalidNamesOnCreate(String invalidName) {
    // 意图：验证非法名称模式在创建时被拒绝
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName(invalidName);
    dto.setUrl("http://localhost/mcp");
    dto.setTimeoutMillis(5000L);

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsOverlongNameOnCreate() {
    // 意图：验证名称超过 32 字符被拒绝
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("a".repeat(33));
    dto.setUrl("http://localhost/mcp");
    dto.setTimeoutMillis(5000L);

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsInvalidUrlsOnCreate() {
    // 意图：验证空白 URL、带首尾空格的 URL 或超长 URL 被拒绝
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("mcp_test");
    dto.setTimeoutMillis(5000L);

    dto.setUrl(null);
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));

    dto.setUrl("   ");
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));

    dto.setUrl(" http://localhost/mcp ");
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));

    dto.setUrl("http://example.com/" + "a".repeat(2048));
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsInvalidBearerTokenOnCreate() {
    // 意图：验证 bearerToken 带首尾空白或超长被拒绝
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("mcp_test");
    dto.setUrl("http://localhost/mcp");
    dto.setTimeoutMillis(5000L);

    dto.setBearerToken(" token_with_surrounding_space ");
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));

    dto.setBearerToken("a".repeat(2049));
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsInvalidTimeoutOnCreate() {
    // 意图：验证 timeoutMillis 为 null、0 或负数时被拒绝
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("mcp_test");
    dto.setUrl("http://localhost/mcp");

    dto.setTimeoutMillis(null);
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));

    dto.setTimeoutMillis(0L);
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));

    dto.setTimeoutMillis(-100L);
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void normalizesUpdateDTOWithNullsAndValues() {
    // 意图：验证更新时允许字段为 null（表示不修改），有值时严格校验
    McpServerUpdateDTO dto = new McpServerUpdateDTO();
    dto.setExpectedVersion("1");

    McpServerMutationValidator.NormalizedUpdate normalized =
        McpServerMutationValidator.normalizeUpdate(dto);
    assertNull(normalized.url());
    assertNull(normalized.bearerToken());
    assertNull(normalized.timeoutMillis());

    dto.setUrl("https://new.url.com/mcp");
    dto.setBearerToken("new_token");
    dto.setTimeoutMillis(10000L);

    McpServerMutationValidator.NormalizedUpdate updated =
        McpServerMutationValidator.normalizeUpdate(dto);
    assertEquals("https://new.url.com/mcp", updated.url());
    assertEquals("new_token", updated.bearerToken());
    assertEquals(10000L, updated.timeoutMillis());
  }

  @Test
  void rejectsNullUpdateDTO() {
    // 意图：验证 null 更新请求体抛出异常
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeUpdate(null));
  }
}
