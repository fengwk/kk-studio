package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpServerDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.impl.model.McpToolDO;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.LocalConnectionConfig;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpConnectionType;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.RemoteConnectionConfig;

import java.util.List;
import java.util.Map;

/** 验证持有完整 MCP 配置或 schema 的内部对象不会经 {@code toString()} 泄露内容。 */
class McpSensitiveToStringTest {

  private static final String SECRET = "sensitive-mcp-marker";

  @Test
  void redactsConnectionAndSchemaValues() {
    // 意图：日志格式化领域对象、DO、解析结果或连接 record 时均不出现敏感值
    McpServer server = new McpServer();
    server.setConnectionConfig(SECRET);
    McpServerDO serverDO = new McpServerDO();
    serverDO.setConnectionConfig(SECRET);
    McpTool tool = new McpTool();
    tool.setDescription(SECRET);
    tool.setInputSchemaJson(SECRET);
    McpToolDO toolDO = new McpToolDO();
    toolDO.setDescription(SECRET);
    toolDO.setInputSchemaJson(SECRET);
    LocalConnectionConfig local =
        new LocalConnectionConfig(List.of(SECRET), "/" + SECRET, Map.of("KEY", SECRET));
    RemoteConnectionConfig remote =
        new RemoteConnectionConfig("https://" + SECRET + ".example", Map.of("KEY", SECRET));
    McpConfigParser.ParsedMcpConfig parsed =
        new McpConfigParser.ParsedMcpConfig(
            McpConnectionType.REMOTE, null, SECRET, true, 60_000L, remote);

    assertFalse(server.toString().contains(SECRET));
    assertFalse(serverDO.toString().contains(SECRET));
    assertFalse(tool.toString().contains(SECRET));
    assertFalse(toolDO.toString().contains(SECRET));
    assertFalse(local.toString().contains(SECRET));
    assertFalse(remote.toString().contains(SECRET));
    assertFalse(parsed.toString().contains(SECRET));
  }
}
