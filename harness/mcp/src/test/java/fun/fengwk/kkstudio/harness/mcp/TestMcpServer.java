package fun.fengwk.kkstudio.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 测试用标准输入输出本地 MCP 服务端进程。
 *
 * <p>支持通过系统属性控制行为：延迟握手（验证单一总预算覆盖初始化）与记录 {@code notifications/cancelled}（验证协议级取消确实到达服务端）。
 */
public class TestMcpServer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** tool call 并发执行：真实 MCP 服务端不会让一个慢调用阻塞同一连接上的其它请求。 */
  private static final ExecutorService TOOL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /** 已收到取消通知的 request id；父进程通过 stdout 回读，用于断言协议取消真实发生。 */
  private static final Set<Long> CANCELLED_IDS = ConcurrentHashMap.newKeySet();

  /** 客户端在 initialize 中实际请求的协议版本；父进程通过工具回读，用于断言真实协商契约。 */
  private static volatile String requestedProtocolVersion = "";

  /** 慢 tools/list 已进入服务端；父进程据此消除取消测试的固定 sleep。 */
  private static volatile boolean listStarted;

  public static void main(String[] args) throws Exception {
    // 写入 stderr 敏感机密数据，用于验证 stderr 不会被记录或泄漏
    System.err.println("CONFIDENTIAL_STDERR_TOKEN_SUPER_SECRET_12345");
    System.err.flush();

    long initDelayMillis = Long.getLong("test.mcp.initDelayMillis", 0L);
    long listDelayMillis = Long.getLong("test.mcp.listDelayMillis", 0L);

    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode request = MAPPER.readTree(line);
      String method = request.path("method").asText("");
      JsonNode idNode = request.get("id");
      Long id = idNode != null && idNode.isNumber() ? idNode.asLong() : null;

      if ("initialize".equals(method)) {
        requestedProtocolVersion = request.path("params").path("protocolVersion").asText("");
        if (initDelayMillis > 0) {
          Thread.sleep(initDelayMillis);
        }
        String resp =
            "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"test-server\",\"version\":\"1.0\"}}}";
        System.out.println(resp);
        System.out.flush();
      } else if ("notifications/initialized".equals(method)) {
        // notification 不需要回复
      } else if ("notifications/cancelled".equals(method)) {
        // 记录取消的 request id，供 cancelling_request 工具回读
        JsonNode requestIdNode = request.path("params").path("requestId");
        if (requestIdNode.isNumber()) {
          CANCELLED_IDS.add(requestIdNode.asLong());
        }
      } else if ("tools/list".equals(method)) {
        Long listId = id;
        TOOL_EXECUTOR.execute(
            () -> {
              try {
                listStarted = true;
                if (listDelayMillis > 0) {
                  Thread.sleep(listDelayMillis);
                }
                respondToolList(listId);
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              }
            });
      } else if ("tools/call".equals(method)) {
        String toolName = request.path("params").path("name").asText();
        JsonNode arguments = request.path("params").path("arguments");
        Long callId = id;
        TOOL_EXECUTOR.execute(
            () -> {
              try {
                handleToolCall(callId, toolName, arguments);
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              } catch (Exception error) {
                // 测试服务端不向客户端泄漏内部错误细节
              }
            });
      }
    }
  }

  private static void handleToolCall(Long id, String toolName, JsonNode arguments)
      throws Exception {
    if ("check_cwd".equals(toolName)) {
      String cwd = System.getProperty("user.dir");
      respond(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + cwd + "\"}]}");
    } else if ("check_env".equals(toolName)) {
      String varName = arguments.path("name").asText("TEST_VAR");
      String val = System.getenv(varName);
      respond(
          id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + (val == null ? "" : val) + "\"}]}");
    } else if ("rich_result".equals(toolName)) {
      respond(
          id,
          "{\"content\":["
              + "{\"type\":\"text\",\"text\":\"plain text\"},"
              + "{\"type\":\"image\",\"data\":\"AQID\",\"mimeType\":\"image/png\"},"
              + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///test.txt\",\"text\":\"content\"}}"
              + "]}");
    } else if ("init_elapsed".equals(toolName)) {
      // 回读本进程启动时记录的初始化延迟，证明「初始化已被消耗的总预算」
      long initDelayMillis = Long.getLong("test.mcp.initDelayMillis", 0L);
      respond(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + initDelayMillis + "\"}]}");
    } else if ("sleep_delay".equals(toolName)) {
      long millis = arguments.path("millis").asLong(2000);
      // 可中断睡眠：被取消后立刻返回，便于父进程观察「在途请求已结束」
      Thread.sleep(millis);
      respond(id, "{\"content\":[{\"type\":\"text\",\"text\":\"done sleeping\"}]}");
    } else if ("structured_result".equals(toolName)) {
      // 结构化结果走 SDK 的 structuredContent 分支，不经过自定义抽取器
      respond(
          id,
          "{\"structuredContent\":{\"answer\":42},\"content\":[{\"type\":\"text\",\"text\":\"structured ok\"}]}");
    } else if ("text_only".equals(toolName)) {
      // 纯文本结果 + _meta 属性：验证 details 与文本通路
      respondWithMeta(id, "{\"content\":[{\"type\":\"text\",\"text\":\"plain only\"}]}");
    } else if ("negotiated_protocol".equals(toolName)) {
      respond(
          id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + requestedProtocolVersion + "\"}]}");
    } else if ("cancelled_ids".equals(toolName)) {
      respond(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + CANCELLED_IDS + "\"}]}");
    } else if ("list_started".equals(toolName)) {
      respond(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + listStarted + "\"}]}");
    } else {
      respond(id, "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"unknown tool\"}]}");
    }
  }

  private static void respondToolList(Long id) {
    String result =
        "{\"tools\":["
            + "{\"name\":\"check_cwd\",\"description\":\"cwd\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"check_env\",\"description\":\"env\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"rich_result\",\"description\":\"rich\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"sleep_delay\",\"description\":\"sleep\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"init_elapsed\",\"description\":\"init\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"structured_result\",\"description\":\"structured\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"text_only\",\"description\":\"text\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"negotiated_protocol\",\"description\":\"protocol\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"duplicate_tool\",\"description\":\"dup1\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"duplicate_tool\",\"description\":\"dup2\",\"inputSchema\":{\"type\":\"object\"}}"
            + "]}";
    respond(id, result);
  }

  /** 返回带 _meta 属性的结果，用于验证 SDK 会把元数据放入 ToolExecutionResult.attributes。 */
  private static synchronized void respondWithMeta(Long id, String resultJson) {
    System.out.println(
        "{\"jsonrpc\":\"2.0\",\"id\":"
            + id
            + ",\"result\":"
            + resultJson.substring(0, resultJson.length() - 1)
            + ",\"_meta\":{\"traceId\":\"abc\"}}}");
    System.out.flush();
  }

  /** 并发写 stdout 必须串行化，避免不同请求的响应字节相互交错。 */
  private static synchronized void respond(Long id, String resultJson) {
    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
    System.out.flush();
  }
}
