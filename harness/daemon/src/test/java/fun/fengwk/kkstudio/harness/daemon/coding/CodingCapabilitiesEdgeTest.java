package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class CodingCapabilitiesEdgeTest {

  @TempDir Path workspaceRoot;
  @TempDir Path externalRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  /** LSP capability 的测试门面：本类只验证参数与接线，因此不配置任何服务器。 */
  private final ExecutorService lspDispatch = Executors.newCachedThreadPool();

  private final ScheduledExecutorService lspScheduler =
      Executors.newSingleThreadScheduledExecutor();
  private final LspService lspService =
      LspService.create(LspDiscovery.empty(), lspDispatch, lspScheduler);

  @AfterEach
  void closeExecutors() {
    lspService.close();
    lspScheduler.shutdownNow();
    lspDispatch.shutdownNow();
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  /** 错误类型与未知字段必须在执行前拒绝；integer 数字字符串会被归一化，因此 grep 用非数字文本证明类型失败。 */
  @Test
  void everyDescriptorRejectsWrongTypedAndUnknownArguments() {
    EnvironmentCapability[] capabilities = {
      write(config()), edit(config()), bash(config()), grep(config()), find(config())
    };
    String[] wrong = {
      "{\"path\":\"x\",\"content\":1,\"workdir\":\"" + workspaceRoot + "\"}",
      "{\"path\":\"x\",\"old_string\":\"a\",\"new_string\":\"b\",\"replace_all\":\"yes\",\"workdir\":\""
          + workspaceRoot
          + "\"}",
      "{\"command\":\"echo x\",\"workdir\":1}",
      "{\"pattern\":\"x\",\"path\":\".\",\"limit\":\"one\",\"workdir\":\"" + workspaceRoot + "\"}",
      "{\"pattern\":\"*\",\"path\":\".\",\"timeout_seconds\":false,\"workdir\":\""
          + workspaceRoot
          + "\"}"
    };
    for (int index = 0; index < capabilities.length; index++) {
      EnvironmentCapability capability = capabilities[index];
      String arguments = wrong[index];
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new EnvironmentCapabilityExecutionRequest(
                  capability.descriptor(),
                  new EnvironmentCapabilityCall("invalid", arguments),
                  Duration.ZERO));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new EnvironmentCapabilityExecutionRequest(
                  capability.descriptor(),
                  new EnvironmentCapabilityCall("unknown", "{\"unknown\":true}"),
                  Duration.ZERO));
    }
  }

  @Test
  void commonArgumentHelpersRejectInvalidValuesAndKeepDefaults() throws Exception {
    JsonNode values =
        AbstractCodingCapability.OBJECT_MAPPER.readTree(
            "{\"text\":\"x\",\"number\":2,\"truth\":true}");
    assertEquals("x", AbstractCodingCapability.string(values, "text"));
    assertNull(AbstractCodingCapability.optionalString(values, "missing"));
    assertEquals(7, AbstractCodingCapability.optionalPositiveInt(values, "missing", 7, 9));
    assertEquals(2, AbstractCodingCapability.optionalPositiveInt(values, "number", 7, 9));
    assertEquals(2, AbstractCodingCapability.requiredPositiveInt(values, "number"));
    assertEquals(7, AbstractCodingCapability.optionalNonNegativeInt(values, "missing", 7));
    assertEquals(2, AbstractCodingCapability.optionalNonNegativeInt(values, "number", 7));
    assertTrue(AbstractCodingCapability.optionalBoolean(values, "truth"));
    assertFalse(AbstractCodingCapability.optionalBoolean(values, "missing"));
    assertThrows(
        IllegalArgumentException.class, () -> AbstractCodingCapability.string(values, "number"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.optionalPositiveInt(values, "text", 1, 9));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AbstractCodingCapability.optionalPositiveInt(
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"number\":0}"), "number", 1, 9));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.requiredPositiveInt(values, "missing"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.requiredPositiveInt(values, "text"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AbstractCodingCapability.optionalNonNegativeInt(
                AbstractCodingCapability.OBJECT_MAPPER.readTree("{\"number\":-1}"), "number", 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> AbstractCodingCapability.optionalNonNegativeInt(values, "text", 1));
    assertTrue(AbstractCodingCapability.error("id", "failure").error());
    assertFalse(AbstractCodingCapability.success("id", "ok").error());
  }

  /** 共享 executor 上的 coding 任务收到 cancel 后必须被中断，并且只产生一次取消终态。 */
  @Test
  void abstractCodingExecutionUsesInjectedExecutorAndInterruptsOnCancel() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AbstractCodingCapability blocking =
        new AbstractCodingCapability(
            config(),
            executor,
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ)) {
          @Override
          EnvironmentCapabilityResult run(
              EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
            started.countDown();
            new CountDownLatch(1).await();
            return success(request.call().id(), "unexpected");
          }
        };
    RecordingListener listener =
        invokeAsync(
            blocking, "{\"path\":\"x\",\"workdir\":\"" + workspaceRoot + "\"}", Duration.ZERO);
    assertTrue(started.await(5, TimeUnit.SECONDS));

    listener.handle.cancel();
    listener.handle.cancel();

    assertTrue(listener.await());
    assertTrue(listener.result.error());
    assertTrue(text(listener.result).contains("Operation cancelled"));
    assertEquals(1, listener.completions);
    EnvironmentCapabilityExecutionRequest wrong =
        new EnvironmentCapabilityExecutionRequest(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
            new EnvironmentCapabilityCall(
                "wrong", "{\"command\":\"true\",\"workdir\":\"" + workspaceRoot + "\"}"),
            Duration.ofSeconds(1));
    assertThrows(IllegalArgumentException.class, () -> blocking.execute(wrong, listener));
  }

  /** workdir 校验必须绝对且现存；path 保持字面值，绝对路径与越出 workdir 的相对路径不受 root 范围限制。 */
  @Test
  void environmentPathsUseExplicitWorkdirAndAcceptExternalPaths() throws Exception {
    Path nested = Files.createDirectories(workspaceRoot.resolve("nested"));
    Files.writeString(nested.resolve("file.txt"), "x");
    Files.writeString(nested.resolve("@file.txt"), "x");
    Files.writeString(externalRoot.resolve("external.txt"), "x");
    Path externalFile = externalRoot.resolve("external.txt");
    String traversal = workspaceRoot.relativize(externalFile).toString();

    assertEquals(nested.toRealPath(), EnvironmentPaths.workdir(nested.toRealPath().toString()));
    assertEquals(
        nested.resolve("@file.txt").toRealPath(),
        EnvironmentPaths.existing("@file.txt", nested.toRealPath()));
    // 还不存在的新文件只能拿「真实根目录」拼出来：workdir 要的是绝对且真实存在的目录，返回的路径挂在真实根之下。
    // 直接用 junit 给的 @TempDir 拼会在两种平台上跑偏：macOS 的 /var 是指向 /private/var 的符号链接，Windows 的临时
    // 目录名可能是 8.3 短名（RUNNER~1 与 runneradmin 指向同一个目录但字符串不同）。
    assertEquals(
        nested.toRealPath().resolve("future/file.txt"),
        EnvironmentPaths.writable("future/file.txt", nested.toRealPath()));
    assertEquals(
        nested.resolve("file.txt").toRealPath(),
        EnvironmentPaths.writable("file.txt", nested.toRealPath()));
    assertEquals(
        nested.resolve("file.txt").toRealPath(),
        EnvironmentPaths.existing(
            nested.resolve("file.txt").toString(), workspaceRoot.toRealPath()));

    // 绝对路径与 ../ 遍历都是普通路径：workdir 只提供相对路径的解析基准。
    assertEquals(
        externalRoot.toRealPath(), EnvironmentPaths.workdir(externalRoot.toRealPath().toString()));
    assertEquals(
        externalFile.toRealPath(),
        EnvironmentPaths.existing(traversal, workspaceRoot.toRealPath()));
    assertEquals(
        externalFile.toRealPath(),
        EnvironmentPaths.existing(externalFile.toRealPath().toString(), nested.toRealPath()));
    assertEquals(
        externalRoot.toRealPath().resolve("external.txt.new"),
        EnvironmentPaths.writable(traversal + ".new", workspaceRoot.toRealPath()));

    IllegalArgumentException nullError =
        assertThrows(IllegalArgumentException.class, () -> EnvironmentPaths.workdir(null));
    assertTrue(
        nullError.getMessage().contains("workdir must be a non-blank absolute directory path"));

    IllegalArgumentException relativeError =
        assertThrows(IllegalArgumentException.class, () -> EnvironmentPaths.workdir("missing"));
    assertTrue(relativeError.getMessage().contains("workdir must be an absolute path"));

    IllegalArgumentException fileError =
        assertThrows(
            IllegalArgumentException.class,
            () -> EnvironmentPaths.workdir(nested.resolve("file.txt").toRealPath().toString()));
    assertTrue(fileError.getMessage().contains("workdir must be an existing directory"));

    IllegalArgumentException missingDirError =
        assertThrows(
            IllegalArgumentException.class,
            () -> EnvironmentPaths.workdir(workspaceRoot.resolve("missing").toString()));
    assertTrue(missingDirError.getMessage().contains("workdir must be an existing directory"));

    assertThrows(
        IllegalArgumentException.class, () -> EnvironmentPaths.existing("", nested.toRealPath()));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentPaths.existing("missing", nested.toRealPath()));
    assertThrows(IllegalArgumentException.class, () -> EnvironmentPaths.existing("file.txt", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentPaths.existing("\u0000", nested.toRealPath()));
    Path dangling = workspaceRoot.resolve("dangling");
    Files.createSymbolicLink(dangling, workspaceRoot.resolve("not-created"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentPaths.existing("dangling", workspaceRoot.toRealPath()));
  }

  @Test
  void codecPreservesAllSupportedBomFormats() throws Exception {
    byte[] utf16le = new byte[] {(byte) 0xff, (byte) 0xfe, 'a', 0, '\n', 0};
    byte[] utf16be = new byte[] {(byte) 0xfe, (byte) 0xff, 0, 'a', 0, '\n'};
    TextFileCodec.Decoded little = TextFileCodec.decode(utf16le);
    TextFileCodec.Decoded big = TextFileCodec.decode(utf16be);
    assertEquals("a\n", little.text());
    assertArrayEquals(
        utf16le, TextFileCodec.encode(little.text(), little.charset(), little.bomLength()));
    assertArrayEquals(utf16be, TextFileCodec.encode(big.text(), big.charset(), big.bomLength()));
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(new byte[] {0, 1}));
  }

  @Test
  void readToolDecodesUtf16BomAsNumberedText() throws Exception {
    Files.write(
        workspaceRoot.resolve("utf16.txt"),
        TextFileCodec.encode("alpha\nbeta\n", StandardCharsets.UTF_16LE, 2));

    EnvironmentCapabilityResult result =
        invoke(
            read(config()),
            "{\"path\":\"utf16.txt\",\"workdir\":" + json(workspaceRoot.toString()) + "}");

    assertFalse(result.error());
    assertTrue(text(result).contains("1|alpha"));
    assertTrue(text(result).contains("2|beta"));
    assertFalse(result.contents().stream().anyMatch(ResourceResultContent.class::isInstance));
  }

  /** 已删除的 {@code kkstudio.daemon.*} 系统属性不再是配置来源；配置只从运行时显式取值与数据目录资源根构建。 */
  @Test
  void runtimeConfigurationIgnoresRemovedSystemProperties() throws Exception {
    String[] removed = {
      "kkstudio.daemon.resource-directory",
      "kkstudio.daemon.max-resource-bytes",
      "kkstudio.daemon.bash",
      "kkstudio.daemon.lsp-bridge-command",
      "kkstudio.daemon.lsp-config",
      "kkstudio.daemon.javap"
    };
    String[] previous = new String[removed.length];
    for (int index = 0; index < removed.length; index++) {
      previous[index] = System.getProperty(removed[index]);
      System.setProperty(removed[index], "must-be-ignored");
    }
    try {
      Path resources = Files.createDirectories(workspaceRoot.resolve("data/resources"));
      CodingToolsConfig config =
          CodingToolsConfig.fromRuntime(resources, "/usr/bin/bash", TestCodingConfig.testLsp());

      // 被删除的属性不得改写任何运行时取值。
      assertEquals("/usr/bin/bash", config.bashExecutable());
      assertEquals("test-ls", config.lsp().servers().getFirst().id());
      // 输出布局完全由数据目录决定；本地不再有二进制 resource 导出根。
      assertEquals(resources.resolve("text"), config.textOutputStore().textDirectory());
      assertEquals(resources.resolve("staging"), config.textOutputStore().stagingDirectory());
    } finally {
      for (int index = 0; index < removed.length; index++) {
        if (previous[index] == null) {
          System.clearProperty(removed[index]);
        } else {
          System.setProperty(removed[index], previous[index]);
        }
      }
    }
  }

  /** 本地执行程序参数的默认值与显式覆盖：空白回退默认，显式取值原样保留，未配置时没有 LSP 服务器。 */
  @Test
  void runtimeConfigurationResolvesLocalExecutableDefaults() throws Exception {
    Path resources = Files.createDirectories(workspaceRoot.resolve("data/resources"));

    CodingToolsConfig defaults =
        CodingToolsConfig.fromRuntime(resources, "  ", LspDiscovery.empty());
    assertEquals(CodingToolsConfig.DEFAULT_BASH_EXECUTABLE, defaults.bashExecutable());
    assertTrue(defaults.lsp().servers().isEmpty(), "未配置 lsp 时没有 LSP 服务器");

    CodingToolsConfig explicit =
        CodingToolsConfig.fromRuntime(resources, "custom-bash", LspDiscovery.empty());
    assertEquals("custom-bash", explicit.bashExecutable());
    assertTrue(explicit.lsp().servers().isEmpty());
  }

  /** 无效配置必须在构造期 fail closed：非正阈值不允许进入运行期。 */
  @Test
  void configurationRejectsInvalidLimits() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> TestCodingConfig.withLimits(workspaceRoot, 0, 1));
    assertThrows(
        IllegalArgumentException.class, () -> TestCodingConfig.withLimits(workspaceRoot, 1, 0));
  }

  @Test
  void writeAndEditHandleCreateBinaryAndNoMatchErrors() throws Exception {
    WriteCapability write = write(config());
    EditCapability edit = edit(config());
    EnvironmentCapabilityResult created =
        invoke(
            write,
            "{\"path\":\"new/created.txt\",\"content\":\"one\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    EnvironmentCapabilityResult unchanged =
        invoke(
            edit,
            "{\"path\":\"new/created.txt\",\"old_string\":\"one\",\"new_string\":\"one\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    EnvironmentCapabilityResult absent =
        invoke(
            edit,
            "{\"path\":\"new/created.txt\",\"old_string\":\"zero\",\"new_string\":\"two\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    Files.write(workspaceRoot.resolve("binary.txt"), new byte[] {0, 1});
    EnvironmentCapabilityResult binary =
        invoke(
            edit,
            "{\"path\":\"binary.txt\",\"old_string\":\"a\",\"new_string\":\"b\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    EnvironmentCapabilityResult empty =
        invoke(
            edit,
            "{\"path\":\"new/created.txt\",\"old_string\":\"\",\"new_string\":\"b\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    EnvironmentCapabilityResult directory =
        invoke(
            edit,
            "{\"path\":\"new\",\"old_string\":\"a\",\"new_string\":\"b\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    assertFalse(created.error());
    assertEquals("one", Files.readString(workspaceRoot.resolve("new/created.txt")));
    assertTrue(text(unchanged).contains("must differ"));
    assertTrue(text(absent).contains("Could not find old_string"));
    assertTrue(text(binary).contains("appears to be binary"));
    assertTrue(text(empty).contains("must not be empty"));
    assertTrue(text(directory).contains("must be a file"));
  }

  @Test
  void bashReportsStartupAndNonZeroFailuresWithoutDuplicateTerminalCallbacks() throws Exception {
    BashCapability missing =
        bash(TestCodingConfig.withBash(workspaceRoot, 10, 100, "missing-bash"));
    String startupFailure =
        text(
            invoke(
                missing,
                "{\"command\":\"echo x\",\"workdir\":" + json(workspaceRoot.toString()) + "}"));
    assertTrue(startupFailure.contains("could not be started"));
    assertFalse(startupFailure.contains("missing-bash"));
    BashCapability bash = bash(config());
    EnvironmentCapabilityResult nonZero =
        invoke(
            bash,
            "{\"command\":\"echo failure; exit 7\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(nonZero.error());
    assertTrue(text(nonZero).contains("Command exited with code 7"));
  }

  @Test
  void utf8StreamDecoderCarriesIncompleteSuffixAndFlushesMalformedTail() {
    Utf8StreamDecoder decoder = new Utf8StreamDecoder();
    byte[] emoji = "😀".getBytes(StandardCharsets.UTF_8);

    assertEquals("", decoder.decode(emoji, 2));
    assertEquals("😀", decoder.decode(new byte[] {emoji[2], emoji[3]}, 2));
    assertEquals("", decoder.finish());
    assertEquals("", decoder.decode(new byte[] {(byte) 0xf0}, 1));
    assertEquals("�", decoder.finish());
    assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[1], 2));
  }

  @Test
  void bashStreamsSplitUtf8CodePointWithoutReplacementCharacters() throws Exception {
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"printf '\\\\360\\\\237'; sleep 0.05; printf '\\\\230\\\\200'\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}",
            Duration.ofSeconds(2));

    assertTrue(listener.await());
    String partialText =
        listener.partials.stream().map(CodingCapabilitiesEdgeTest::text).reduce("", String::concat);
    assertEquals("😀", partialText);
    assertFalse(partialText.contains("�"));
    assertEquals(1, listener.completions);
  }

  @Test
  void lspCapabilitiesRequireValidAbsoluteWorkdirAndFile() throws Exception {
    CodingToolsConfig config = config();
    Files.createDirectories(workspaceRoot.resolve("src"));
    Files.writeString(workspaceRoot.resolve("src/App.java"), "class App {}");

    LspGotoDefinitionCapability gotoDef =
        new LspGotoDefinitionCapability(config, lspService, executor);
    EnvironmentCapabilityResult relWorkdir =
        invoke(gotoDef, "{\"path\":\"src/App.java\",\"line\":1,\"workdir\":\"relative/dir\"}");
    assertTrue(relWorkdir.error());
    assertTrue(text(relWorkdir).contains("workdir must be an absolute path"));

    EnvironmentCapabilityResult missingFile =
        invoke(
            gotoDef,
            "{\"path\":\"src/Missing.java\",\"line\":1,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(missingFile.error());
    assertTrue(text(missingFile).contains("path does not exist"));

    LspWorkspaceSymbolsCapability wsSymbols =
        new LspWorkspaceSymbolsCapability(config, lspService, executor);
    EnvironmentCapabilityResult blankQuery =
        invoke(
            wsSymbols,
            "{\"path\":\"src/App.java\",\"query\":\"   \",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(blankQuery.error());
    assertTrue(text(blankQuery).contains("query must not be blank"));

    LspJavaDecompileCapability decompile =
        new LspJavaDecompileCapability(config, lspService, executor);
    EnvironmentCapabilityResult blankTarget =
        invoke(
            decompile,
            "{\"path\":\"src/App.java\",\"target\":\"   \",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(blankTarget.error());
    assertTrue(text(blankTarget).contains("target must not be blank"));

    // LspGotoDefinition rejects directory as path
    EnvironmentCapabilityResult dirAsPath =
        invoke(
            gotoDef,
            "{\"path\":\"src\",\"line\":1,\"workdir\":" + json(workspaceRoot.toString()) + "}");
    assertTrue(dirAsPath.error());
    assertTrue(text(dirAsPath).contains("path must be a file"));

    // LspWorkspaceSymbols rejects limit > 500
    EnvironmentCapabilityResult limitTooLarge =
        invoke(
            wsSymbols,
            "{\"path\":\"src/App.java\",\"query\":\"test\",\"limit\":501,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(limitTooLarge.error());
    assertTrue(text(limitTooLarge).contains("limit must be <= 500"));
  }

  @Test
  void environmentPathsEdgeCasesAndDisplayPath() throws Exception {
    Path file = workspaceRoot.resolve("regular.txt");
    Files.writeString(file, "hello");
    assertThrows(IllegalArgumentException.class, () -> EnvironmentPaths.workdir(file.toString()));
    assertThrows(
        IllegalArgumentException.class, () -> EnvironmentPaths.existing("child.txt", file));

    Path unreadable = Files.createDirectory(workspaceRoot.resolve("unreadable-dir"));
    if (unreadable.toFile().setReadable(false)) {
      try {
        assertThrows(
            IllegalArgumentException.class, () -> EnvironmentPaths.workdir(unreadable.toString()));
      } finally {
        unreadable.toFile().setReadable(true);
      }
    }

    assertEquals(".", EnvironmentPaths.displayPath(workspaceRoot, workspaceRoot, "."));
    assertEquals(
        "sub/file.txt",
        EnvironmentPaths.displayPath(
            workspaceRoot.resolve("sub/file.txt"), workspaceRoot, "sub/file.txt"));
    assertEquals(
        "/other/path.txt",
        EnvironmentPaths.displayPath(Path.of("/other/path.txt"), workspaceRoot, "/other/path.txt"));
    assertEquals("raw.txt", EnvironmentPaths.displayPath(null, workspaceRoot, "raw.txt"));
    assertEquals(
        "rel/target.txt", EnvironmentPaths.displayPath(null, workspaceRoot, "rel/target.txt"));
    assertEquals("a/c", EnvironmentPaths.displayPath(null, workspaceRoot, "a/b/../c"));
    assertEquals("<missing>", EnvironmentPaths.displayPath(null, workspaceRoot, null));
    assertEquals("", EnvironmentPaths.displayPath(null, workspaceRoot, ""));
    assertEquals("\u0000", EnvironmentPaths.displayPath(null, workspaceRoot, "\u0000"));

    assertTrue(SearchFiles.isGitMetadata(Path.of(".git/config")));
    assertFalse(SearchFiles.isGitMetadata(Path.of("src/App.java")));
    assertEquals("a/b/c", SearchFiles.toPosix(Path.of("a", "b", "c")));
  }

  @Test
  void lspCapabilitiesReportUnavailableWhenNotConfigured() throws Exception {
    CodingToolsConfig withoutLsp = TestCodingConfig.withoutLsp(workspaceRoot);

    Files.createDirectories(workspaceRoot.resolve("src"));
    Files.writeString(workspaceRoot.resolve("src/App.java"), "class App {}");

    LspGotoDefinitionCapability gotoDef =
        new LspGotoDefinitionCapability(withoutLsp, lspService, executor);
    EnvironmentCapabilityResult defRes =
        invoke(
            gotoDef,
            "{\"path\":\"src/App.java\",\"line\":1,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(defRes.error());
    assertTrue(text(defRes).contains("No LSP server configured"));

    LspWorkspaceSymbolsCapability wsSymbols =
        new LspWorkspaceSymbolsCapability(withoutLsp, lspService, executor);
    EnvironmentCapabilityResult wsRes =
        invoke(
            wsSymbols,
            "{\"path\":\"src/App.java\",\"query\":\"App\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(wsRes.error());
    assertTrue(text(wsRes).contains("No LSP server configured"));
  }

  private Path file(String relative) {
    return workspaceRoot.resolve(relative);
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
    return TestCodingConfig.withLimits(workspaceRoot, lines, bytes);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = invokeAsync(capability, arguments, Duration.ZERO);
    assertTrue(listener.await());
    return listener.result;
  }

  private RecordingListener invokeAsync(
      EnvironmentCapability capability, String arguments, Duration timeout) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(), new EnvironmentCapabilityCall("edge", arguments), timeout),
            listener);
    return listener;
  }

  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private static String text(EnvironmentCapabilityResult result) {
    return text(result.contents());
  }

  private static String text(List<ResultContent> contents) {
    return contents.stream().map(CodingCapabilitiesEdgeTest::text).reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent value ? value.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    private volatile EnvironmentCapabilityResult result;
    private volatile EnvironmentCapabilityExecutionHandle handle;
    private volatile int completions;

    @Override
    public synchronized void onPartial(EnvironmentCapabilityResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completions++;
      done.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }

    private boolean await() throws InterruptedException {
      return done.await(5, TimeUnit.SECONDS);
    }
  }
}
