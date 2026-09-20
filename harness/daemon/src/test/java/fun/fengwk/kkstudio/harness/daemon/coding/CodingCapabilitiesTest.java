package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
            "fs.edit",
            "process.exec",
            "fs.grep",
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
    CodingToolsConfig bridged = TestCodingConfig.withBridge(environmentRoot);

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
        TestCodingConfig.withBridgeCommand(environmentRoot, 2000, 50 * 1024, bridge.toString());

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
    Files.write(
        environmentRoot.resolve("image.png"),
        new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});
    Files.write(environmentRoot.resolve("binary.bin"), new byte[] {1, 0, 2});
    Files.createDirectory(environmentRoot.resolve("directory"));
    Files.writeString(environmentRoot.resolve("directory/a.txt"), "a");
    ReadCapability read = read(config());

    EnvironmentCapabilityResult window =
        invoke(
            read,
            "{\"path\":\"many.txt\",\"limit\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}");
    EnvironmentCapabilityResult directory =
        invoke(
            read, "{\"path\":\"directory\",\"workdir\":" + json(environmentRoot.toString()) + "}");
    EnvironmentCapabilityResult image =
        invoke(
            read, "{\"path\":\"image.png\",\"workdir\":" + json(environmentRoot.toString()) + "}");
    EnvironmentCapabilityResult binary =
        invoke(
            read, "{\"path\":\"binary.bin\",\"workdir\":" + json(environmentRoot.toString()) + "}");

    assertTrue(text(window).contains("Showing lines 1-1 of 3"));
    assertTrue(directory.contents().stream().anyMatch(content -> text(content).contains("a.txt")));
    // 图片不再产生本地 resource 引用：以内存字节进入终态，由 daemon 直传全局对象存储。
    assertTrue(image.contents().stream().anyMatch(BinaryResultContent.class::isInstance));
    assertEquals("image/png", ((BinaryResultContent) image.contents().getFirst()).mediaType());
    assertTrue(binary.error());
    assertTrue(text(binary).contains("binary"));
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

  /**
   * 验证 Bash live partial 的 APPEND 契约：details 声明 {@code process.output}/APPEND，区间连续且与已观测总量一致，
   * 拼接结果等于真实输出；终态内容与 live partial 不重复。
   */
  @Test
  void bashEmitsCoalescedAppendPartialsWithExactByteRanges() throws Exception {
    BashCapability bash = bash(config());
    RecordingListener listener =
        invokeAsync(
            bash,
            "{\"command\":\"printf 'alpha'; sleep 0.4; printf 'beta'; sleep 0.4; printf 'gamma'\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(10));
    assertTrue(listener.await());
    assertFalse(listener.result.error(), text(listener.result));
    assertTrue(listener.partials.size() >= 2, "慢速分片输出必须产生多条 partial");

    StringBuilder appended = new StringBuilder();
    long expectedStart = 0;
    for (EnvironmentCapabilityResult partial : listener.partials) {
      JsonNode details = AbstractCodingCapability.OBJECT_MAPPER.readTree(partial.detailsJson());
      assertEquals(BashCapability.PARTIAL_KIND, details.path("kind").asText());
      assertEquals(BashCapability.PARTIAL_MODE_APPEND, details.path("mode").asText());
      assertEquals(expectedStart, details.path("startOffset").asLong(), details.toString());
      assertTrue(details.path("endOffset").asLong() >= expectedStart, details.toString());
      expectedStart = details.path("endOffset").asLong();
      assertTrue(
          details.path("observedBytes").asLong() >= expectedStart, "已观测总量必须覆盖本区间：" + details);
      String chunk = ((TextResultContent) partial.contents().getFirst()).text();
      assertTrue(
          chunk.getBytes(StandardCharsets.UTF_8).length <= BashCapability.LIVE_PARTIAL_UTF8_BYTES,
          "单条 partial 必须有界");
      appended.append(chunk);
    }
    assertEquals("alphabetagamma", appended.toString(), "partial 拼接必须等于真实输出");
    assertEquals("alphabetagamma", text(listener.result), "终态仍是权威全文");
    // 区间与拼接字节数一致，说明没有重复也没有丢失。
    assertEquals(expectedStart, "alphabetagamma".getBytes(StandardCharsets.UTF_8).length);
  }

  /** 验证输出体积永远不终止进程：超过捕获预算时只停止文件捕获并补发 SNAPSHOT，命令仍跑到最后一行。 */
  @Test
  void bashTruncatedCaptureEmitsSnapshotAndNeverKillsTheProcess() throws Exception {
    TextOutputStore smallBudget =
        TextOutputStore.open(
            environmentRoot.resolve("budget/text"),
            environmentRoot.resolve("budget/staging"),
            2048);
    CodingToolsConfig budgetConfig =
        new CodingToolsConfig(
            environmentRoot,
            CodingToolsConfig.DEFAULT_PREVIEW_MAX_LINES,
            CodingToolsConfig.DEFAULT_PREVIEW_MAX_BYTES,
            "bash",
            smallBudget,
            null,
            CodingToolsConfig.DEFAULT_JAVAP_EXECUTABLE);

    RecordingListener listener =
        invokeAsync(
            new BashCapability(budgetConfig, executor, scheduler),
            "{\"command\":\"seq 1 20000; echo tail-marker\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(30));
    assertTrue(listener.await());
    assertFalse(listener.result.error(), "输出体积不得导致失败：" + text(listener.result));

    boolean sawSnapshot =
        listener.partials.stream()
            .anyMatch(
                partial -> {
                  try {
                    return BashCapability.PARTIAL_MODE_SNAPSHOT.equals(
                        AbstractCodingCapability.OBJECT_MAPPER
                            .readTree(partial.detailsJson())
                            .path("mode")
                            .asText());
                  } catch (Exception error) {
                    throw new AssertionError(error);
                  }
                });
    assertTrue(sawSnapshot, "捕获被截断时必须补发 SNAPSHOT partial，让调用方尽早知道全文不完整");

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER
            .readTree(listener.result.detailsJson())
            .path("textOutput");
    assertTrue(textOutput.path("captureTruncated").asBoolean(), textOutput.toString());
    assertTrue(
        textOutput.path("totalBytes").asLong() > textOutput.path("capturedBytes").asLong(),
        "总数必须完整统计，证明排空没有停止：" + textOutput);
    // seq 1 20000 加上最后一行 tail-marker：总数完整说明命令跑到了最后一行，进程没有被体积或预算终止。
    assertEquals(20001, textOutput.path("totalLines").asLong(), textOutput.toString());
    assertTrue(textOutput.path("path").asText().endsWith(".log"), "已捕获前缀仍必须发布为可读文件：" + textOutput);
    assertTrue(
        Files.readString(Path.of(textOutput.path("path").asText()), StandardCharsets.UTF_8)
            .startsWith("1\n"),
        "发布文件必须是输出的前缀");
  }

  /**
   * 验证本地磁盘写失败只降级为有界预览：不抛异常、不产生路径，更不终止子进程。
   *
   * <p>把 staging 目录设为不可写即可确定性地触发 IOException，无需任何 mock。
   */
  @Test
  void bashSurvivesLocalStorageFailureWithoutKillingTheProcess() throws Exception {
    assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "需要 POSIX 权限位来构造确定性的本地写入失败");
    Path resources = Files.createDirectories(environmentRoot.resolve("broken/resources"));
    TextOutputStore brokenStore =
        TextOutputStore.open(resources.resolve("text"), resources.resolve("staging"));
    Files.setPosixFilePermissions(
        brokenStore.stagingDirectory(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
    CodingToolsConfig brokenConfig =
        new CodingToolsConfig(
            environmentRoot,
            CodingToolsConfig.DEFAULT_PREVIEW_MAX_LINES,
            CodingToolsConfig.DEFAULT_PREVIEW_MAX_BYTES,
            "bash",
            brokenStore,
            null,
            CodingToolsConfig.DEFAULT_JAVAP_EXECUTABLE);

    RecordingListener listener =
        invokeAsync(
            new BashCapability(brokenConfig, executor, scheduler),
            // 输出远超内联阈值，必然尝试落盘；命令最后一行证明进程跑完了。
            "{\"command\":\"seq 1 20000; echo tail-marker\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(30));
    assertTrue(listener.await());
    assertFalse(listener.result.error(), "本地写入失败不得使调用失败：" + text(listener.result));

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER
            .readTree(listener.result.detailsJson())
            .path("textOutput");
    assertTrue(textOutput.path("captureFailed").asBoolean(), textOutput.toString());
    assertTrue(textOutput.path("path").isMissingNode(), "失败时不得给出不存在的路径");
    assertEquals(20001, textOutput.path("totalLines").asLong(), "进程必须跑完，总数仍然完整");
    assertTrue(
        text(listener.result).contains("could not be saved to local storage"),
        text(listener.result));
    assertTrue(
        listener.result.contents().stream().noneMatch(ResourceResultContent.class::isInstance),
        "降级预览不得变成 Resource");
  }

  /**
   * 验证 LSP 子进程使用调用方有效超时而不是隐藏的 30 秒常量：bridge 故意挂住时必须在有效超时内失败返回。
   *
   * <p>若实现退回旧的固定 30 秒常量，本测试的 5 秒 await 会超时失败。
   */
  @Test
  void lspBridgeHonoursTheEffectiveTimeoutInsteadOfAHiddenDefault() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path bridge = environmentRoot.resolve("hanging-bridge.sh");
    Files.writeString(bridge, "#!/bin/sh\ncat >/dev/null\nsleep 30\n");
    assertTrue(bridge.toFile().setExecutable(true));
    Files.writeString(environmentRoot.resolve("App.java"), "class App {}\n");

    CodingToolsConfig config =
        TestCodingConfig.withBridgeCommand(environmentRoot, 2000, 50 * 1024, bridge.toString());

    long started = System.nanoTime();
    RecordingListener listener =
        invokeAsync(
            new LspGotoDefinitionCapability(config, executor),
            "{\"path\":\"App.java\",\"line\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofMillis(1200));
    assertTrue(listener.await(), "必须在有效超时内返回，而不是等隐藏常量");
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    assertTrue(listener.result.error(), text(listener.result));
    assertTrue(text(listener.result).contains("timed out"), text(listener.result));
    assertTrue(elapsedMillis < 10_000, "必须在调用方有效超时附近返回，实际 " + elapsedMillis + "ms");
  }

  /** 验证 LSP 调用被取消后整棵 bridge 进程树都被终止：后代不再继续写 tick 文件。 */
  @Test
  void lspBridgeCancellationTerminatesTheProcessTree() throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    Path marker = environmentRoot.resolve("bridge-ticks.log");
    Path bridge = environmentRoot.resolve("ticking-bridge.sh");
    Files.writeString(
        bridge,
        "#!/bin/sh\n"
            + "cat >/dev/null\n"
            + "(while true; do echo child >> "
            + marker
            + "; sleep 0.05; done) &\n"
            + "while true; do echo parent >> "
            + marker
            + "; sleep 0.05; done\n");
    assertTrue(bridge.toFile().setExecutable(true));
    Files.writeString(environmentRoot.resolve("App.java"), "class App {}\n");

    CodingToolsConfig config =
        TestCodingConfig.withBridgeCommand(environmentRoot, 2000, 50 * 1024, bridge.toString());

    RecordingListener listener =
        invokeAsync(
            new LspGotoDefinitionCapability(config, executor),
            "{\"path\":\"App.java\",\"line\":1,\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(30));
    // 等待 bridge 真正开始产出，再取消。
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (ticks(marker) == 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(ticks(marker) > 0, "取消前 bridge 进程树必须已经在产出输出");

    listener.handle.cancel();
    assertTrue(listener.await());
    assertTrue(text(listener.result).contains("Operation cancelled"), text(listener.result));

    long ticksAtCancel = ticks(marker);
    Thread.sleep(700);
    assertEquals(ticksAtCancel, ticks(marker), "取消必须终止整棵 bridge 进程树，后代不得继续写入");
  }

  private static long ticks(Path marker) {
    try {
      return Files.readAllLines(marker).size();
    } catch (Exception error) {
      return 0;
    }
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

  /**
   * 验证 Bash 大输出跨越 2000 行内联限制后返回单个 TextResultContent：有界 head/tail 预览、绝对本地路径、总字节/行数与 read/grep 指引。
   *
   * <p>大文本绝不是 Resource：不经过 ResourceStore，也不做内容寻址。
   */
  @Test
  void bashSpoolsLargeOutputToBoundedTextResultWithPath() throws Exception {
    BashCapability bash = bash(config());
    RecordingListener listener =
        invokeAsync(
            bash,
            "{\"command\":\"seq 1 20000\",\"workdir\":" + json(environmentRoot.toString()) + "}",
            Duration.ofSeconds(20));
    assertTrue(listener.await());
    assertFalse(listener.result.error());
    assertEquals(1, listener.result.contents().size());
    assertTrue(listener.result.contents().getFirst() instanceof TextResultContent);
    assertFalse(
        listener.result.contents().stream().anyMatch(ResourceResultContent.class::isInstance),
        "大文本不得作为 Resource 附件返回");

    String text = text(listener.result);
    // 预览有界：头部前若干行可见，中间被省略并标注，尾部可见。
    assertTrue(text.startsWith("1\n2\n"), text);
    assertTrue(text.contains("bytes omitted here"), text);
    assertTrue(text.contains("20000 lines"), text);
    assertTrue(text.contains("Use read with offset/limit to page through the file"), text);

    JsonNode details =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(listener.result.detailsJson());
    JsonNode textOutput = details.path("textOutput");
    assertEquals(20000, textOutput.path("totalLines").asLong());
    assertFalse(textOutput.path("captureTruncated").asBoolean());

    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(published.isAbsolute());
    assertTrue(text.contains(published.toString()), "预览必须内联绝对路径");
    // durable 全文保留全部 20000 行，模型可继续 read/grep 分页。
    List<String> lines = Files.readAllLines(published);
    assertEquals(20000, lines.size());
    assertEquals("1", lines.getFirst());
    assertEquals("20000", lines.getLast());
  }

  /**
   * 验证输出体积永远不是终止进程或让调用失败的理由：远超 16 MiB 的输出仍然正常完成，退出码是权威事实。
   *
   * <p>这是对旧 {@code OUTPUT_TOO_LARGE} 语义的显式反向断言。
   */
  @Test
  void bashNeverFailsOrKillsProcessOnLargeOutputVolume() throws Exception {
    BashCapability bash = bash(config());
    RecordingListener listener =
        invokeAsync(
            bash,
            "{\"command\":\"seq 1 4000000\",\"workdir\":" + json(environmentRoot.toString()) + "}",
            Duration.ofSeconds(60));
    assertTrue(listener.await());
    assertFalse(listener.result.error(), "输出体积不得导致调用失败：" + text(listener.result));

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER
            .readTree(listener.result.detailsJson())
            .path("textOutput");
    // 远大于旧的 16 MiB 硬上限，且进程正常退出、计数完整。
    assertTrue(textOutput.path("totalBytes").asLong() > 16L * 1024 * 1024, textOutput.toString());
    assertEquals(4000000, textOutput.path("totalLines").asLong());
    assertTrue(
        textOutput.path("captureTruncated").asBoolean()
            || textOutput.path("path").asText().endsWith(".log"),
        "大输出必须落本地 durable 全文：" + textOutput);

    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(Files.isRegularFile(published));
    assertEquals(4000000, Files.readAllLines(published).size(), "durable 全文必须保留全部行（未被体积截断）");
  }

  /** 达到捕获预算只停止文件捕获并明确报告截断，命令本身仍必须跑完，退出码仍然有效。 */
  @Test
  void bashCaptureBudgetStopsCaptureWithoutStoppingTheProcess() throws Exception {
    TextOutputStore smallBudget =
        TextOutputStore.open(
            environmentRoot.resolve("budget/text"),
            environmentRoot.resolve("budget/staging"),
            4096);
    CodingToolsConfig budgetConfig =
        new CodingToolsConfig(
            environmentRoot,
            CodingToolsConfig.DEFAULT_PREVIEW_MAX_LINES,
            CodingToolsConfig.DEFAULT_PREVIEW_MAX_BYTES,
            "bash",
            smallBudget,
            null,
            CodingToolsConfig.DEFAULT_JAVAP_EXECUTABLE);

    BashCapability bash = new BashCapability(budgetConfig, executor, scheduler);
    RecordingListener listener =
        invokeAsync(
            bash,
            "{\"command\":\"seq 1 5000; echo done-marker\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofSeconds(15));
    assertTrue(listener.await());
    // 命令跑到了最后，证明进程没有被体积或预算终止。
    assertFalse(listener.result.error(), text(listener.result));

    JsonNode textOutput =
        AbstractCodingCapability.OBJECT_MAPPER
            .readTree(listener.result.detailsJson())
            .path("textOutput");
    assertTrue(textOutput.path("captureTruncated").asBoolean(), textOutput.toString());
    assertTrue(textOutput.path("capturedBytes").asLong() <= 4096, textOutput.toString());
    // 总数仍然完整统计，说明排空从未停止。
    assertTrue(textOutput.path("totalBytes").asLong() > textOutput.path("capturedBytes").asLong());

    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(Files.isRegularFile(published), "已捕获的前缀仍必须发布为可读文件");
    assertTrue(
        new String(Files.readAllBytes(published), StandardCharsets.UTF_8).startsWith("1\n"),
        "发布文件必须是输出的前缀");
  }

  /** bash 只消费已解析的 request.timeout：显式短超时必须在 deadline 内终止命令，且请求值是唯一来源。 */
  @Test
  void bashConsumesResolvedRequestTimeout() throws Exception {
    // 显式 1 秒 deadline 必须在命令自然结束前终止它。
    RecordingListener timed =
        invokeAsync(
            bash(config()),
            "{\"command\":\"sleep 2\",\"workdir\":" + json(environmentRoot.toString()) + "}",
            Duration.ofSeconds(1));
    assertTrue(timed.await());
    assertTrue(text(timed.result).contains("Command timed out"), text(timed.result));

    // 0 表示没有 deadline：命令必须自然跑完，不能被退化为立即超时。
    RecordingListener noDeadline =
        invokeAsync(
            bash(config()),
            "{\"command\":\"echo done\",\"workdir\":" + json(environmentRoot.toString()) + "}",
            Duration.ZERO);
    assertTrue(noDeadline.await());
    assertFalse(noDeadline.result.error(), text(noDeadline.result));
    assertTrue(text(noDeadline.result).contains("done"), text(noDeadline.result));
  }

  /** grep/find 使用 definition 默认超时（1 分钟），并原样消费 request.timeout：显式超时严格生效，0 表示无 deadline。 */
  @Test
  void searchCapabilitiesConsumeResolvedRequestTimeout() throws Exception {
    assertEquals(Duration.ofMinutes(1), grep(config()).descriptor().defaultTimeout());
    assertEquals(Duration.ofMinutes(1), find(config()).descriptor().defaultTimeout());

    // 显式短 deadline 必须立即生效，且错误信息报告的就是该 request 值。
    EnvironmentCapabilityResult timed =
        invoke(
            grep(config()),
            "{\"pattern\":\"a\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ofNanos(1));
    assertTrue(timed.error(), text(timed));
    assertTrue(text(timed).contains("grep timed out"), text(timed));

    // 0 表示没有 execution deadline：搜索必须自然完成，而不是被当成立即超时。
    EnvironmentCapabilityResult noDeadline =
        invoke(
            grep(config()),
            "{\"pattern\":\"a\",\"path\":\".\",\"workdir\":"
                + json(environmentRoot.toString())
                + "}",
            Duration.ZERO);
    assertFalse(noDeadline.error(), text(noDeadline));
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
    return TestCodingConfig.withLimits(environmentRoot, lines, bytes);
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
    return invoke(capability, arguments, Duration.ZERO);
  }

  private EnvironmentCapabilityResult invoke(
      EnvironmentCapability capability, String arguments, Duration timeout) throws Exception {
    RecordingListener listener = invokeAsync(capability, arguments, timeout);
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
