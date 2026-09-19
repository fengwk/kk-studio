package fun.fengwk.kkstudio.harness.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 LangChain4j {@link DefaultMcpClient} 实现的 {@link McpClient} 包装器。
 *
 * <p>SDK 的 {@code executeTool}/{@code listTools} 是同步阻塞调用，公开 API 不提供 per-call 取消句柄，因此本实现不以 {@code
 * CompletableFuture.supplyAsync(...).cancel(...)} 冒充协议取消。
 *
 * <p>取消与超时都按「调用级」处理：立即结束本地等待，且不关闭共享 client，因此同一 client 上的其它并发调用不受影响。 Streamable HTTP 传输通过关闭
 * per-request SSE 流取消，无需额外的协议取消通道。
 */
final class LangChainMcpClient implements McpClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final DefaultMcpClient client;
  private final McpTransport transport;
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "mcp-client-worker");
            thread.setDaemon(true);
            return thread;
          });

  LangChainMcpClient(DefaultMcpClient client, McpTransport transport) {
    this.client = Objects.requireNonNull(client, "client");
    this.transport = transport;
  }

  @Override
  public List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token) {
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(token, "token");
    List<ToolSpecification> specifications =
        runOperation(deadline, token, "MCP listTools", client::listTools);
    List<McpToolDefinition> definitions = new ArrayList<>(specifications.size());
    for (ToolSpecification specification : specifications) {
      definitions.add(toDefinition(specification));
    }
    return List.copyOf(definitions);
  }

  @Override
  public McpToolCallResult callTool(
      String name, String argumentsJson, McpDeadline deadline, McpCancellationToken token) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(token, "token");
    ToolExecutionRequest request =
        ToolExecutionRequest.builder()
            .name(name)
            .arguments(normalizeArguments(argumentsJson))
            .build();
    try {
      ToolExecutionResult executionResult =
          runOperation(deadline, token, "MCP tool call", () -> client.executeTool(request));
      return new McpToolCallResult(
          executionResult.isError(),
          extractContents(executionResult),
          extractDetailsJson(executionResult));
    } catch (ToolExecutionException error) {
      // SDK 把「工具以错误结束」抛成异常而不是返回结果：那仍是工具自己的可读输出，必须按 error 结果透传，
      // 否则上层会把正常的工具错误误判为连接/进程失败并销毁共享 client。
      return McpToolCallResult.errorText(errorMessageOf(error));
    }
  }

  /** 在 worker 线程上执行一次 SDK 阻塞调用，并按总预算等待。 */
  private <T> T runOperation(
      McpDeadline deadline, McpCancellationToken token, String what, SdkCall<T> sdkCall) {
    deadline.checkNotExpired();
    if (token.isCancelled()) {
      throw new McpCancelledException("MCP operation cancelled by caller");
    }
    Future<T> future =
        workers.submit(
            () -> {
              try {
                return sdkCall.call();
              } catch (CancellationException error) {
                throw new McpCancelledException("MCP operation cancelled by caller");
              } finally {
                // worker 线程会被池复用：异常路径留下的 interrupt 状态会污染后续任务的取消判定。
                Thread.interrupted();
              }
            });
    token.onCancel(() -> future.cancel(true));
    try {
      Duration waitBudget = deadline.requireRemaining();
      return future.get(Math.max(1L, waitBudget.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | McpTimeoutException error) {
      future.cancel(true);
      throw new McpTimeoutException(what + " timed out");
    } catch (CancellationException error) {
      throw new McpCancelledException("MCP operation cancelled by caller");
    } catch (ExecutionException error) {
      Throwable cause = error.getCause() != null ? error.getCause() : error;
      // 取消会让 SDK 把中断包装成普通执行失败：调用方已决定取消时必须如实报告取消，否则上层会把
      // 「调用方主动取消」误判为连接或进程失败，进而销毁仍健康的共享 client。
      if (token.isCancelled()) {
        throw new McpCancelledException("MCP operation cancelled by caller");
      }
      if (cause instanceof McpCancelledException cancelled) {
        throw cancelled;
      }
      if (cause instanceof ToolExecutionException toolError) {
        // 工具自身以错误结束：这是可读的工具输出而不是传输失败，必须原样交给调用点映射为 error 结果。
        throw toolError;
      }
      throw new McpException(what + " failed");
    } catch (InterruptedException error) {
      future.cancel(true);
      if (token.isCancelled()) {
        throw new McpCancelledException("MCP operation cancelled by caller");
      }
      Thread.currentThread().interrupt();
      throw new McpException(what + " interrupted");
    }
  }

  private McpToolDefinition toDefinition(ToolSpecification specification) {
    String inputSchemaJson = "{}";
    if (specification.parameters() != null) {
      Map<String, Object> map = JsonSchemaElementJsonUtils.toMap(specification.parameters());
      try {
        inputSchemaJson = OBJECT_MAPPER.writeValueAsString(map);
      } catch (JsonProcessingException error) {
        throw new McpException("failed to encode MCP tool input schema");
      }
    }
    return new McpToolDefinition(
        specification.name(), specification.description(), inputSchemaJson);
  }

  /**
   * 校验并规范化工具入参：只接受严格 JSON object。
   *
   * <p>复用仓库统一的严格 JSON 门禁 {@link JsonValues}：重复键与尾随 token 都属于畸形输入，必须本地拒绝，而不是让服务端按「哪个键胜出」的偶然实现执行。
   * 错误文本恒为固定不透明字符串，绝不回显 payload 片段，也不保留底层解析异常作为 cause。
   */
  private static String normalizeArguments(String argumentsJson) {
    try {
      return JsonValues.requireJsonObject(argumentsJson, "MCP tool arguments");
    } catch (IllegalArgumentException error) {
      throw new McpException("MCP tool arguments must be a strict JSON object");
    }
  }

  private static List<ResultContent> extractContents(ToolExecutionResult executionResult) {
    if (executionResult.result() instanceof List<?> list && !list.isEmpty()) {
      List<ResultContent> contents = new ArrayList<>(list.size());
      for (Object item : list) {
        if (item instanceof ResultContent content) {
          contents.add(content);
        }
      }
      if (!contents.isEmpty()) {
        return contents;
      }
    }
    String resultText = executionResult.resultText();
    if (resultText != null && !resultText.isEmpty()) {
      return List.of(new TextResultContent(resultText));
    }
    return List.of(new TextResultContent(""));
  }

  private static String extractDetailsJson(ToolExecutionResult executionResult) {
    if (executionResult.attributes() != null && !executionResult.attributes().isEmpty()) {
      try {
        return OBJECT_MAPPER.writeValueAsString(executionResult.attributes());
      } catch (JsonProcessingException ignored) {
        return "{}";
      }
    }
    return "{}";
  }

  @Override
  public void close() {
    workers.shutdownNow();
    try {
      client.close();
    } catch (Exception ignored) {
      // 关闭失败不影响后续清理。
    }
    if (transport != null) {
      try {
        transport.close();
      } catch (Exception ignored) {
        // 关闭失败不影响后续清理。
      }
    }
  }

  /**
   * 提取 SDK 工具执行异常中的可读文本。
   *
   * <p>SDK 把服务端 {@code isError} 结果的消息压进异常文本；这里只取文本，不向上抛出异常对象，避免异常消息中的本地事实泄漏到模块边界之外。
   */
  private static String errorMessageOf(ToolExecutionException error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? "MCP tool returned an error result" : message;
  }

  /** 底层 SDK 阻塞调用。 */
  @FunctionalInterface
  private interface SdkCall<T> {
    T call();
  }
}
