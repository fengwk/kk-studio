package fun.fengwk.kkstudio.harness.daemon.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.util.List;

/**
 * 固定 {@code mcp_call_tool} 桥接工具：按精确 server/tool 查找并执行 MCP 调用，保留上游 isError 与 文本/结构化 JSON 结果；未知/未
 * READY server 或未知工具是确定性错误。
 */
public final class McpCallToolTool extends AbstractMcpBridgeTool {

  public McpCallToolTool(McpServerRegistry registry) {
    super(registry, EnvironmentToolCatalog.require("mcp_call_tool"));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String server = string(args, "server");
    String tool = string(args, "tool");
    JsonNode arguments = args.get("arguments");
    if (arguments == null || !arguments.isObject()) {
      throw new IllegalArgumentException("arguments is required and must be a JSON object");
    }
    McpServerClient client = registry.find(server).orElse(null);
    if (client == null) {
      return error(request.call().id(), "MCP server is unknown or not ready: " + server);
    }
    // 对 READY 冻结工具列表做精确本地校验：未知工具是确定性错误，绝不触达 client.call。
    if (!registry.hasTool(server, tool)) {
      return error(request.call().id(), "unknown MCP tool on server " + server + ": " + tool);
    }
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    McpCallOutcome outcome =
        client.call(new McpToolRequest(tool, OBJECT_MAPPER.writeValueAsString(arguments)));
    if (outcome.isError()) {
      return error(request.call().id(), outcome.text());
    }
    String id = request.call().id();
    String text = outcome.text();
    if (isJsonValue(text)) {
      return new ToolResult(id, List.of(new JsonToolContent(text)), false, "{}", false);
    }
    return new ToolResult(id, List.of(new TextToolContent(text)), false, "{}", false);
  }

  private boolean isJsonValue(String text) {
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(text);
      return parsed != null && (parsed.isObject() || parsed.isArray());
    } catch (Exception error) {
      return false;
    }
  }
}
