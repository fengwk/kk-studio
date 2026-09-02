package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生产 {@link McpToolClientFactory}：按 {@link McpConnectionSpec} 构建 LangChain4j Streamable HTTP
 * transport/client，并完成 initialize 握手。
 *
 * <p>每次 {@link #create} 都产生全新 client（per-call create + close，绝不缓存）；配置仅 URL / Bearer /
 * timeout，Bearer token 以 {@code Authorization} header 注入。任何构建/握手失败都向上抛出RuntimeException， 且已被创建的底层
 * client 恰好关闭一次，绝不泄漏半初始化连接。
 */
@Slf4j
@Component
public final class LangChainMcpToolClientFactory implements McpToolClientFactory {

  private static final String CLIENT_NAME = "kk-studio-platform";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public McpToolClient create(McpConnectionSpec spec) {
    Objects.requireNonNull(spec, "spec");
    Duration timeout = Duration.ofMillis(spec.timeoutMillis());
    DefaultMcpClient client = null;
    try {
      McpTransport transport = transport(spec, timeout);
      client =
          DefaultMcpClient.builder()
              .transport(transport)
              .key("platform-mcp")
              .clientName(CLIENT_NAME)
              .initializationTimeout(timeout)
              .toolExecutionTimeout(timeout)
              // 关闭 list 缓存与 list-change 订阅：本平台 client 每次发现都是完整新会话。
              .cacheToolList(false)
              .subscribeToToolListChanges(false)
              .autoHealthCheck(false)
              .build();
      // listTools 触发 initialize 握手；失败时在 catch 中恰好关闭一次。
      List<McpRemoteToolSpec> tools = listTools(client);
      return new LangChainMcpToolClient(client, tools);
    } catch (RuntimeException error) {
      if (client != null) {
        try {
          client.close();
        } catch (RuntimeException closeError) {
          log.debug("failed to close half-initialized MCP client", closeError);
        }
      }
      throw error;
    }
  }

  private static McpTransport transport(McpConnectionSpec spec, Duration timeout) {
    StreamableHttpMcpTransport.Builder builder =
        StreamableHttpMcpTransport.builder().url(spec.url()).timeout(timeout);
    Map<String, String> headers = new LinkedHashMap<>();
    if (spec.bearerToken() != null) {
      headers.put("Authorization", "Bearer " + spec.bearerToken());
    }
    if (!headers.isEmpty()) {
      builder.customHeaders(headers);
    }
    return builder.build();
  }

  private static List<McpRemoteToolSpec> listTools(DefaultMcpClient client) {
    List<ToolSpecification> specifications = client.listTools();
    List<McpRemoteToolSpec> specs = new ArrayList<>(specifications.size());
    for (ToolSpecification specification : specifications) {
      String inputSchemaJson;
      if (specification.parameters() == null) {
        inputSchemaJson = "{}";
      } else {
        Map<String, Object> map = JsonSchemaElementJsonUtils.toMap(specification.parameters());
        try {
          inputSchemaJson = OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException error) {
          inputSchemaJson = "{}";
        }
      }
      specs.add(
          new McpRemoteToolSpec(
              specification.name(), specification.description(), inputSchemaJson));
    }
    return List.copyOf(specs);
  }

  /** 已完成握手的 per-call client 包装；LangChain4j 类型不越过 {@link McpToolClient}。 */
  private static final class LangChainMcpToolClient implements McpToolClient {

    private final DefaultMcpClient client;
    private final List<McpRemoteToolSpec> tools;

    private LangChainMcpToolClient(DefaultMcpClient client, List<McpRemoteToolSpec> tools) {
      this.client = Objects.requireNonNull(client, "client");
      this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public List<McpRemoteToolSpec> listTools() {
      return tools;
    }

    @Override
    public McpToolCallOutcome callTool(
        String sourceToolName, String argumentsJson, String toolCallId) {
      try {
        ToolExecutionResult result =
            client.executeTool(
                ToolExecutionRequest.builder()
                    .name(sourceToolName)
                    .arguments(argumentsJson)
                    .build());
        String text = result.resultText();
        return McpToolCallOutcome.of(
            new ToolResult(
                toolCallId,
                List.of(new TextResultContent(text == null ? "" : text)),
                result.isError(),
                "{}"));
      } catch (RuntimeException error) {
        // 传输/协议异常可能携带 URL、header 或 token；模型侧只接收稳定的非敏感错误。
        log.info("MCP tool call failed for tool {}", sourceToolName, error);
        return McpToolCallOutcome.failure(toolCallId);
      }
    }

    @Override
    public void close() {
      client.close();
    }
  }
}
