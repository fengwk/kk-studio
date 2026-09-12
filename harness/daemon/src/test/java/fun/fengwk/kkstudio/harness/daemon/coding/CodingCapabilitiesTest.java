package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.daemon.DaemonCapabilityRegistry;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class CodingCapabilitiesTest {

  @TempDir Path environmentRoot;
  @TempDir Path externalRoot;
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
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    CodingCapabilities.registerAll(registry, config, executor, scheduler);

    assertEquals(
        List.of(
            "fs.read",
            "fs.write",
            "fs.apply-edit",
            "process.exec",
            "fs.search",
            "fs.find",
            "lsp.goto-definition",
            "lsp.workspace-symbols",
            "lsp.java-decompile"),
        registry.descriptors().stream().map(d -> d.id().value()).toList());
    EnvironmentCapability read = registry.find(EnvironmentCapabilityIds.FS_READ).orElseThrow();
    assertThrows(
        IllegalArgumentException.class,
        () -> request(read, "{\"path\":1,\"workdir\":\"" + environmentRoot + "\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                read, "{\"path\":\"x\",\"workdir\":\"" + environmentRoot + "\",\"unknown\":true}"));
    assertThrows(IllegalArgumentException.class, () -> request(read, "{\"path\":\"x\"}"));
  }

  /** 越出 workdir 的相对遍历与符号链接目标都是普通路径：读取照常解析，写入落到符号链接指向的真实目录。 */
  @Test
  void resolvesTraversalAndSymlinkTargetsOutsideWorkdir() throws Exception {
    Files.writeString(externalRoot.resolve("secret.txt"), "secret");
    Files.createSymbolicLink(environmentRoot.resolve("escape"), externalRoot);
    ReadCapability read = read(config());
    WriteCapability write = write(config());
    String traversalPath =
        environmentRoot.relativize(externalRoot.resolve("secret.txt")).toString();

    EnvironmentCapabilityResult traversal =
        invoke(
            read,
            "{\"path\":"
                + json(traversalPath)
                + ",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult symlink =
        invoke(
            read,
            "{\"path\":\"escape/secret.txt\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult writeThroughSymlink =
        invoke(
            write,
            "{\"path\":\"escape/new.txt\",\"content\":\"x\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

    assertFalse(traversal.error());
    assertTrue(text(traversal).contains("secret"));
    assertFalse(symlink.error());
    assertTrue(text(symlink).contains("secret"));
    assertFalse(writeThroughSymlink.error());
    assertEquals("x", Files.readString(externalRoot.resolve("new.txt")));
  }

  @Test
  void writeAndEditPreserveBomNewlinesAndSerializeExactReplacement() throws Exception {
    Path file = environmentRoot.resolve("sample.txt");
    Files.write(
        file, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\r', '\n', 'a', '\r', '\n'});
    EditCapability edit = edit(config());

    EnvironmentCapabilityResult duplicate =
        invoke(
            edit,
            "{\"path\":\"sample.txt\",\"old_string\":\"a\\n\",\"new_string\":\"b\\n\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult replaced =
        invoke(
            edit,
            "{\"path\":\"sample.txt\",\"old_string\":\"a\\n"
                + "\",\"new_string\":\"b\\n"
                + "\",\"replace_all\":true,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

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

    EnvironmentCapabilityResult disabled =
        invoke(
            read(config()),
            "{\"path\":\"lsp-status.txt\",\"workdir\":" + json(environmentRoot.toString()) + "}");
    EnvironmentCapabilityResult enabled =
        invoke(
            read(bridged),
            "{\"path\":\"lsp-status.txt\",\"workdir\":" + json(environmentRoot.toString()) + "}");

    assertTrue(text(disabled).contains("lsp: unsupported"));
    assertTrue(text(enabled).contains("lsp: supported"));
  }

  /** LSP bridge 子进程必须使用本次调用的显式 workdir，不能继承 Daemon/JVM 进程目录。 */
  @Test
  void lspBridgeRunsInExplicitWorkdir() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path source = environmentRoot.resolve("Main.java");
    Files.writeString(source, "class Main {}\n");
    Path bridge = environmentRoot.resolve("bridge.sh");
    Files.writeString(
        bridge,
        """
        #!/bin/sh
        cat >/dev/null
        printf '{"ok":true,"text":"%s"}' "$PWD"
        """);
    assertTrue(bridge.toFile().setExecutable(true));
    CodingToolsConfig config =
        new CodingToolsConfig(
            environmentRoot,
            2000,
            50 * 1024,
            "bash",
            new InMemoryResourceStore(),
            bridge.toString(),
            "javap");

    EnvironmentCapabilityResult result =
        invoke(
            new LspGotoDefinitionCapability(config, executor),
            "{\"path\":\"Main.java\",\"line\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

    assertFalse(result.error());
    assertEquals(environmentRoot.toRealPath().toString(), text(result));
  }

  @Test
  void editRejectsMultipleOrOverlappingOccurrences() throws Exception {
    Path overlap = environmentRoot.resolve("overlap.txt");
    Files.writeString(overlap, "aaa\n");
    Path replaced = environmentRoot.resolve("replace-all.txt");
    Files.writeString(replaced, "abab\n");
    EditCapability edit = edit(config());

    EnvironmentCapabilityResult overlapping =
        invoke(
            edit,
            "{\"path\":\"overlap.txt\",\"old_string\":\"aa\",\"new_string\":\"b\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult overlappingAll =
        invoke(
            edit,
            "{\"path\":\"overlap.txt\",\"old_string\":\"aa\",\"new_string\":\"b\",\"replace_all\":true,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult replacedResult =
        invoke(
            edit,
            "{\"path\":\"replace-all.txt\",\"old_string\":\"ab\",\"new_string\":\"x\",\"replace_all\":true,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

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
    EditCapability edit = edit(config());

    EnvironmentCapabilityResult crlfSpelled =
        invoke(
            edit,
            "{\"path\":\"crlf-noop.txt\",\"old_string\":\"alpha\\r\\n\",\"new_string\":\"alpha\\n\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult lfSpelled =
        invoke(
            edit,
            "{\"path\":\"lf-noop.txt\",\"old_string\":\"alpha\\n\",\"new_string\":\"alpha\\r\\n\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

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
    EditCapability edit = edit(config());

    EnvironmentCapabilityResult crResult =
        invoke(
            edit,
            "{\"path\":\"cr-only.txt\",\"old_string\":\"alpha\\nbeta\",\"new_string\":\"left\\nright\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult mixedResult =
        invoke(
            edit,
            "{\"path\":\"mixed.txt\",\"old_string\":\"alpha\\nbeta\",\"new_string\":\"left\\nright\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

    // mixed 文件中被替换段内部无行尾（歧义）时，新增换行回退 LF，未修改区域仍原样保留。
    Path ambiguous = environmentRoot.resolve("mixed-ambiguous.txt");
    Files.write(ambiguous, "head\r\nmiddle\rtail".getBytes(StandardCharsets.UTF_8));
    EnvironmentCapabilityResult ambiguousResult =
        invoke(
            edit,
            "{\"path\":\"mixed-ambiguous.txt\",\"old_string\":\"middle\",\"new_string\":\"left\\nright\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

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
    ReadCapability read = read(config());
    ReadCapability constrainedRead = read(config(1, 16));

    EnvironmentCapabilityResult window =
        invoke(
            read,
            "{\"path\":\"many.txt\",\"limit\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult directory =
        invoke(
            read, "{\"path\":\"directory\",\"workdir\":" + json(environmentRoot.toString()) + "}");
    EnvironmentCapabilityResult binary =
        invoke(
            constrainedRead,
            "{\"path\":\"binary.bin\",\"workdir\":" + json(environmentRoot.toString()) + "}");

    assertTrue(text(window).contains("Showing lines 1-1 of 3"));
    assertTrue(directory.contents().stream().anyMatch(content -> text(content).contains("a.txt")));
    assertTrue(binary.contents().stream().anyMatch(ResourceResultContent.class::isInstance));
  }

  @Test
  void serializesConcurrentMutationsOfTheSameFile() throws Exception {
    WriteCapability write = write(config());

    RecordingListener first =
        invokeAsync(
            write,
            "{\"path\":\"shared.txt\",\"content\":\"first\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ZERO);
    RecordingListener second =
        invokeAsync(
            write,
            "{\"path\":\"shared.txt\",\"content\":\"second\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ZERO);

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
    GrepCapability grep = grep(config());
    FindCapability find = find(config());

    EnvironmentCapabilityResult grepResult =
        invoke(
            grep,
            "{\"pattern\":\"needle\",\"path\":\".\",\"limit\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult findResult =
        invoke(
            find,
            "{\"pattern\":\"*.txt\",\"path\":\".\",\"limit\":10,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");

    assertTrue(text(grepResult).contains("results limit reached"));
    assertTrue(text(findResult).contains("visible.txt"));
    assertFalse(text(findResult).contains("ignored.txt"));
  }

  @Test
  void bashStreamsAndReportsTimeoutAndIdempotentCancellation() throws Exception {
    BashCapability bash = bash(config());
    RecordingListener streaming =
        invokeAsync(
            bash,
            "{\"command\":\"printf first; sleep 0.05; printf second\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(2));
    assertTrue(streaming.await());
    assertTrue(streaming.partials.size() >= 1);
    assertTrue(text(streaming.result).contains("firstsecond"));

    RecordingListener ansi =
        invokeAsync(
            bash,
            "{\"command\":\"printf '\\\\033[31mpassed\\\\033[0m\\\\n'\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(2));
    assertTrue(ansi.await());
    assertTrue(text(ansi.result).contains("passed"));
    assertFalse(ansi.result.contents().stream().anyMatch(ResourceResultContent.class::isInstance));

    RecordingListener timeout =
        invokeAsync(
            bash,
            "{\"command\":\"sleep 2\",\"workdir\":" + json(environmentRoot.toString()) + "}",
            Duration.ofMillis(50));
    assertTrue(timeout.await());
    assertTrue(text(timeout.result).contains("Command timed out"));

    RecordingListener cancelled =
        invokeAsync(
            bash,
            "{\"command\":\"sleep 2\",\"workdir\":" + json(environmentRoot.toString()) + "}",
            Duration.ofSeconds(2));
    cancelled.handle.cancel();
    cancelled.handle.cancel();
    assertTrue(cancelled.await());
    assertTrue(text(cancelled.result).contains("Operation cancelled"));
  }

  @Test
  void bashTimeoutParameterDefaultsAndNeverExceedsInvocationDeadline() throws Exception {
    var missing = AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"command\":\"true\"}");
    var explicit =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(
            "{\"command\":\"true\",\"timeout_seconds\":7}");
    assertEquals(120, BashCapability.requestedTimeoutSeconds(missing));
    assertEquals(7, BashCapability.requestedTimeoutSeconds(explicit));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BashCapability.requestedTimeoutSeconds(
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"timeout_seconds\":0}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BashCapability.requestedTimeoutSeconds(
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"timeout_seconds\":3601}")));

    assertEquals(
        Duration.ofSeconds(120),
        BashCapability.effectiveProcessTimeout(Duration.ofHours(1), Duration.ofSeconds(120)));
    assertEquals(
        Duration.ofSeconds(2),
        BashCapability.effectiveProcessTimeout(Duration.ofSeconds(2), Duration.ofSeconds(120)));
    assertEquals(
        Duration.ofSeconds(7),
        BashCapability.effectiveProcessTimeout(Duration.ofSeconds(30), Duration.ofSeconds(7)));

    // 显式的短 timeout 必须在外部 request deadline 到来之前终止命令。
    RecordingListener timed =
        invokeAsync(
            bash(config()),
            "{\"command\":\"sleep 2\",\"timeout_seconds\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(5));
    assertTrue(timed.await());
    assertTrue(text(timed.result).contains("Command timed out"));
  }

  /** grep 保持 15 秒默认值；find 只受调用 deadline 或显式 timeout 约束。 */
  @Test
  void searchTimeoutsMatchPiDefaultsAndRespectInvocationDeadlines() throws Exception {
    var absent = AbstractCodingCapability.OBJECT_MAPPER.readTree("{}");
    var explicit = AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"timeout_seconds\":7}");

    assertEquals(Duration.ofHours(1), grep(config()).descriptor().timeout());
    assertEquals(Duration.ofHours(1), find(config()).descriptor().timeout());
    assertEquals(15, GrepCapability.requestedTimeoutSeconds(absent));
    assertEquals(7, GrepCapability.requestedTimeoutSeconds(explicit));
    assertEquals(
        Duration.ofSeconds(15),
        GrepCapability.effectiveSearchTimeout(
            Duration.ofHours(1), GrepCapability.requestedTimeoutSeconds(absent)));
    assertEquals(
        Duration.ofSeconds(2),
        GrepCapability.effectiveSearchTimeout(
            Duration.ofSeconds(2), GrepCapability.requestedTimeoutSeconds(absent)));
    assertEquals(
        Duration.ofSeconds(7),
        GrepCapability.effectiveSearchTimeout(
            Duration.ofSeconds(30), GrepCapability.requestedTimeoutSeconds(explicit)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GrepCapability.requestedTimeoutSeconds(
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"timeout_seconds\":3601}")));

    assertEquals(Duration.ofHours(1), FindCapability.effectiveSearchTimeout(Duration.ZERO, absent));
    assertEquals(
        Duration.ofMinutes(2),
        FindCapability.effectiveSearchTimeout(Duration.ofMinutes(2), absent));
    assertEquals(
        Duration.ofSeconds(7),
        FindCapability.effectiveSearchTimeout(Duration.ofMinutes(2), explicit));
    assertEquals(
        Duration.ofSeconds(2),
        FindCapability.effectiveSearchTimeout(Duration.ofSeconds(2), explicit));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FindCapability.effectiveSearchTimeout(
                Duration.ofMinutes(2),
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"timeout_seconds\":0}")));
  }

  private CodingToolsConfig config() {
    return config(2000, 50 * 1024);
  }

  private ReadCapability read(CodingToolsConfig config) {
    return new ReadCapability(config, executor);
  }

  private WriteCapability write(CodingToolsConfig config) {
    return new WriteCapability(config, executor);
  }

  private EditCapability edit(CodingToolsConfig config) {
    return new EditCapability(config, executor);
  }

  private GrepCapability grep(CodingToolsConfig config) {
    return new GrepCapability(config, executor);
  }

  private FindCapability find(CodingToolsConfig config) {
    return new FindCapability(config, executor);
  }

  private BashCapability bash(CodingToolsConfig config) {
    return new BashCapability(config, executor, scheduler);
  }

  private CodingToolsConfig config(int lines, int bytes) {
    return new CodingToolsConfig(
        environmentRoot, lines, bytes, "bash", new InMemoryResourceStore());
  }

  /** 以 JSON 字符串字面量表示任意本地路径，避免手工拼接转义。 */
  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private EnvironmentCapabilityExecutionRequest request(
      EnvironmentCapability capability, String arguments) {
    return new EnvironmentCapabilityExecutionRequest(
        capability.descriptor(), new EnvironmentCapabilityCall("call", arguments), Duration.ZERO);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = invokeAsync(capability, arguments, Duration.ZERO);
    assertTrue(listener.await());
    assertNotNull(listener.result);
    return listener.result;
  }

  private RecordingListener invokeAsync(
      EnvironmentCapability capability, String arguments, Duration timeout) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(), new EnvironmentCapabilityCall("call", arguments), timeout),
            listener);
    return listener;
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream().map(CodingCapabilitiesTest::text).reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent text ? text.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    private volatile EnvironmentCapabilityResult result;
    private volatile EnvironmentCapabilityExecutionHandle handle;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
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
