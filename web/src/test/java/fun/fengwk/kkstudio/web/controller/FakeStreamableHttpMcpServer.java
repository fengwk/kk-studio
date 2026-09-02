package fun.fengwk.kkstudio.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Web 控制器测试专用的轻量级 Streamable HTTP MCP Server Mock。 */
class FakeStreamableHttpMcpServer implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpServer server;
  private final List<DiscoveredTool> tools = new CopyOnWriteArrayList<>();

  record DiscoveredTool(String name, String description, JsonNode inputSchema) {}

  FakeStreamableHttpMcpServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/mcp",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
              sendResponse(exchange, 405, "Method Not Allowed");
              return;
            }

            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            if (bodyBytes.length == 0) {
              sendResponse(exchange, 202, "");
              return;
            }

            JsonNode request;
            try {
              request = MAPPER.readTree(bodyBytes);
            } catch (Exception e) {
              sendResponse(exchange, 400, "Invalid JSON");
              return;
            }

            String method = request.has("method") ? request.get("method").asText() : "";
            JsonNode id = request.get("id");

            if ("server/discover".equals(method)) {
              ObjectNode err = MAPPER.createObjectNode();
              err.put("jsonrpc", "2.0");
              if (id != null) {
                err.set("id", id);
              }
              ObjectNode errorObj = err.putObject("error");
              errorObj.put("code", -32601);
              errorObj.put("message", "Method not found");
              sendJson(exchange, 200, err);
              return;
            }

            if ("initialize".equals(method)) {
              ObjectNode resp = MAPPER.createObjectNode();
              resp.put("jsonrpc", "2.0");
              if (id != null) {
                resp.set("id", id);
              }
              ObjectNode result = resp.putObject("result");
              result.put("protocolVersion", "2025-11-25");
              ObjectNode caps = result.putObject("capabilities");
              caps.putObject("tools");
              ObjectNode serverInfo = result.putObject("serverInfo");
              serverInfo.put("name", "fake-web-mcp");
              serverInfo.put("version", "1.0.0");
              sendJson(exchange, 200, resp);
              return;
            }

            if ("notifications/initialized".equals(method)) {
              sendResponse(exchange, 202, "");
              return;
            }

            if ("tools/list".equals(method)) {
              ObjectNode resp = MAPPER.createObjectNode();
              resp.put("jsonrpc", "2.0");
              if (id != null) {
                resp.set("id", id);
              }
              ObjectNode result = resp.putObject("result");
              ArrayNode toolsArr = result.putArray("tools");
              for (DiscoveredTool tool : tools) {
                ObjectNode toolNode = toolsArr.addObject();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("inputSchema", tool.inputSchema());
              }
              sendJson(exchange, 200, resp);
              return;
            }

            ObjectNode defaultResp = MAPPER.createObjectNode();
            defaultResp.put("jsonrpc", "2.0");
            if (id != null) {
              defaultResp.set("id", id);
            }
            defaultResp.putObject("result");
            sendJson(exchange, 200, defaultResp);
          }
        });
    server.start();
  }

  String endpointUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
  }

  void addTool(String name, String description, String inputSchemaJson) {
    try {
      JsonNode schema = MAPPER.readTree(inputSchemaJson);
      tools.add(new DiscoveredTool(name, description, schema));
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid json", e);
    }
  }

  private static void sendJson(HttpExchange exchange, int statusCode, JsonNode json)
      throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(json);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static void sendResponse(HttpExchange exchange, int statusCode, String response)
      throws IOException {
    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
