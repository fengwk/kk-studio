package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpServerDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpToolDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;

import java.util.Map;

/** 验证持有完整 MCP 配置或 schema 的内部对象不会经 {@code toString()} 泄露内容。 */
class McpSensitiveToStringTest {

  private static final String SECRET = "sensitive-mcp-marker";

  @Test
  void redactsHeadersAndSchemaValues() {
    // 意图：日志格式化领域对象、DO 与配置 DTO 时均不出现 headers 值或 schema 内容
    McpServer server = new McpServer();
    server.setName("sensitive_server");
    server.setUrl("https://mcp.example.com/mcp");
    server.setHeaders(Map.of("Authorization", "Bearer " + SECRET));

    McpServerDO serverDO = new McpServerDO();
    serverDO.setName("sensitive_server");
    serverDO.setUrl("https://mcp.example.com/mcp");
    serverDO.setHeadersJson("{\"Authorization\":\"Bearer " + SECRET + "\"}");

    McpTool tool = new McpTool();
    tool.setName("mcp_sensitive_server_probe");
    tool.setDescription(SECRET);
    tool.setInputSchemaJson("{\"secret\":\"" + SECRET + "\"}");

    McpToolDO toolDO = new McpToolDO();
    toolDO.setName("mcp_sensitive_server_probe");
    toolDO.setDescription(SECRET);
    toolDO.setInputSchemaJson("{\"secret\":\"" + SECRET + "\"}");

    McpServerConfigDTO configDTO = new McpServerConfigDTO();
    configDTO.setName("sensitive_server");
    configDTO.setUrl("https://mcp.example.com/mcp");
    configDTO.setHeaders(Map.of("Authorization", "Bearer " + SECRET));

    assertFalse(server.toString().contains(SECRET));
    assertFalse(server.toString().contains("Authorization"));
    assertFalse(serverDO.toString().contains(SECRET));
    assertFalse(tool.toString().contains(SECRET));
    assertFalse(toolDO.toString().contains(SECRET));
    // 显式配置 DTO 允许暴露 URL（不含 user-info 凭据），但绝不暴露 headers
    assertFalse(configDTO.toString().contains("Authorization"));

    // 公开安全视图只暴露元数据，物理上不存在 headers 字段
    McpServerDTO publicDTO = new McpServerDTO();
    publicDTO.setName("sensitive_server");
    assertTrue(publicDTO.toString().contains("sensitive_server"));
    assertFalse(publicDTO.toString().contains(SECRET));
  }
}
