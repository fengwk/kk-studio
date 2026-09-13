package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 锁定本模块取消设计所依赖的 SDK 线程契约。
 *
 * <p>{@link McpDispatchGate} 基于 {@link ThreadLocal} 把「调用级取消状态」与「发起该请求的线程」关联：SDK 在 {@code
 * beforeExecuteTool} 回调里暴露真实 request id，随后才把请求交给传输写出。该设计要求两者运行在<strong>同一个线程</strong>上。
 *
 * <p>SDK 目前是同步实现（{@code executeTool} 内先通知监听器、再调用 {@code
 * transport.executeOperationWithResponse}，中间不切换线程）， 但这是外部依赖的行为而非编译期保证。本测试在真实 SDK
 * 上断言该不变量：一旦未来升级引入线程切换，这里会先失败，而不是让取消在生产里静默失效。
 */
class McpSdkThreadingContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 记录「监听器回调线程」与「传输派发线程」，并对握手与工具调用给出合法响应。 */
  private static final class ThreadRecordingTransport implements McpTransport {

    private final AtomicReference<String> transportThreadName = new AtomicReference<>();
    private final AtomicReference<Long> dispatchedRequestId = new AtomicReference<>();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final AtomicInteger transportSequence = new AtomicInteger(-1);

    @Override
    public void start(McpOperationHandler handler) {
      // 本测试只关心调用发生在哪个线程，不需要真实子进程
    }

    @Override
    public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) {
      return respond(
          request.getId(),
          "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},"
              + "\"serverInfo\":{\"name\":\"thread-probe\",\"version\":\"1.0\"}}");
    }

    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage message) {
      return executeOperationWithResponse(new McpCallContext(null, message));
    }

    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpCallContext context) {
      transportThreadName.set(Thread.currentThread().getName());
      transportSequence.set(sequence.incrementAndGet());
      if (context != null && context.message() != null) {
        dispatchedRequestId.set(context.message().getId());
      }
      return respond(
          context != null && context.message() != null ? context.message().getId() : null,
          "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
    }

    @Override
    public void executeOperationWithoutResponse(McpClientMessage message) {}

    @Override
    public void executeOperationWithoutResponse(McpCallContext context) {}

    @Override
    public void checkHealth() {}

    @Override
    public void onFailure(Runnable callback) {}

    @Override
    public void close() {}

    private static CompletableFuture<JsonNode> respond(Long id, String resultJson) {
      String json = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
      try {
        return CompletableFuture.completedFuture(MAPPER.readTree(json));
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    }
  }

  /**
   * 断言 SDK 在同一线程上先回调 {@code beforeExecuteTool}、再调用 {@code transport.executeOperationWithResponse}。
   *
   * <p>这是「监听器拿到的 request id 能对应本次传输派发」的前提；线程不同则 {@link ThreadLocal} 闸门失效。
   */
  @Test
  @Timeout(30)
  void listenerCallbackAndTransportDispatchShareTheSameThread() {
    ThreadRecordingTransport transport = new ThreadRecordingTransport();
    AtomicReference<String> listenerThread = new AtomicReference<>();
    McpClientListener listener =
        new McpClientListener() {
          @Override
          public void beforeExecuteTool(McpCallContext context) {
            listenerThread.set(Thread.currentThread().getName());
          }
        };

    DefaultMcpClient client =
        DefaultMcpClient.builder()
            .transport(transport)
            .key("thread-contract")
            .clientName("thread-contract")
            .protocolVersion("2025-11-25")
            .cacheToolList(false)
            .subscribeToToolListChanges(false)
            .autoHealthCheck(false)
            .listener(listener)
            .build();
    try {
      var result =
          client.executeTool(ToolExecutionRequest.builder().name("probe").arguments("{}").build());

      // 先确认调用真的走完了 SDK 工具执行通路，否则线程断言可能是空跑
      assertThat(result).isNotNull();
      assertThat(transport.transportThreadName.get()).isNotNull();
      assertThat(listenerThread.get())
          .as("SDK 必须在同一线程上完成回调与派发，否则基于 ThreadLocal 的取消闸门会失效")
          .isNotNull()
          .isEqualTo(transport.transportThreadName.get());
    } finally {
      client.close();
    }
  }

  /** 传输的 request id 透传：监听器看到的 id 必须与传输派发时看到的一致，且监听回调必须在传输派发前执行。 */
  @Test
  @Timeout(30)
  void listenerSeesTheSameRequestIdAsTransport() {
    ThreadRecordingTransport transport = new ThreadRecordingTransport();
    AtomicReference<Long> listenerRequestId = new AtomicReference<>();
    AtomicInteger listenerSequence = new AtomicInteger(-1);

    DefaultMcpClient client =
        DefaultMcpClient.builder()
            .transport(transport)
            .key("thread-contract-2")
            .clientName("thread-contract")
            .protocolVersion("2025-11-25")
            .cacheToolList(false)
            .subscribeToToolListChanges(false)
            .autoHealthCheck(false)
            .listener(
                new McpClientListener() {
                  @Override
                  public void beforeExecuteTool(McpCallContext context) {
                    listenerSequence.set(transport.sequence.incrementAndGet());
                    listenerRequestId.set(context.message().getId());
                  }
                })
            .build();
    try {
      client.executeTool(ToolExecutionRequest.builder().name("probe").arguments("{}").build());
      assertThat(listenerRequestId.get()).as("工具调用必须带 request id").isNotNull();
      assertThat(transport.dispatchedRequestId.get()).as("传输必须记录到实际派发的 request id").isNotNull();
      assertThat(listenerRequestId.get()).isEqualTo(transport.dispatchedRequestId.get());
      assertThat(listenerSequence.get())
          .as("监听器回调必须在传输派发之前执行")
          .isPositive()
          .isLessThan(transport.transportSequence.get());
    } finally {
      client.close();
    }
  }
}
