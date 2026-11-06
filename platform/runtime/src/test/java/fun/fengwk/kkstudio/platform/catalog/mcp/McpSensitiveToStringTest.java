package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.dto.McpServerDTO;
import fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO;

import java.time.Instant;
import java.util.Map;

/** 验证持有完整 MCP 配置或 schema 的内部对象与 HTTP DTO 不会经 {@code toString()} 泄露内容。 */
class McpSensitiveToStringTest {

  private static final String SECRET = "sensitive-mcp-marker";

  @Test
  void redactsHeadersAndSchemaValues() {
    // 意图：日志格式化领域对象、规范化配置与 HTTP DTO 时均不出现 headers 值或 schema 内容
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

    assertFalse(server.toString().contains(SECRET));
    assertFalse(server.toString().contains("Authorization"));
    assertFalse(tool.toString().contains(SECRET));
    assertFalse(config.toString().contains(SECRET));

    // 详情只陈述 headers 已配置，物理上不存在 header 值
    McpServerDTO detail = new McpServerDTO();
    detail.setName("sensitive_server");
    detail.setUrl("https://mcp.example.com/mcp");
    detail.setHeaders(new SecretConfiguredDTO(true));
    detail.setVersion(0L);
    detail.setCreatedAt(Instant.now());
    detail.setUpdatedAt(Instant.now());
    assertTrue(detail.toString().contains("sensitive_server"));
    assertFalse(detail.toString().contains(SECRET));
    assertFalse(detail.toString().contains("Authorization"));
  }
}
