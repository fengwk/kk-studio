package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.LocalConnectionConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.RemoteConnectionConfig;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.util.List;
import java.util.UUID;

/**
 * MCP Server 创建与更新输入校验器测试。
 *
 * <p>验证 name（^[a-z][a-z0-9_]*$ ≤32）以及 configJson 的解析与校验。
 */
class McpServerMutationValidatorTest {

  private static final String REMOTE_CONFIG =
      """
      {
        "type": "remote",
        "url": "https://mcp.github.com/api",
        "headers": {
          "Authorization": "Bearer my-secret-token"
        },
        "timeoutMillis": 30000
      }
      """;

  private static final String LOCAL_CONFIG =
      """
      {
        "type": "local",
        "environmentId": "11111111-1111-1111-1111-111111111111",
        "command": ["node", "server.js"],
        "cwd": "/app",
        "env": {"NODE_ENV": "production"},
        "timeoutMillis": 15000
      }
      """;

  @Test
  void normalizesValidRemoteCreateDTO() {
    // 意图：验证合法的 Remote 创建输入被正常接受
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("github_mcp");
    dto.setConfigJson(REMOTE_CONFIG);

    McpServerMutationValidator.NormalizedCreate normalized =
        McpServerMutationValidator.normalizeCreate(dto);
    assertNotNull(normalized);
    assertEquals("github_mcp", normalized.name());
    assertEquals(McpConnectionType.REMOTE, normalized.config().connectionType());
    assertEquals(30000L, normalized.config().timeoutMillis());
    RemoteConnectionConfig remote = (RemoteConnectionConfig) normalized.config().connectionConfig();
    assertEquals("https://mcp.github.com/api", remote.url());
  }

  @Test
  void normalizesValidLocalCreateDTO() {
    // 意图：验证合法的 Local 创建输入被正常接受
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("local_node");
    dto.setConfigJson(LOCAL_CONFIG);

    McpServerMutationValidator.NormalizedCreate normalized =
        McpServerMutationValidator.normalizeCreate(dto);
    assertNotNull(normalized);
    assertEquals("local_node", normalized.name());
    assertEquals(McpConnectionType.LOCAL, normalized.config().connectionType());
    assertEquals(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        normalized.config().environmentId());
    assertEquals(15000L, normalized.config().timeoutMillis());
    LocalConnectionConfig local = (LocalConnectionConfig) normalized.config().connectionConfig();
    assertEquals(List.of("node", "server.js"), local.command());
    assertEquals("/app", local.cwd());
  }

  @Test
  void appliesDocumentedOptionalDefaults() {
    // 意图：验证 enabled 与 timeoutMillis 省略时严格使用协议默认 true/60000
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("defaulted_mcp");
    dto.setConfigJson(
        """
        {
          "type": "remote",
          "url": "https://example.com/mcp"
        }
        """);

    McpConfigParser.ParsedMcpConfig config =
        McpServerMutationValidator.normalizeCreate(dto).config();

    assertEquals(true, config.enabled());
    assertEquals(60_000L, config.timeoutMillis());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/workspace/project",
        "C:\\workspace\\project",
        "C:/workspace/project",
        "\\\\server\\share\\project",
        "//server/share/project",
        "${MCP_CWD}"
      })
  void acceptsTargetOsAbsoluteOrWholeVariableCwd(String cwd) {
    // 意图：Platform 不借用 Backend 文件系统解析远端路径，接受 Unix、Windows drive/UNC 与整值变量
    McpServerCreateDTO dto = localCreateWithCwd(cwd);

    LocalConnectionConfig config =
        (LocalConnectionConfig)
            McpServerMutationValidator.normalizeCreate(dto).config().connectionConfig();

    assertEquals(cwd, config.cwd());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "relative/path",
        "C:relative",
        "\\root-relative",
        "~/.agents",
        "$MCP_CWD",
        "/workspace/${PROJECT}",
        "C:\\workspace\\%PROJECT%"
      })
  void rejectsRelativeOrPartiallyExpandedCwd(String cwd) {
    // 意图：保存边界拒绝目标 OS 上不绝对或无法按整值规则解析的 cwd
    McpServerCreateDTO dto = localCreateWithCwd(cwd);

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
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
    dto.setConfigJson(REMOTE_CONFIG);

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsOverlongNameOnCreate() {
    // 意图：验证名称超过 32 字符被拒绝
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("a".repeat(33));
    dto.setConfigJson(REMOTE_CONFIG);

    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
  }

  @Test
  void rejectsInvalidConfigOnCreate() {
    // 意图：验证非法 configJson 在创建时被拒绝且不泄露敏感信息
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("mcp_test");
    dto.setConfigJson("{invalid-json}");

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> McpServerMutationValidator.normalizeCreate(dto));
    assertFalse(ex.getMessage().contains("invalid-json"));
  }

  @Test
  void normalizesValidUpdateDTO() {
    // 意图：验证合法更新输入被正常接受
    McpServerUpdateDTO dto = new McpServerUpdateDTO();
    dto.setExpectedVersion("1");
    dto.setConfigJson(REMOTE_CONFIG);

    McpServerMutationValidator.NormalizedUpdate normalized =
        McpServerMutationValidator.normalizeUpdate(dto);
    assertNotNull(normalized);
    assertEquals(McpConnectionType.REMOTE, normalized.config().connectionType());
  }

  @Test
  void rejectsNullUpdateDTO() {
    // 意图：验证 null 更新请求体抛出异常
    assertThrows(
        AiValidationException.class, () -> McpServerMutationValidator.normalizeUpdate(null));
  }

  private static McpServerCreateDTO localCreateWithCwd(String cwd) {
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName("local_path_test");
    dto.setConfigJson(
        """
        {
          "type": "local",
          "environmentId": "11111111-1111-1111-1111-111111111111",
          "command": ["node", "server.js"],
          "cwd": %s
        }
        """
            .formatted(quoteJson(cwd)));
    return dto;
  }

  private static String quoteJson(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }
}
