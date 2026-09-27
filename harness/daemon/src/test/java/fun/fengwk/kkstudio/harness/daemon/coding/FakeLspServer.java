package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可控的 stdio LSP 假服务器：手工实现 {@code Content-Length} 分帧，与被测客户端使用的协议库相互独立。
 *
 * <p>用法：{@code java -cp <test-classpath> ...FakeLspServer <mode> <transcript>
 * [childPidFile]}。所有收发消息以 JSONL 追加写入 transcript，测试据此断言真实帧、参数、通知与应答；{@code mode}
 * 用于确定性地制造慢响应、崩溃、忽视关闭、乱序输出等场景。
 *
 * <p>它是测试基座而不是被测对象：这里只实现协议必需的最小行为，故意不提供完整 LSP 语义。
 */
public final class FakeLspServer {

  /** 假服务器返回的反编译源码，同时用于断言源码正文不被路径改写。 */
  public static final String DECOMPILED_SOURCE = "class Decompiled {}\n";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DEFINITION_LINE = 9;
  private static final int DEFINITION_CHARACTER = 5;
  private static final long SLOW_INIT_MILLIS = 3_000;
  private static final long STUBBORN_SHUTDOWN_HOOK_MILLIS = 20_000;

  private final String mode;
  private final Path transcript;
  private final Path childPidFile;
  private final AtomicLong outboundId = new AtomicLong(900);

  private FakeLspServer(String mode, Path transcript, Path childPidFile) {
    this.mode = mode;
    this.transcript = transcript;
    this.childPidFile = childPidFile;
  }

  private static final int NOISE_BYTES = 2 * 1024;

  public static void main(String[] args) throws Exception {
    Path childPidFile = args.length > 2 && !args[2].isBlank() ? Path.of(args[2]) : null;
    new FakeLspServer(args[0], Path.of(args[1]), childPidFile).run();
  }

