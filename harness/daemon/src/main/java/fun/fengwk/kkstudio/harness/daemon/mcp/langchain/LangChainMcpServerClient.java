package fun.fengwk.kkstudio.harness.daemon.mcp.langchain;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import fun.fengwk.kkstudio.harness.daemon.mcp.McpCallOutcome;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerClient;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpToolRequest;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 包装 LangChain4j {@link DefaultMcpClient} 的 daemon 端口实现；LangChain4j 类型不越过本适配器。 */
final class LangChainMcpServerClient implements McpServerClient {

  private final String name;
  private final DefaultMcpClient client;

  LangChainMcpServerClient(String name, DefaultMcpClient client) {
    this.name = Objects.requireNonNull(name, "name");
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<McpToolSpec> listTools() {
    List<ToolSpecification> specifications = client.listTools();
    List<McpToolSpec> result = new ArrayList<>(specifications.size());
    for (ToolSpecification specification : specifications) {
      String specificationName = specification.name();
      String description = specification.description();
      if (description == null || description.isBlank()) {
        description = specificationName;
      }
      result.add(new McpToolSpec(specificationName, description, specification.toJson()));
    }
    return List.copyOf(result);
  }

  @Override
  public McpCallOutcome call(McpToolRequest request) {
    ToolExecutionRequest executionRequest =
        ToolExecutionRequest.builder()
            .name(request.toolName())
            .arguments(request.argumentsJson())
            .build();
    try {
      ToolExecutionResult result = client.executeTool(executionRequest);
      String text = result.resultText();
      return new McpCallOutcome(result.isError(), text == null ? "" : text);
    } catch (RuntimeException error) {
      // 上游 isError/JSON-RPC 错误统一转换为模型可见的错误结果；中断在桥接工具层按取消处理。
      String message = error.getMessage();
      if (message == null || message.isBlank()) {
        message = error.getClass().getSimpleName();
      }
      return new McpCallOutcome(true, message);
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
