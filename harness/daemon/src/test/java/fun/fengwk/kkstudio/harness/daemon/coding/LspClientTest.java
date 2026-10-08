package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link LspClient} 与真实 stdio 假服务器之间的协议契约：分帧、初始化参数、文档同步、位置编码、请求/通知、崩溃、超时与关闭。
 *
 * <p>所有断言都读自假服务器写下的真实收发记录，因此“协议通过”不依赖任何 mock。
 */
class LspClientTest {

  private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ExecutorService dispatch = Executors.newCachedThreadPool();
  private final List<LspClient> clients = new ArrayList<>();
  private Path transcript;

  @AfterEach
  void tearDown() {
    clients.forEach(client -> client.stop(Duration.ofMillis(200)));
    dispatch.shutdownNow();
  }

  /** 意图：初始化声明真实的协议事实（rootUri、workspaceFolders、位置编码、同步与 jdtls 能力），并在收到结果后发出 initialized。 */
  @Test
  void initializeDeclaresProtocolFactsAndRoot() throws Exception {
    LspClient client = start("normal");
    assertTrue(client.isAlive());

    JsonNode params = FakeLspServers.received(transcript, "initialize").getFirst().path("params");
    assertEquals(root.toUri().toString(), params.path("rootUri").asText());
    assertEquals(root.toString(), params.path("rootPath").asText());
    assertTrue(params.path("processId").asLong() > 0, params.toString());
    assertEquals(
        List.of("utf-16", "utf-8"),
        texts(params.path("capabilities").path("general").path("positionEncodings")));
    assertTrue(params.path("capabilities").path("workspace").path("workspaceFolders").asBoolean());
    assertFalse(params.path("capabilities").path("workspace").path("applyEdit").asBoolean());
    assertTrue(
        params
            .path("capabilities")
            .path("textDocument")
            .path("synchronization")
            .path("didSave")
            .asBoolean());
    assertTrue(
        params
            .path("capabilities")
            .path("textDocument")
            .path("definition")
            .path("linkSupport")
            .asBoolean());
    assertEquals("workspace", params.path("workspaceFolders").get(0).path("name").asText());
    assertEquals(
        root.toUri().toString(), params.path("workspaceFolders").get(0).path("uri").asText());
    // initialized 是 fire-and-forget 通知：等它真的到达服务器再断言，避免与发送时序竞争。
    FakeLspServers.await(transcript, event -> hasMethod(event, "initialized"));
    assertEquals(1, FakeLspServers.received(transcript, "initialized").size());
  }

  /** 意图：查询前打开发送完整文档，definition 结果按“路径:行:列”格式化，非本地 URI 原样保留。 */
  @Test
  void definitionSyncsDocumentAndFormatsLocations() throws Exception {
    LspClient client = start("normal");
    Path file = write("App.java", "class App {\n  // first\n  void run() {}\n}\n");

    List<String> locations = client.definition(file, 3, 4, REQUEST_TIMEOUT);

    assertEquals(List.of(file + ":10:5"), locations);
    JsonNode opened =
        FakeLspServers.received(transcript, "textDocument/didOpen").getFirst().path("params");
    assertEquals(file.toUri().toString(), opened.path("textDocument").path("uri").asText());
    assertEquals("java", opened.path("textDocument").path("languageId").asText());
    assertEquals(1, opened.path("textDocument").path("version").asInt());
    assertEquals(Files.readString(file), opened.path("textDocument").path("text").asText());
    JsonNode request = FakeLspServers.received(transcript, "textDocument/definition").getFirst();
    assertEquals(2, request.path("params").path("position").path("line").asInt());
    assertEquals(4, request.path("params").path("position").path("character").asInt());
  }

