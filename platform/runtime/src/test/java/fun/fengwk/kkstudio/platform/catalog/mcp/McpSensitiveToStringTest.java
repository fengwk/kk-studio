package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;

import java.util.Map;

/** 验证持有完整 MCP 配置或 schema 的内部对象不会经 {@code toString()} 泄露内容。 */
class McpSensitiveToStringTest {

  private static final String SECRET = "sensitive-mcp-marker";

  @Test
  void redactsHeadersAndSchemaValues() {
    // 意图：日志格式化领域对象、规范化配置与配置 DTO 时均不出现 headers 值或 schema 内容
    McpServer server =
        McpServer.builder()
            .name("sensitive_server")
            .url("https://mcp.example.com/mcp")
            .headers(Map.of("Authorization", "Bearer " + SECRET))
            .build();
    McpTool tool =
        McpTool.builder()
            .name("mcp_sensitive_server_probe")
            .description(SECRET)
            .inputSchemaJson("{\"secret\":\"" + SECRET + "\"}")
            .build();
    McpConfigParser.HttpConfig config =
        McpConfigParser.normalizeHttpConfig(
            "https://mcp.example.com/mcp", Map.of("Authorization", "Bearer " + SECRET), true, 1L);

    McpServerConfigDTO configDTO = new McpServerConfigDTO();
    configDTO.setName("sensitive_server");
    configDTO.setUrl("https://mcp.example.com/mcp");
    configDTO.setHeaders(Map.of("Authorization", "Bearer " + SECRET));

    assertFalse(server.toString().contains(SECRET));
    assertFalse(server.toString().contains("Authorization"));
    assertFalse(tool.toString().contains(SECRET));
    assertFalse(config.toString().contains(SECRET));
    // 显式配置 DTO 允许暴露 URL（不含 user-info 凭据），但绝不暴露 headers
    assertFalse(configDTO.toString().contains("Authorization"));

    // 公开安全视图只暴露元数据，物理上不存在 headers 字段
    McpServerDTO publicDTO = new McpServerDTO();
    publicDTO.setName("sensitive_server");
    assertTrue(publicDTO.toString().contains("sensitive_server"));
    assertFalse(publicDTO.toString().contains(SECRET));
  }
}
