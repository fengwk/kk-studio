package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;

class CodingToolsTest {

  @TempDir Path environmentRoot;

  @Test
  void registersEnvironmentDescriptorsAndRejectsMalformedArguments() {
    CodingToolsConfig config = config();
    DaemonToolRegistry registry = new DaemonToolRegistry();
    CodingTools.registerAll(registry, config);

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
    ReadTool read = new ReadTool(config());
    WriteTool write = new WriteTool(config());

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
    EditTool edit = new EditTool(config());

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
  void readReturnsDirectoryWindowAndBinaryResource() throws Exception {
    Files.writeString(environmentRoot.resolve("many.txt"), "one\ntwo\nthree\n");
    Files.write(environmentRoot.resolve("binary.bin"), new byte[] {1, 0, 2});
    Files.createDirectory(environmentRoot.resolve("directory"));
    Files.writeString(environmentRoot.resolve("directory/a.txt"), "a");
    ReadTool read = new ReadTool(config());
    ReadTool constrainedRead = new ReadTool(config(1, 16));

    ToolResult window = invoke(read, "{\"path\":\"many.txt\",\"limit\":1}");
    ToolResult directory = invoke(read, "{\"path\":\"directory\"}");
    ToolResult binary = invoke(constrainedRead, "{\"path\":\"binary.bin\"}");

    assertTrue(text(window).contains("Showing lines 1-1 of 3"));
    assertTrue(directory.contents().stream().anyMatch(content -> text(content).contains("a.txt")));
    assertTrue(binary.contents().stream().anyMatch(ResourceToolContent.class::isInstance));
  }

  @Test
  void serializesConcurrentMutationsOfTheSameFile() throws Exception {
    WriteTool write = new WriteTool(config());

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
    GrepTool grep = new GrepTool(config());
    FindTool find = new FindTool(config());

    ToolResult grepResult = invoke(grep, "{\"pattern\":\"needle\",\"path\":\".\",\"limit\":1}");
    ToolResult findResult = invoke(find, "{\"pattern\":\"*.txt\",\"path\":\".\",\"limit\":10}");

    assertTrue(text(grepResult).contains("results limit reached"));
    assertTrue(text(findResult).contains("visible.txt"));
    assertFalse(text(findResult).contains("ignored.txt"));
  }

  @Test
  void bashStreamsAndReportsTimeoutAndIdempotentCancellation() throws Exception {
    BashTool bash = new BashTool(config());
    RecordingListener streaming =
        invokeAsync(
            bash,
            "{\"command\":\"printf first; sleep 0.05; printf second\"}",
            Duration.ofSeconds(2));
    assertTrue(streaming.await());
    assertTrue(streaming.partials.size() >= 1);
    assertTrue(text(streaming.result).contains("firstsecond"));

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

    // A short explicit timeout must terminate the command before the outer request deadline.
    RecordingListener timed =
        invokeAsync(
            new BashTool(config()),
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

    assertEquals(Duration.ofHours(1), new GrepTool(config()).descriptor().timeout());
    assertEquals(Duration.ofHours(1), new FindTool(config()).descriptor().timeout());
    assertEquals(15, GrepTool.requestedTimeoutSeconds(absent));
    assertEquals(7, GrepTool.requestedTimeoutSeconds(explicit));
    assertEquals(
        Duration.ofSeconds(15),
        GrepTool.effectiveProcessTimeout(
            Duration.ofHours(1), GrepTool.requestedTimeoutSeconds(absent)));
    assertEquals(
        Duration.ofSeconds(2),
        GrepTool.effectiveProcessTimeout(
            Duration.ofSeconds(2), GrepTool.requestedTimeoutSeconds(absent)));
    assertEquals(
        Duration.ofSeconds(7),
        GrepTool.effectiveProcessTimeout(
            Duration.ofSeconds(30), GrepTool.requestedTimeoutSeconds(explicit)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GrepTool.requestedTimeoutSeconds(
                AbstractCodingTool.OBJECT_MAPPER.readTree("{\"timeout_seconds\":3601}")));

    assertEquals(Duration.ofHours(1), FindTool.effectiveProcessTimeout(Duration.ZERO, absent));
    assertEquals(
        Duration.ofMinutes(2), FindTool.effectiveProcessTimeout(Duration.ofMinutes(2), absent));
    assertEquals(
        Duration.ofSeconds(7), FindTool.effectiveProcessTimeout(Duration.ofMinutes(2), explicit));
    assertEquals(
        Duration.ofSeconds(2), FindTool.effectiveProcessTimeout(Duration.ofSeconds(2), explicit));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FindTool.effectiveProcessTimeout(
                Duration.ofMinutes(2),
                AbstractCodingTool.OBJECT_MAPPER.readTree("{\"timeout_seconds\":0}")));
  }

  private CodingToolsConfig config() {
    return config(2000, 50 * 1024);
  }

  private CodingToolsConfig config(int lines, int bytes) {
    return new CodingToolsConfig(
        environmentRoot,
        environmentRoot,
        lines,
        bytes,
        "bash",
        "rg",
        "fd",
        new InMemoryResourceStore());
  }

  private ToolExecutionRequest request(Tool tool, String arguments) {
    return new ToolExecutionRequest(
        tool.descriptor(),
        new ToolCall("call", tool.descriptor().name(), arguments),
        Duration.ZERO);
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
                timeout),
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