  /** 意图：列参数是码点偏移，按协商到的 utf-8 位置编码换算；astral 字符占 4 个 utf-8 单元而不是 2 个 utf-16 单元。 */
  @Test
  void utf8PositionEncodingConvertsCodePointOffsets() throws Exception {
    LspClient client = start("utf8");
    Path file = write("Emoji.java", "\"😀x\";\n");

    // 第 2 个码点位于 4 字节的 astral 字符之后：utf-16 下是 3，utf-8 下是 5。
    client.definition(file, 1, 2, REQUEST_TIMEOUT);

    JsonNode position =
        FakeLspServers.received(transcript, "textDocument/definition")
            .getFirst()
            .path("params")
            .path("position");
    assertEquals(0, position.path("line").asInt());
    assertEquals(5, position.path("character").asInt(), position.toString());
  }

  /** 意图：didOpen 的 languageId 由扩展名决定，未映射的扩展名回退服务器 id，未知扩展名同样回退。 */
  @Test
  void languageIdFollowsExtensionMapping() throws Exception {
    LspClient client = start("normal");
    List<Path> files =
        List.of(
            write("a.mts", "export const a = 1;\n"),
            write("b.cts", "export const b = 1;\n"),
            write("c.mjs", "export const c = 1;\n"),
            write("d.cjs", "export const d = 1;\n"),
            write("e.pyi", "x: int\n"),
            write("f.kt", "val f = 1\n"));
    for (Path file : files) {
      client.definition(file, 1, 0, REQUEST_TIMEOUT);
    }

    List<String> languageIds =
        FakeLspServers.received(transcript, "textDocument/didOpen").stream()
            .map(node -> node.path("params").path("textDocument").path("languageId").asText())
            .toList();
    assertEquals(
        List.of("typescript", "typescript", "javascript", "javascript", "python", "fake"),
        languageIds);
  }

  /** 意图：带 BOM 的源码在 didOpen 前解码，发送的是去掉 BOM 的正文；随后按内容变化发送 didChange。 */
  @Test
  void bomMarkedSourceIsDecodedBeforeOpenAndChange() throws Exception {
    LspClient client = start("normal");
    Path file = write("Bom.java", "");
    Files.write(file, "\ufeffclass Bom {}\n".getBytes(StandardCharsets.UTF_8));

    client.definition(file, 1, 0, REQUEST_TIMEOUT);
    assertEquals(
        "class Bom {}\n",
        FakeLspServers.received(transcript, "textDocument/didOpen")
            .getFirst()
            .path("params")
            .path("textDocument")
            .path("text")
            .asText());

    Files.write(file, "\ufeffclass Bom { void run() {} }\n".getBytes(StandardCharsets.UTF_8));
    client.definition(file, 1, 0, REQUEST_TIMEOUT);
    assertEquals(
        "class Bom { void run() {} }\n",
        FakeLspServers.received(transcript, "textDocument/didChange")
            .getLast()
            .path("params")
            .path("contentChanges")
            .get(0)
            .path("text")
            .asText());
  }

  /** 意图：文件被外部修改后重新查询会发送全量 didChange 与 didSave，且不重复 didOpen。 */
  @Test
  void didChangeAndDidSaveAfterExternalWrite() throws Exception {
    LspClient client = start("normal");
    Path file = write("App.java", "class App {}\n");
    client.definition(file, 1, 0, REQUEST_TIMEOUT);

    Files.writeString(file, "class App { void run() {} }\n");
    client.definition(file, 1, 0, REQUEST_TIMEOUT);

    assertEquals(1, FakeLspServers.received(transcript, "textDocument/didOpen").size());
    JsonNode changed =
        FakeLspServers.received(transcript, "textDocument/didChange").getFirst().path("params");
    assertEquals(2, changed.path("textDocument").path("version").asInt());
    assertEquals(
        "class App { void run() {} }\n",
        changed.path("contentChanges").get(0).path("text").asText());
    assertEquals(1, FakeLspServers.received(transcript, "textDocument/didSave").size());
  }