  private void run() throws Exception {
    record(
        MAPPER
            .createObjectNode()
            .put("event", "start")
            .put("mode", mode)
            .put("pid", ProcessHandle.current().pid())
            .put("cwd", Path.of("").toAbsolutePath().normalize().toString()));
    if (mode.equals("dead")) {
      System.exit(7);
    }
    if (mode.equals("stubborn")) {
      // 忽略 SIGTERM：关闭宽限到期后必须由强制终止收敛，同时验证后代进程也被清理。
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      Thread.sleep(STUBBORN_SHUTDOWN_HOOK_MILLIS);
                    } catch (InterruptedException ignored) {
                      Thread.currentThread().interrupt();
                    }
                  }));
      if (childPidFile != null) {
        spawnChild();
      }
    }
    if (mode.equals("garbage")) {
      // 协议消息之前出现非 LSP 输出：客户端必须容忍它并继续解析后续真实帧。
      OutputStream out = System.out;
      out.write("stray server banner\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
    serve(System.in, System.out);
  }

  private void spawnChild() throws IOException {
    Process child = new ProcessBuilder("/bin/sh", "-c", "exec sleep 600").start();
    record(MAPPER.createObjectNode().put("event", "child").put("pid", child.pid()));
    Files.writeString(childPidFile, String.valueOf(child.pid()), StandardCharsets.UTF_8);
  }

  private void serve(InputStream in, OutputStream out) throws IOException {
    while (true) {
      String headers = readHeaders(in);
      if (headers == null) {
        return;
      }
      int length = contentLength(headers);
      if (length < 0) {
        record(MAPPER.createObjectNode().put("event", "protocol-error").put("headers", headers));
        continue;
      }
      byte[] payload = in.readNBytes(length);
      if (payload.length < length) {
        return;
      }
      JsonNode message = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
      ObjectNode received = MAPPER.createObjectNode();
      received.put("event", "recv");
      received.set("message", message);
      record(received);
      if (!handle(message, out)) {
        return;
      }
    }
  }

  /**
   * @return {@code false} 表示服务器主动结束。
   */
  private boolean handle(JsonNode message, OutputStream out) throws IOException {
    String method = message.path("method").asText(null);
    if (method == null) {
      // 服务端请求的应答：记录即可，测试据此断言客户端如何回答我们。
      return true;
    }
    JsonNode id = message.get("id");
    if (id == null) {
      if (method.equals("exit")) {
        if (mode.equals("stubborn")) {
          return true;
        }
        out.flush();
        System.exit(0);
      }
      if (method.equals("initialized") && mode.equals("server-requests")) {
        sendServerRequests(out);
      }
      if (method.equals("initialized") && mode.equals("client-requests")) {
        sendClientRequests(out);
      }
      return true;
    }
    switch (method) {
      case "initialize" -> {
        if (mode.equals("slow-init")) {
          sleep(SLOW_INIT_MILLIS);
        }
        if (mode.equals("no-init")) {
          return true;
        }
        sendResult(out, id, initializeResult());
      }
      case "shutdown" -> {
        if (!mode.equals("stubborn")) {
          sendResult(out, id, MAPPER.nullNode());
        }
      }
      case "textDocument/definition" -> handleDefinition(message, id, out);
      case "workspace/symbol" -> sendResult(
          out,
          id,
          switch (mode) {
            case "null-symbols" -> MAPPER.nullNode();
            case "empty-symbols" -> MAPPER.createArrayNode();
            case "workspace-symbols" -> workspaceSymbols();
            case "odd-symbols" -> oddSymbols();
            default -> symbols();
          });
      case "java/classFileContents" -> sendResult(
          out, id, MAPPER.getNodeFactory().textNode(DECOMPILED_SOURCE));
      case "workspace/executeCommand" -> sendResult(
          out,
          id,
          MAPPER
              .getNodeFactory()
              .textNode(mode.equals("empty-decompile") ? "" : DECOMPILED_SOURCE));
      default -> sendError(out, id, -32601, "Method not found: " + method);
    }
    return true;
  }

  private void handleDefinition(JsonNode message, JsonNode id, OutputStream out)
      throws IOException {
    switch (mode) {
      case "slow-definition" -> {
        // 不回应：调用方必须在自己的有效超时内失败。
      }
      case "crash-on-definition" -> {
        System.err.println("fake-lsp: crashing during definition");
        System.err.flush();
        System.exit(9);
      }
      case "crash-noisy" -> {
        // 超过客户端诊断尾部上限的输出：错误信息必须仍然有界。
        System.err.println("fake-lsp: ".concat("x".repeat(NOISE_BYTES)));
        System.err.flush();
        System.exit(11);
      }
      case "error-definition" -> sendError(out, id, -32603, "boom");
      case "null-definition" -> sendResult(out, id, MAPPER.nullNode());
      case "weird-locations" -> sendResult(out, id, weirdLocations());
      case "link-definition" -> {
        String uri = message.path("params").path("textDocument").path("uri").asText("");
        ObjectNode link = MAPPER.createObjectNode();
        link.put("targetUri", uri);
        link.set("targetRange", range(DEFINITION_LINE, DEFINITION_CHARACTER, 3));
        link.set("targetSelectionRange", range(DEFINITION_LINE, DEFINITION_CHARACTER, 7));
        sendResult(out, id, MAPPER.createArrayNode().add(link));
      }
      default -> {
        String uri = message.path("params").path("textDocument").path("uri").asText("");
        ObjectNode location = MAPPER.createObjectNode();
        location.put("uri", uri);
        location.set("range", range(DEFINITION_LINE, DEFINITION_CHARACTER, 3));
        sendResult(out, id, MAPPER.createArrayNode().add(location));
      }
    }
  }

  private void sendClientRequests(OutputStream out) throws IOException {
    sendServerRequest(
        out,
        "window/showMessageRequest",
        MAPPER
            .createObjectNode()
            .put("type", 1)
            .put("message", "pick one")
            .set("actions", MAPPER.createArrayNode()));
    sendServerRequest(
        out,
        "workspace/applyEdit",
        MAPPER.createObjectNode().set("edit", MAPPER.createObjectNode()));
    ObjectNode showMessage = MAPPER.createObjectNode();
    showMessage.put("jsonrpc", "2.0");
    showMessage.put("method", "window/showMessage");
    showMessage.set("params", MAPPER.createObjectNode().put("type", 2).put("message", "hi"));
    write(out, showMessage);
    ObjectNode telemetry = MAPPER.createObjectNode();
    telemetry.put("jsonrpc", "2.0");
    telemetry.put("method", "telemetry/event");
    telemetry.set("params", MAPPER.createObjectNode().put("event", "run"));
    write(out, telemetry);
    ObjectNode diagnostics = MAPPER.createObjectNode();
    diagnostics.put("jsonrpc", "2.0");
    diagnostics.put("method", "textDocument/publishDiagnostics");
    diagnostics.set(
        "params",
        MAPPER
            .createObjectNode()
            .put("uri", "file:///tmp/alpha.java")
            .set("diagnostics", MAPPER.createArrayNode()));
    write(out, diagnostics);
  }

  private void sendServerRequests(OutputStream out) throws IOException {
    ObjectNode configuration = MAPPER.createObjectNode();
    configuration.set(
        "items", MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("section", "fake")));
    sendServerRequest(out, "workspace/configuration", configuration);
    sendServerRequest(
        out, "window/workDoneProgress/create", MAPPER.createObjectNode().put("token", "progress"));
    sendServerRequest(
        out,
        "client/registerCapability",
        MAPPER.createObjectNode().set("registrations", MAPPER.createArrayNode()));
    sendServerRequest(out, "workspace/workspaceFolders", MAPPER.createObjectNode());
    sendServerRequest(out, "custom/unknown", MAPPER.createObjectNode());
    ObjectNode notification = MAPPER.createObjectNode();
    notification.put("jsonrpc", "2.0");
    notification.put("method", "window/logMessage");
    notification.set("params", MAPPER.createObjectNode().put("type", 3).put("message", "hello"));
    write(out, notification);
  }

  private void sendServerRequest(OutputStream out, String method, ObjectNode params)
      throws IOException {
    ObjectNode request = MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", outboundId.getAndIncrement());
    request.put("method", method);
    request.set("params", params);
    write(out, request);
  }

  private JsonNode initializeResult() {
    ObjectNode result = MAPPER.createObjectNode();
    if (mode.equals("no-capabilities")) {
      return result;
    }
    ObjectNode capabilities = result.putObject("capabilities");
    capabilities.put(
        "positionEncoding",
        switch (mode) {
          case "utf8" -> "utf-8";
          case "utf32" -> "utf-32";
          default -> "utf-16";
        });
    if (!mode.equals("no-definition")) {
      capabilities.put("definitionProvider", true);
    }
    if (!mode.equals("no-symbols")) {
      capabilities.put("workspaceSymbolProvider", true);
    }
    ObjectNode sync = capabilities.putObject("textDocumentSync");
    sync.put("openClose", true);
    sync.put("change", 1);
    sync.put("save", true);
    result.putObject("serverInfo").put("name", "fake-lsp").put("version", "1");
    return result;
  }

  private JsonNode symbols() {
    List<JsonNode> symbols = new ArrayList<>();
    symbols.add(symbol("Alpha", 5, "file:///tmp/alpha.java", 0));
    symbols.add(symbol("Beta", 12, "file:///tmp/beta.java", 1));
    symbols.add(symbol("Gamma", 26, "file:///tmp/gamma.java", 2));
    return MAPPER.valueToTree(symbols);
  }

  /** 缺失 name/kind/location 的符号：格式化必须给出确定文案而不是空指针。 */
  private JsonNode oddSymbols() {
    List<JsonNode> symbols = new ArrayList<>();
    ObjectNode bare = MAPPER.createObjectNode();
    bare.putNull("name");
    bare.putNull("kind");
    bare.putNull("location");
    symbols.add(bare);
    return MAPPER.valueToTree(symbols);
  }

  /** Location 缺失 uri/range：格式化必须稳定（uri 缺失显示 unknown，range 缺失只给位置）。 */
  private JsonNode weirdLocations() {
    List<JsonNode> locations = new ArrayList<>();
    ObjectNode noRange = MAPPER.createObjectNode();
    noRange.put("uri", "jdt://contents/pkg/Thing.class");
    locations.add(noRange);
    ObjectNode noUri = MAPPER.createObjectNode();
    noUri.putNull("uri");
    locations.add(noUri);
    return MAPPER.valueToTree(locations);
  }

  /** 新版 {@code WorkspaceSymbol} 形态：location 是 {uri} 而不是 SymbolInformation 的完整 Location。 */
  private JsonNode workspaceSymbols() {
    List<JsonNode> symbols = new ArrayList<>();
    ObjectNode symbol = MAPPER.createObjectNode();
    symbol.put("name", "Delta");
    symbol.put("kind", 5);
    symbol.putObject("location").put("uri", "file:///tmp/delta.java");
    symbols.add(symbol);
    return MAPPER.valueToTree(symbols);
  }

  private ObjectNode symbol(String name, int kind, String uri, int line) {
    ObjectNode symbol = MAPPER.createObjectNode();
    symbol.put("name", name);
    symbol.put("kind", kind);
    ObjectNode location = symbol.putObject("location");
    location.put("uri", uri);
    location.set("range", range(line, 0, 1));
    return symbol;
  }

  private ObjectNode range(int line, int character, int endCharacter) {
    ObjectNode range = MAPPER.createObjectNode();
    range.set("start", position(line, character));
    range.set("end", position(line, endCharacter));
    return range;
  }

  private ObjectNode position(int line, int character) {
    ObjectNode position = MAPPER.createObjectNode();
    position.put("line", line);
    position.put("character", character);
    return position;
  }

  private void sendResult(OutputStream out, JsonNode id, JsonNode result) throws IOException {
    ObjectNode response = MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result);
    write(out, response);
  }

  private void sendError(OutputStream out, JsonNode id, int code, String message)
      throws IOException {
    ObjectNode response = MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.putObject("error").put("code", code).put("message", message);
    write(out, response);
  }

  private void write(OutputStream out, JsonNode message) throws IOException {
    byte[] payload = MAPPER.writeValueAsBytes(message);
    ObjectNode sent = MAPPER.createObjectNode();
    sent.put("event", "send");
    sent.set("message", message);
    record(sent);
    synchronized (out) {
      out.write(
          ("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(payload);
      out.flush();
    }
  }

  /** 读取一条消息的头部块；返回 {@code null} 表示输入已结束。 */
  private static String readHeaders(InputStream in) throws IOException {
    StringBuilder headers = new StringBuilder();
    int current;
    while ((current = in.read()) >= 0) {
      headers.append((char) current);
      String text = headers.toString();
      if (text.endsWith("\r\n\r\n") || text.endsWith("\n\n")) {
        return text;
      }
    }
    return headers.isEmpty() ? null : headers.toString();
  }

  private static int contentLength(String headers) {
    for (String line : headers.split("\r?\n")) {
      int separator = line.indexOf(':');
      if (separator > 0 && line.substring(0, separator).trim().equalsIgnoreCase("Content-Length")) {
        try {
          return Integer.parseInt(line.substring(separator + 1).trim());
        } catch (NumberFormatException error) {
          return -1;
        }
      }
    }
    return -1;
  }

  private void record(ObjectNode event) {
    try {
      Files.writeString(
          transcript,
          MAPPER.writeValueAsString(event) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // 诊断记录失败不影响协议行为。
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }
}
