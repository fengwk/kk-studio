package fun.fengwk.kkstudio.harness.daemon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerStatus;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 固定 {@code mcp_list_tools} 桥接工具：确定性 JSON 报告请求的 server 状态与 READY server 的工具 （name/description/完整输入
 * schema），不泄漏任何 secrets。
 */
public final class McpListToolsTool extends AbstractMcpBridgeTool {

  public McpListToolsTool(McpServerRegistry registry, ExecutorService executor) {
    super(registry, executor, EnvironmentToolCatalog.require("mcp_list_tools"));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String requested = optionalString(args, "server");
    List<DaemonMcpServerDescriptor> summaries =
        requested == null ? registry.snapshot() : singleSnapshot(requested);
    if (summaries == null) {
      return error(request.call().id(), "unknown MCP server: " + requested);
    }
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    ArrayNode servers = root.putArray("servers");
    for (DaemonMcpServerDescriptor summary : summaries) {
      servers.add(serverNode(summary));
    }
    return new ToolResult(
        request.call().id(),
        List.of(new JsonToolContent(OBJECT_MAPPER.writeValueAsString(root))),
        false,
        "{}");
  }

  @Override
  protected String failureMessage(Exception error) {
    return "MCP tool catalog rendering failed.";
  }

  private List<DaemonMcpServerDescriptor> singleSnapshot(String server) {
    for (DaemonMcpServerDescriptor summary : registry.snapshot()) {
      if (summary.name().equals(server)) {
        return List.of(summary);
      }
    }
    return null;
  }

  private ObjectNode serverNode(DaemonMcpServerDescriptor summary) throws Exception {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("name", summary.name());
    node.put("status", summary.status().name());
    if (summary.error() != null) {
      node.put("error", summary.error());
    } else {
      node.putNull("error");
    }
    ArrayNode tools = node.putArray("tools");
    if (summary.status() == DaemonMcpServerStatus.READY) {
      for (McpToolSpec spec : registry.toolSpecs(summary.name()).orElseThrow()) {
        ObjectNode toolNode = tools.addObject();
        toolNode.put("name", spec.name());
        toolNode.put("description", spec.description());
        toolNode.set("schema", OBJECT_MAPPER.readTree(spec.schemaJson()));
      }
    }
    return node;
  }
}
