package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class CodingToolsTest {

  @TempDir Path environmentRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void closeExecutors() {
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  @Test
  void registersEnvironmentDescriptorsAndRejectsMalformedArguments() {
    CodingToolsConfig config = config();
    DaemonToolRegistry registry = new DaemonToolRegistry();
    CodingTools.registerAll(registry, config, executor, scheduler);

    assertEquals(
        List.of(
            "read",
            "write",
            "edit",
            "apply_patch",
            "bash",
            "grep",
            "find",
            "lsp_goto_definition",
            "lsp_workspace_symbols",
            "lsp_java_decompile"),
        registry.descriptors().stream().map(d -> d.name()).toList());
    Tool read = registry.find("read").orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> request(read, "{\"path\":1}"));
    assertThrows(
        IllegalArgumentException.class, () -> request(read, "{\"path\":\"x\",\"unknown\":true}"));
  }

  @Test
  void rejectsTraversalSymlinksAndUnsafeWriteAncestors() throws Exception {
    Path outside = Files.createTempDirectory("coding-outside");
    Files.writeString(outside.resolve("secret.txt"), "secret");
    Files.createSymbolicLink(environmentRoot.resolve("escape"), outside);
    ReadTool read = read(config());
    WriteTool write = write(config());

    ToolResult traversal = invoke(read, "{\"path\":\"../secret.txt\"}");
    ToolResult symlink = invoke(read, "{\"path\":\"escape/secret.txt\"}");
    ToolResult unsafeWrite = invoke(write, "{\"path\":\"escape/new.txt\",\"content\":\"x\"}");

    assertTrue(text(traversal).contains("escapes environment root"));
    assertTrue(text(symlink).contains("outside environment root"));
    assertTrue(text(unsafeWrite).contains("outside environment root"));
    assertFalse(Files.exists(outside.resolve("new.txt")));
  }

  @Test
  void writeAndEditPreserveBomNewlinesAndSerializeExactReplacement() throws Exception {
    Path file = environmentRoot.resolve("sample.txt");
    Files.write(
        file, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\r', '\n', 'a', '\r', '\n'});
    EditTool edit = edit(config());

    ToolResult duplicate =
        invoke(edit, "{\"path\":\"sample.txt\",\"old_string\":\"a\\n\",\"new_string\":\"b\\n\"}");
    ToolResult replaced =
        invoke(
            edit,
            "{\"path\":\"sample.txt\",\"old_string\":\"a\\n"
                + "\",\"new_string\":\"b\\n"
                + "\",\"replace_all\":true}");

    assertTrue(text(duplicate).contains("Found 2 exact matches"));
    assertFalse(replaced.error());
    byte[] bytes = Files.readAllBytes(file);
    assertEquals((byte) 0xef, bytes[0]);
    assertEquals("b\r\nb\r\n", new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
  }

  @Test
  void readReportsLspBridgeConfigurationStatus() throws Exception {
    Files.writeString(environmentRoot.resolve("lsp-status.txt"), "x\n");
    CodingToolsConfig bridged =
        new CodingToolsConfig(
            environmentRoot, 2000, 50 * 1024, "bash", new InMemoryResourceStore(), "echo", "javap");

    ToolResult disabled = invoke(read(config()), "{\"path\":\"lsp-status.txt\"}");
    ToolResult enabled = invoke(read(bridged), "{\"path\":\"lsp-status.txt\"}");

    assertTrue(text(disabled).contains("lsp: unsupported"));
    assertTrue(text(enabled).contains("lsp: supported"));
  }

  @Test
  void editRejectsMultipleOrOverlappingOccurrences() throws Exception {
    Path overlap = environmentRoot.resolve("overlap.txt");
    Files.writeString(overlap, "aaa\n");
    Path replaced = environmentRoot.resolve("replace-all.txt");
    Files.writeString(replaced, "abab\n");
    EditTool edit = edit(config());

    ToolResult overlapping =
        invoke(edit, "{\"path\":\"overlap.txt\",\"old_string\":\"aa\",\"new_string\":\"b\"}");
    ToolResult overlappingAll =
        invoke(
            edit,
            "{\"path\":\"overlap.txt\",\"old_string\":\"aa\",\"new_string\":\"b\",\"replace_all\":true}");
    ToolResult replacedResult =
        invoke(
            edit,
            "{\"path\":\"replace-all.txt\",\"old_string\":\"ab\",\"new_string\":\"x\",\"replace_all\":true}");

    assertTrue(text(overlapping).contains("Found 2 exact matches"));
    assertTrue(text(overlappingAll).contains("overlapping exact matches"));
    assertFalse(replacedResult.error());
    assertArrayEquals("aaa\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(overlap));
    assertEquals("xx\n", Files.readString(replaced));
  }

  @Test
  void editRejectsNoOpEditsThatWouldLeaveBytesUnchanged() throws Exception {
    Path crlf = environmentRoot.resolve("crlf-noop.txt");
    Path lf = environmentRoot.resolve("lf-noop.txt");
    byte[] originalCrlf = "alpha\r\n".getBytes(StandardCharsets.UTF_8);
    byte[] originalLf = "alpha\n".getBytes(StandardCharsets.UTF_8);
    Files.write(crlf, originalCrlf);
    Files.write(lf, originalLf);
    EditTool edit = edit(config());

    ToolResult crlfSpelled =
        invoke(
            edit,
            "{\"path\":\"crlf-noop.txt\",\"old_string\":\"alpha\\r\\n\",\"new_string\":\"alpha\\n\"}");
    ToolResult lfSpelled =
        invoke(
            edit,
            "{\"path\":\"lf-noop.txt\",\"old_string\":\"alpha\\n\",\"new_string\":\"alpha\\r\\n\"}");

    assertTrue(text(crlfSpelled).contains("No changes to apply"));
    assertTrue(text(crlfSpelled).contains("must differ"));
    assertTrue(text(lfSpelled).contains("No changes to apply"));
    assertArrayEquals(originalCrlf, Files.readAllBytes(crlf));
    assertArrayEquals(originalLf, Files.readAllBytes(lf));
  }

  @Test
  void editPreservesCrOnlyAndMixedLineEndingsExactly() throws Exception {
    // CR-only 文件：跨行 old_string 在 LF 归一空间匹配，未修改区域与替换换行沿用既有 CR。
    Path cr = environmentRoot.resolve("cr-only.txt");
    byte[] originalCr = "alpha\rbeta\rgamma\r".getBytes(StandardCharsets.UTF_8);
    Files.write(cr, originalCr);
    // mixed 文件：CRLF 与 CR 并存；替换跨行段落时未修改区域原样保留，新增换行沿用被替换段行尾。
    Path mixed = environmentRoot.resolve("mixed.txt");
    byte[] originalMixed = "alpha\r\nbeta\rgamma".getBytes(StandardCharsets.UTF_8);
    Files.write(mixed, originalMixed);
    EditTool edit = edit(config());

    ToolResult crResult =
        invoke(
            edit,
            "{\"path\":\"cr-only.txt\",\"old_string\":\"alpha\\nbeta\",\"new_string\":\"left\\nright\"}");
    ToolResult mixedResult =
        invoke(
            edit,
            "{\"path\":\"mixed.txt\",\"old_string\":\"alpha\\nbeta\",\"new_string\":\"left\\nright\"}");

    // mixed 文件中被替换段内部无行尾（歧义）时，新增换行回退 LF，未修改区域仍原样保留。
    Path ambiguous = environmentRoot.resolve("mixed-ambiguous.txt");
    Files.write(ambiguous, "head\r\nmiddle\rtail".getBytes(StandardCharsets.UTF_8));
    ToolResult ambiguousResult =
        invoke(
            edit,
            "{\"path\":\"mixed-ambiguous.txt\",\"old_string\":\"middle\",\"new_string\":\"left\\nright\"}");

    assertFalse(crResult.error());
    assertFalse(mixedResult.error());
    assertFalse(ambiguousResult.error());
    assertArrayEquals(
        "left\rright\rgamma\r".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(cr));
    assertArrayEquals(
        "left\r\nright\rgamma".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(mixed));
    assertArrayEquals(
        "head\r\nleft\nright\rtail".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(ambiguous));
  }

  @Test
  void readReturnsDirectoryWindowAndBinaryResource() throws Exception {
    Files.writeString(environmentRoot.resolve("many.txt"), "one\ntwo\nthree\n");
    Files.write(environmentRoot.resolve("binary.bin"), new byte[] {1, 0, 2});
    Files.createDirectory(environmentRoot.resolve("directory"));
    Files.writeString(environmentRoot.resolve("directory/a.txt"), "a");
    ReadTool read = read(config());
    ReadTool constrainedRead = read(config(1, 16));

    ToolResult window = invoke(read, "{\"path\":\"many.txt\",\"limit\":1}");
    ToolResult directory = invoke(read, "{\"path\":\"directory\"}");
    ToolResult binary = invoke(constrainedRead, "{\"path\":\"binary.bin\"}");

    assertTrue(text(window).contains("Showing lines 1-1 of 3"));
    assertTrue(directory.contents().stream().anyMatch(content -> text(content).contains("a.txt")));
    assertTrue(binary.contents().stream().anyMatch(ResourceToolContent.class::isInstance));
  }

  @Test
  void serializesConcurrentMutationsOfTheSameFile() throws Exception {
    WriteTool write = write(config());

    RecordingListener first =
        invokeAsync(write, "{\"path\":\"shared.txt\",\"content\":\"first\"}", Duration.ZERO);
    RecordingListener second =
        invokeAsync(write, "{\"path\":\"shared.txt\",\"content\":\"second\"}", Duration.ZERO);

    assertTrue(first.await());
    assertTrue(second.await());
    String content = Files.readString(environmentRoot.resolve("shared.txt"));
    assertTrue(content.equals("first") || content.equals("second"));
    assertFalse(first.result.error());
    assertFalse(second.result.error());
  }

  @Test
  void grepAndFindRespectLimitsAndGitignore() throws Exception {
    Files.writeString(environmentRoot.resolve("visible.txt"), "needle\nneedle\n");
    Files.writeString(environmentRoot.resolve(".gitignore"), "ignored.txt\n");
    Files.writeString(environmentRoot.resolve("ignored.txt"), "needle\n");
    GrepTool grep = grep(config());
    FindTool find = find(config());

    ToolResult grepResult = invoke(grep, "{\"pattern\":\"needle\",\"path\":\".\",\"limit\":1}");
    ToolResult findResult = invoke(find, "{\"pattern\":\"*.txt\",\"path\":\".\",\"limit\":10}");

    assertTrue(text(grepResult).contains("results limit reached"));
    assertTrue(text(findResult).contains("visible.txt"));
    assertFalse(text(findResult).contains("ignored.txt"));
  }

  @Test
  void bashStreamsAndReportsTimeoutAndIdempotentCancellation() throws Exception {
    BashTool bash = bash(config());
    RecordingListener streaming =
        invokeAsync(
            bash,
            "{\"command\":\"printf first; sleep 0.05; printf second\"}",
            Duration.ofSeconds(2));
    assertTrue(streaming.await());
    assertTrue(streaming.partials.size() >= 1);
    assertTrue(text(streaming.result).contains("firstsecond"));

    RecordingListener ansi =
        invokeAsync(
            bash,
            "{\"command\":\"printf '\\\\033[31mpassed\\\\033[0m\\\\n'\"}",
            Duration.ofSeconds(2));
    assertTrue(ansi.await());
    assertTrue(text(ansi.result).contains("passed"));
    assertFalse(ansi.result.contents().stream().anyMatch(ResourceToolContent.class::isInstance));

    RecordingListener timeout =
        invokeAsync(bash, "{\"command\":\"sleep 2\"}", Duration.ofMillis(50));
    assertTrue(timeout.await());
    assertTrue(text(timeout.result).contains("Command timed out"));

    RecordingListener cancelled =
        invokeAsync(bash, "{\"command\":\"sleep 2\"}", Duration.ofSeconds(2));
    cancelled.handle.cancel();
    cancelled.handle.cancel();
    assertTrue(cancelled.await());
    assertTrue(text(cancelled.result).contains("Operation cancelled"));
  }

  @Test
  void bashTimeoutParameterDefaultsAndNeverExceedsInvocationDeadline() throws Exception {
    var missing = AbstractCodingTool.OBJECT_MAPPER.readTree("{\"command\":\"true\"}");
    var explicit =
        AbstractCodingTool.OBJECT_MAPPER.readTree("{\"command\":\"true\",\"timeout_seconds\":7}");
    assertEquals(120, BashTool.requestedTimeoutSeconds(missing));
    assertEquals(7, BashTool.requestedTimeoutSeconds(explicit));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BashTool.requestedTimeoutSeconds(
                AbstractCodingTool.OBJECT_MAPPER.readTree("{\"timeout_seconds\":0}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BashTool.requestedTimeoutSeconds(
                AbstractCodingTool.OBJECT_MAPPER.readTree("{\"timeout_seconds\":3601}")));

    assertEquals(
        Duration.ofSeconds(120),
        BashTool.effectiveProcessTimeout(Duration.ofHours(1), Duration.ofSeconds(120)));
    assertEquals(
        Duration.ofSeconds(2),
        BashTool.effectiveProcessTimeout(Duration.ofSeconds(2), Duration.ofSeconds(120)));
    assertEquals(
        Duration.ofSeconds(7),
        BashTool.effectiveProcessTimeout(Duration.ofSeconds(30), Duration.ofSeconds(7)));

    // 显式的短 timeout 必须在外部 request deadline 到来之前终止命令。
    RecordingListener timed =
        invokeAsync(
            bash(config()),
            "{\"command\":\"sleep 2\",\"timeout_seconds\":1}",
            Duration.ofSeconds(5));
    assertTrue(timed.await());
    assertTrue(text(timed.result).contains("Command timed out"));
  }

  /** grep 保持 15 秒默认值；find 只受调用 deadline 或显式 timeout 约束。 */
  @Test
  void searchTimeoutsMatchPiDefaultsAndRespectInvocationDeadlines() throws Exception {
    var absent = AbstractCodingTool.OBJECT_MAPPER.readTree("{}");
    var explicit = AbstractCodingTool.OBJECT_MAPPER.readTree("{\"timeout_seconds\":7}");

    assertEquals(Duration.ofHours(1), grep(config()).descriptor().timeout());
    assertEquals(Duration.ofHours(1), find(config()).descriptor().timeout());
    assertEquals(15, GrepTool.requestedTimeoutSeconds(absent));
    assertEquals(7, GrepTool.requestedTimeoutSeconds(explicit));
    assertEquals(
        Duration.ofSeconds(15),
        GrepTool.effectiveSearchTimeout(
            Duration.ofHours(1), GrepTool.requestedTimeoutSeconds(absent)));
    assertEquals(
        Duration.ofSeconds(2),
        GrepTool.effectiveSearchTimeout(
            Duration.ofSeconds(2), GrepTool.requestedTimeoutSeconds(absent)));
    assertEquals(
        Duration.ofSeconds(7),
        GrepTool.effectiveSearchTimeout(
            Duration.ofSeconds(30), GrepTool.requestedTimeoutSeconds(explicit)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GrepTool.requestedTimeoutSeconds(
                AbstractCodingTool.OBJECT_MAPPER.readTree("{\"timeout_seconds\":3601}")));

    assertEquals(Duration.ofHours(1), FindTool.effectiveSearchTimeout(Duration.ZERO, absent));
    assertEquals(
        Duration.ofMinutes(2), FindTool.effectiveSearchTimeout(Duration.ofMinutes(2), absent));
    assertEquals(
        Duration.ofSeconds(7), FindTool.effectiveSearchTimeout(Duration.ofMinutes(2), explicit));
    assertEquals(
        Duration.ofSeconds(2), FindTool.effectiveSearchTimeout(Duration.ofSeconds(2), explicit));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FindTool.effectiveSearchTimeout(
                Duration.ofMinutes(2),
                AbstractCodingTool.OBJECT_MAPPER.readTree("{\"timeout_seconds\":0}")));
  }

  private CodingToolsConfig config() {
    return config(2000, 50 * 1024);
  }

  private ReadTool read(CodingToolsConfig config) {
    return new ReadTool(config, executor);
  }

  private WriteTool write(CodingToolsConfig config) {
    return new WriteTool(config, executor);
  }

  private EditTool edit(CodingToolsConfig config) {
    return new EditTool(config, executor);
  }

  private GrepTool grep(CodingToolsConfig config) {
    return new GrepTool(config, executor);
  }

  private FindTool find(CodingToolsConfig config) {
    return new FindTool(config, executor);
  }

  private BashTool bash(CodingToolsConfig config) {
    return new BashTool(config, executor, scheduler);
  }

  private CodingToolsConfig config(int lines, int bytes) {
    return new CodingToolsConfig(
        environmentRoot, lines, bytes, "bash", new InMemoryResourceStore());
  }

  private ToolExecutionRequest request(Tool tool, String arguments) {
    return new ToolExecutionRequest(
        tool.descriptor(),
        new ToolCall("call", tool.descriptor().name(), arguments),
        Duration.ZERO,
        null,
        environmentRoot);
  }

  private ToolResult invoke(Tool tool, String arguments) throws Exception {
    RecordingListener listener = invokeAsync(tool, arguments, Duration.ZERO);
    assertTrue(listener.await());
    assertNotNull(listener.result);
    return listener.result;
  }

  private RecordingListener invokeAsync(Tool tool, String arguments, Duration timeout) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall("call", tool.descriptor().name(), arguments),
                timeout,
                null,
                environmentRoot),
            listener);
    return listener;
  }

  private static String text(ToolResult result) {
    return result.contents().stream().map(CodingToolsTest::text).reduce("", String::concat);
  }

  private static String text(ToolContent content) {
    return content instanceof TextToolContent text ? text.text() : "";
  }

  private static final class RecordingListener implements ToolExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private final List<ToolResult> partials = new ArrayList<>();
    private volatile ToolResult result;
    private volatile ToolExecutionHandle handle;

    @Override
    public void onPartial(ToolResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(ToolResult result) {
      this.result = result;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }

    private boolean await() throws InterruptedException {
      return completed.await(5, TimeUnit.SECONDS);
    }
  }
}