  /** 意图：越过文档末尾的行与越过目标行长度的列都在发请求前明确报错。 */
  @Test
  void rejectsLineAndColumnBeyondTheDocument() throws Exception {
    LspClient client = start("normal");
    Path file = write("App.java", "class App {}\n");

    IllegalArgumentException beyondLine =
        assertThrows(
            IllegalArgumentException.class, () -> client.definition(file, 99, 0, REQUEST_TIMEOUT));
    assertTrue(beyondLine.getMessage().contains("beyond the end"), beyondLine.getMessage());

    IllegalArgumentException beyondColumn =
        assertThrows(
            IllegalArgumentException.class, () -> client.definition(file, 1, 99, REQUEST_TIMEOUT));
    assertTrue(beyondColumn.getMessage().contains("beyond line"), beyondColumn.getMessage());
    assertTrue(FakeLspServers.received(transcript, "textDocument/definition").isEmpty());
  }

  /** 意图：服务器未声明能力时不发送请求，直接给出可理解的错误。 */
  @Test
  void unsupportedDefinitionIsReportedWithoutRequest() throws Exception {
    LspClient client = start("no-definition");
    Path file = write("App.java", "class App {}\n");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> client.definition(file, 1, 0, REQUEST_TIMEOUT));
    assertTrue(
        error.getMessage().contains("does not advertise go-to-definition"), error.getMessage());
    assertTrue(FakeLspServers.received(transcript, "textDocument/definition").isEmpty());
    assertEquals(
        "Alpha (Class) - file:///tmp/alpha.java",
        client.workspaceSymbols("A", 1, REQUEST_TIMEOUT).getFirst());
  }

  /** 意图：workspace symbol 结果带符号类型与位置，limit 同时约束条目数与空结果文案。 */
  @Test
  void workspaceSymbolsFormatsKindsAndHonoursLimit() throws Exception {
    LspClient client = start("normal");

    assertEquals(
        List.of(
            "Alpha (Class) - file:///tmp/alpha.java", "Beta (Function) - file:///tmp/beta.java"),
        client.workspaceSymbols("A", 2, REQUEST_TIMEOUT));
    assertEquals(List.of("No symbols found"), client.workspaceSymbols("A", 0, REQUEST_TIMEOUT));
    List<String> all = client.workspaceSymbols("A", 10, REQUEST_TIMEOUT);
    assertEquals(3, all.size(), all.toString());
    assertEquals("Gamma (TypeParameter) - file:///tmp/gamma.java", all.get(2));
    assertEquals(
        List.of("Alpha (Class) - file:///tmp/alpha.java"),
        client.workspaceSymbols("A", 1, REQUEST_TIMEOUT));
  }

  /** 意图：jdt:// 目标走 jdtls 的 classFileContents，并在初始化时声明 classFileContents 支持。 */
  @Test
  void javaDecompileUsesJdtClassFileContentsForJdtUris() throws Exception {
    assumeFalse(isWindows());
    Path childPid = root.resolve("child.pid");
    LspServerConfig config = FakeLspServers.jdtlsServer(root, "normal", transcript(), childPid);
    LspClient client = start(config);

    String source =
        client.javaDecompile(
            "String (Class) - jdt://contents/java.base/java/lang/String.class", REQUEST_TIMEOUT);

    assertEquals(FakeLspServer.DECOMPILED_SOURCE, source);
    JsonNode request = FakeLspServers.received(transcript, "java/classFileContents").getFirst();
    assertEquals(
        "jdt://contents/java.base/java/lang/String.class",
        request.path("params").path("uri").asText());
    assertTrue(
        FakeLspServers.received(transcript, "initialize")
            .getFirst()
            .path("params")
            .path("initializationOptions")
            .path("extendedClientCapabilities")
            .path("classFileContentsSupport")
            .asBoolean());
  }

  /** 意图：非 jdtls 服务器明确拒绝 jdt:// 目标，而不是退回字节码反汇编。 */
  @Test
  void javaDecompileRejectsJdtUriOnNonJdtlsServer() throws Exception {
    LspClient client = start("normal");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                client.javaDecompile(
                    "jdt://contents/java.base/java/lang/String.class", REQUEST_TIMEOUT));
    assertTrue(error.getMessage().contains("only supported by jdtls"), error.getMessage());
  }

  /** 意图：绝对 class 目标转换为 file URI，并通过 jdtls 的 java.decompile 命令请求。 */
  @Test
  void javaDecompileLocalClassUsesExecuteCommand() throws Exception {
    LspClient client = start("normal");
    Path classFile = Files.createDirectories(root.resolve("build")).resolve("Foo.class");
    Files.write(classFile, new byte[] {0x1});

    String source = client.javaDecompile(classFile.toString(), REQUEST_TIMEOUT);

    assertEquals(FakeLspServer.DECOMPILED_SOURCE, source);
    JsonNode request =
        FakeLspServers.received(transcript, "workspace/executeCommand").getFirst().path("params");
    assertEquals("java.decompile", request.path("command").asText());
    assertEquals(classFile.toUri().toString(), request.path("arguments").get(0).asText());
  }

  /** 意图：服务端发出的请求都被应答：能力协商类返回可用结果、未知方法回 MethodNotFound。 */
  @Test
  void answersServerRequestsAndUnknownMethods() throws Exception {
    start("server-requests");

    JsonNode configuration = awaitResponse(900);
    assertTrue(configuration.path("result").isArray(), configuration.toString());
    assertEquals(1, configuration.path("result").size());
    assertTrue(awaitResponse(901).path("result").isNull());
    assertTrue(awaitResponse(902).path("result").isNull());
    JsonNode workspaceFolders = awaitResponse(903);
    assertEquals(
        root.toUri().toString(),
        workspaceFolders.path("result").get(0).path("uri").asText(),
        "workspace/workspaceFolders 必须回答当前项目根");
    JsonNode unknown = awaitResponse(904);
    assertEquals(-32601, unknown.path("error").path("code").asInt(), unknown.toString());
  }

  /** 意图：协议消息之前的非 LSP 输出被容忍，后续真实帧照常解析。 */
  @Test
  void toleratesStrayOutputBeforeTheFirstMessage() throws Exception {
    LspClient client = start("garbage");
    Path file = write("App.java", "class App {}\n");

    assertEquals(List.of(file + ":10:5"), client.definition(file, 1, 0, REQUEST_TIMEOUT));
  }

  /** 意图：请求进行中服务器崩溃时请求明确失败，实例被标记为不可复用。 */
  @Test
  void crashDuringRequestFailsClearlyAndMarksTheClientDead() throws Exception {
    LspClient client = start("crash-on-definition");
    Path file = write("App.java", "class App {}\n");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> client.definition(file, 1, 0, REQUEST_TIMEOUT));
    assertNotNull(error.getMessage());
    assertFalse(client.isAlive(), "崩溃后实例不得被复用");
  }

  /** 意图：请求超过调用方有效超时后失败，并按协议发送 $/cancelRequest，同时不关闭共享客户端。 */
  @Test
  void slowRequestIsCancelledAtTheCallerDeadline() throws Exception {
    LspClient client = start("slow-definition");
    Path file = write("App.java", "class App {}\n");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> client.definition(file, 1, 0, Duration.ofMillis(300)));

    assertTrue(error.getMessage().contains("timed out"), error.getMessage());
    FakeLspServers.await(transcript, event -> hasMethod(event, "$/cancelRequest"));
    assertTrue(client.isAlive(), "取消本次请求不得关闭共享客户端");
  }

  /** 意图：正常服务器走 shutdown 请求、exit 通知并在宽限内自行退出。 */
  @Test
  void gracefulShutdownUsesShutdownAndExit() throws Exception {
    LspClient client = start("normal");
    long pid = FakeLspServers.startedPid(transcript);

    client.stop(Duration.ofSeconds(5));

    assertEquals(1, FakeLspServers.received(transcript, "shutdown").size());
    assertEquals(1, FakeLspServers.received(transcript, "exit").size());
    assertFalse(client.isAlive());
    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    assertThrows(
        IllegalStateException.class,
        () -> client.definition(write("Other.java", "class Other {}\n"), 1, 0, REQUEST_TIMEOUT));
  }

  /** 意图：忽视关闭的服务器在宽限后被强制终止，其派生的后代进程也一并收敛。 */
  @Test
  void stubbornServerIsForceKilledWithItsDescendants() throws Exception {
    assumeFalse(isWindows());
    Path childPid = root.resolve("child.pid");
    LspServerConfig config = FakeLspServers.jdtlsServer(root, "stubborn", transcript(), childPid);
    LspClient client = start(config);
    long pid = FakeLspServers.startedPid(transcript);
    long child = Long.parseLong(Files.readString(childPid).trim());

    long started = System.nanoTime();
    client.stop(Duration.ofMillis(200));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    assertFalse(client.isAlive());
    FakeLspServers.awaitProcessGone(pid, Duration.ofSeconds(10));
    FakeLspServers.awaitProcessGone(child, Duration.ofSeconds(10));
    assertTrue(elapsedMillis >= 200, "必须先给出关闭宽限，实际 " + elapsedMillis + "ms");
  }

  /**
   * 意图：服务器在初始化前退出时，它留下的、忽略温和信号的子进程也必须一起消失。
   *
   * <p>这是「执行范围拥有整组」在 LSP 生命周期上的直接事实：启动失败只收掉服务器自己是不够的，否则每一次启动失败都会在后台留下一个 拒绝退出的进程，而这个进程与本次连接已经没有任何关系。
   */
  @Test
  void launchFailureReapsTheServersStubbornChildren() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 信号语义：子进程忽略 TERM，只能由强杀阶段收敛");
    Path childPid = root.resolve("stubborn-child.pid");
    LspServerConfig config =
        FakeLspServers.deadServerWithStubbornChild("died-with-child", 7, childPid);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> LspClient.launch(config, root, config.command().getFirst(), dispatch));
    assertTrue(error.getMessage().contains("exited before initialization"), error.getMessage());
    assertTrue(error.getMessage().contains("exit code 7"), error.getMessage());

    long child = Long.parseLong(Files.readString(childPid).trim());
    FakeLspServers.awaitProcessGone(child, Duration.ofSeconds(20));
  }

  /** 意图：二进制文件在发送 didOpen 之前就被拒绝，不把字节当作文本发给服务器。 */
  @Test
  void binaryDocumentIsRejected() throws Exception {
    LspClient client = start("normal");
    Path binary = root.resolve("App.java");
    Files.write(binary, new byte[] {'c', 0, 'x'});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> client.definition(binary, 1, 0, REQUEST_TIMEOUT));
    assertTrue(error.getMessage().contains("cannot open binary file"), error.getMessage());
    assertTrue(FakeLspServers.received(transcript, "textDocument/didOpen").isEmpty());
  }

  private LspClient start(String mode) throws Exception {
    return start(FakeLspServers.javaServer("fake", mode, root, transcript()));
  }

  private LspClient start(LspServerConfig config) throws Exception {
    LspClient client = LspClient.launch(config, root, config.command().getFirst(), dispatch);
    clients.add(client);
    client.initialize(INITIALIZE_TIMEOUT);
    return client;
  }

  private Path transcript() {
    if (transcript == null) {
      transcript = FakeLspServers.transcript(root);
    }
    return transcript;
  }

  private JsonNode awaitResponse(long id) {
    return FakeLspServers.await(
            transcript,
            event -> hasMethod(event, null) && event.path("message").path("id").asLong() == id)
        .path("message");
  }

  private static boolean hasMethod(JsonNode event, String method) {
    if (!event.path("event").asText().equals("recv")) {
      return false;
    }
    JsonNode message = event.path("message");
    if (method == null) {
      return message.path("method").isMissingNode() && message.has("id");
    }
    return method.equals(message.path("method").asText());
  }

  private Path write(String name, String content) throws IOException {
    return Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(element -> values.add(element.asText()));
    return values;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
