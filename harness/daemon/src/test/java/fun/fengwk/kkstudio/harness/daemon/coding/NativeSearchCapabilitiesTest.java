package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

class NativeSearchCapabilitiesTest {

  @TempDir Path workspaceRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void closeExecutor() {
    executor.shutdownNow();
  }

  /** 根与嵌套 ignore 文件共同覆盖锚定、目录、double-star、转义、否定和父目录剪枝；规则来自测试资源 fixture。 */
  @Test
  void respectsHierarchicalGitignoreRulesAndHardExcludesGitMetadata() throws Exception {
    write(".gitignore", fixture("hierarchy.gitignore"));
    write("visible.txt", "needle\n");
    write(".hidden.txt", "needle\n");
    write("#literal.txt", "needle\n");
    write("!literal.txt", "needle\n");
    write("root-only.txt", "needle\n");
    write("nested/root-only.txt", "needle\n");
    write("ignored-dir/file.txt", "needle\n");
    write("blocked/.gitignore", "!keep.txt\n");
    write("blocked/keep.txt", "needle\n");
    write("logs/debug1.log", "needle\n");
    write("logs/deep/debug2.log", "needle\n");
    write("logs/deep/keep.log", "needle\n");
    write("nested/.gitignore", "*.tmp\n!important.tmp\ncache/\n");
    write("nested/drop.tmp", "needle\n");
    write("nested/important.tmp", "needle\n");
    write("nested/cache/file.txt", "needle\n");
    write("nested/path-dir/file.txt", "needle\n");
    write(".pi/git/.gitignore", "!.gitignore\n");
    write(".pi/git/generated.txt", "needle\n");
    write(".git/config", "needle\n");

    EnvironmentCapabilityResult found =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    String findText = text(found);
    List<String> findLines = List.of(findText.split("\n"));
    assertTrue(findText.contains(".hidden.txt"));
    assertTrue(findText.contains(".pi/git/.gitignore"));
    assertTrue(findText.contains("nested/important.tmp"));
    assertTrue(findText.contains("nested/root-only.txt"));
    assertFalse(findText.contains("#literal.txt"));
    assertFalse(findText.contains("!literal.txt"));
    assertFalse(findLines.contains("root-only.txt"));
    assertFalse(findText.contains("ignored-dir/file.txt"));
    assertFalse(findText.contains("blocked/keep.txt"));
    assertFalse(findText.contains("debug1.log"));
    assertFalse(findText.contains("debug2.log"));
    assertFalse(findText.contains("nested/drop.tmp"));
    assertFalse(findText.contains("nested/cache/file.txt"));
    assertFalse(findText.contains("nested/path-dir/file.txt"));
    assertFalse(findText.contains(".pi/git/generated.txt"));
    assertFalse(findText.contains(".git/config"));

    EnvironmentCapabilityResult grepped =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    String grepText = text(grepped);
    assertTrue(grepText.contains(".hidden.txt:1:needle"));
    assertTrue(grepText.contains("logs/deep/keep.log:1:needle"));
    assertTrue(grepText.contains("nested/important.tmp:1:needle"));
    assertFalse(grepText.contains("ignored-dir"));
    assertFalse(grepText.contains("blocked"));
    assertFalse(grepText.contains("generated.txt"));
  }

  /** basename 否定只作用于当前节点，不能覆盖后代文件自己的 ignore 匹配。 */
  @Test
  void basenameNegationDoesNotReincludeIgnoredDescendantFiles() throws Exception {
    write(".gitignore", "*.log\n!foo\n");
    write("foo/bar.log", "ignored\n");
    write("foo/keep.txt", "visible\n");

    EnvironmentCapabilityResult negatedDirectory =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(text(negatedDirectory).contains("foo/keep.txt"));
    assertFalse(text(negatedDirectory).contains("foo/bar.log"));

    Files.writeString(workspaceRoot.resolve(".gitignore"), "foo\n");
    EnvironmentCapabilityResult ignoredDirectory =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertFalse(text(ignoredDirectory).contains("foo/keep.txt"));
    assertFalse(text(ignoredDirectory).contains("foo/bar.log"));
  }

  /** find 必须区分 basename/full-path glob，只返回文件，并固定 workdir 相对顺序。 */
  @Test
  void findMatchesBasenamesAndSearchRelativePathsInDeterministicOrder() throws Exception {
    write("search/z.ts", "z");
    write("search/a.ts", "a");
    write("search/nested/b.ts", "b");
    write("search/nested/c.md", "c");
    write("search/.hidden.ts", "hidden");
    Files.createSymbolicLink(
        workspaceRoot.resolve("search/link.ts"), workspaceRoot.resolve("search/a.ts"));
    Files.createSymbolicLink(workspaceRoot.resolve("search-link"), workspaceRoot.resolve("search"));

    EnvironmentCapabilityResult basenames =
        invoke(
            find(config()),
            "{\"pattern\":\"*.ts\",\"path\":\"search\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertEquals(
        "search/.hidden.ts\nsearch/a.ts\nsearch/nested/b.ts\nsearch/z.ts", text(basenames));
    assertFalse(text(basenames).contains("link.ts"));

    EnvironmentCapabilityResult fullPath =
        invoke(
            find(config()),
            "{\"pattern\":\"nested/*.ts\",\"path\":\"search\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertEquals("search/nested/b.ts", text(fullPath));

    EnvironmentCapabilityResult doubleStar =
        invoke(
            find(config()),
            "{\"pattern\":\"**/*.ts\",\"path\":\"search\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertEquals(
        "search/.hidden.ts\nsearch/a.ts\nsearch/nested/b.ts\nsearch/z.ts", text(doubleStar));

    EnvironmentCapabilityResult characterClass =
        invoke(
            find(config()),
            "{\"pattern\":\"[ab].ts\",\"path\":\"search\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertEquals("search/a.ts\nsearch/nested/b.ts", text(characterClass));
    assertTrue(
        text(invoke(
                find(config()),
                "{\"pattern\":\"*\",\"path\":\"search/a.ts\",\"workdir\":"
                    + json(workspaceRoot.toString())
                    + "}"))
            .contains("path must be a directory"));
    // 参数路径上的符号链接不再被拒绝：解析为真实路径后照常检索，遍历仍不跟随内部符号链接。
    assertEquals(
        "search/.hidden.ts\nsearch/a.ts\nsearch/nested/b.ts\nsearch/z.ts",
        text(
            invoke(
                find(config()),
                "{\"pattern\":\"*.ts\",\"path\":\"search-link\",\"workdir\":"
                    + json(workspaceRoot.toString())
                    + "}")));
    assertEquals(
        "search/a.ts:1:a",
        text(
            invoke(
                grep(config()),
                "{\"pattern\":\"a\",\"path\":\"search/link.ts\",\"workdir\":"
                    + json(workspaceRoot.toString())
                    + "}")));
  }

  /** grep 的 literal/regex/ignore-case/include 组合必须保持逐行去重。 */
  @Test
  void grepSupportsLiteralRegexIgnoreCaseAndIncludeWithoutDuplicateLines() throws Exception {
    write("a.txt", "Alpha foo\nalpha.foo alpha.foo\n");
    write("nested/c.txt", "ALPHA.FOO\n");
    write("b.md", "alpha.foo\n");

    EnvironmentCapabilityResult literal =
        invoke(
            grep(config()),
            "{\"pattern\":\"alpha.foo\",\"path\":\".\",\"literal\":true,"
                + "\"ignore_case\":true,\"include\":\"**/*.txt\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertEquals("a.txt:2:alpha.foo alpha.foo\nnested/c.txt:1:ALPHA.FOO", text(literal));

    EnvironmentCapabilityResult regex =
        invoke(
            grep(config()),
            "{\"pattern\":\"^Alpha\\\\s+foo$\",\"path\":\"a.txt\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertEquals("a.txt:1:Alpha foo", text(regex));

    EnvironmentCapabilityResult once =
        invoke(
            grep(config()),
            "{\"pattern\":\"alpha\\\\.foo\",\"path\":\"a.txt\",\"ignore_case\":true,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertEquals("a.txt:2:alpha.foo alpha.foo", text(once));
  }

  /** multiline 的两个跨行命中共享覆盖行时，该行仍只输出一次。 */
  @Test
  void grepMultilineReportsCoveredLinesOnce() throws Exception {
    write("multi.txt", "zero\nalpha\nbeta gamma\ndelta\n");

    EnvironmentCapabilityResult result =
        invoke(
            grep(config()),
            "{\"pattern\":\"alpha\\nbeta|gamma\\ndelta\",\"path\":\"multi.txt\","
                + "\"multiline\":true,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    assertEquals("multi.txt:2:alpha\nmulti.txt:3:beta gamma\nmulti.txt:4:delta", text(result));
  }

  /** 直接二进制目标是清晰错误；目录扫描跳过二进制并继续返回文本匹配。 */
  @Test
  void grepRejectsDirectBinarySkipsDirectoryBinaryAndReportsInvalidRegex() throws Exception {
    write(".gitignore", "binary.bin\n");
    write("text.txt", "needle\n");
    Files.write(workspaceRoot.resolve("binary.bin"), new byte[] {'n', 0, 'e'});

    EnvironmentCapabilityResult direct =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\"binary.bin\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(direct.error());
    assertTrue(text(direct).contains("file appears to be binary"));

    EnvironmentCapabilityResult directory =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertFalse(directory.error());
    assertEquals("text.txt:1:needle", text(directory));

    EnvironmentCapabilityResult invalid =
        invoke(
            grep(config()),
            "{\"pattern\":\"[\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(invalid.error());
    assertTrue(text(invalid).contains("Invalid regex"));
  }

  /** glob 转换覆盖 separator 边界、double-star、问号、字符类与转义字面量。 */
  @Test
  void globPatternsArePlatformIndependentAndSegmentAware() {
    assertTrue(GlobPattern.compile("**/*.java").matches("Main.java"));
    assertTrue(GlobPattern.compile("**/*.java").matches("src/main/Main.java"));
    assertTrue(GlobPattern.compile("a/**/b").matches("a/b"));
    assertTrue(GlobPattern.compile("a/**/b").matches("a/deep/nested/b"));
    assertTrue(GlobPattern.compile("foo/**").matches("foo/child"));
    assertTrue(GlobPattern.compile("foo/**").matches("foo/deep/child"));
    assertTrue(GlobPattern.compile("a/**/b?.[ch]").matches("a/deep/b1.c"));
    assertTrue(GlobPattern.compile("[!a].txt").matches("b.txt"));
    assertTrue(GlobPattern.compile("[]].txt").matches("].txt"));
    assertTrue(GlobPattern.compile("\\*.txt").matches("*.txt"));
    assertTrue(GlobPattern.compile("[.txt").matches("[.txt"));
    assertTrue(GlobPattern.compile("[[]").matches("["));
    assertTrue(GlobPattern.compile("[a\\]]").matches("]"));
    assertTrue(GlobPattern.compile("[\\d].txt").matches("d.txt"));
    assertTrue(GlobPattern.compile("a**b").matches("ab"));
    assertTrue(GlobPattern.compile("a**b").matches("axxb"));
    assertTrue(GlobPattern.compile("**.java").matches("Main.java"));
    assertTrue(GlobPattern.compile("foo**bar").matches("foo-middle-bar"));
    assertTrue(GlobPattern.compile("abc\\").matches("abc\\"));
    assertFalse(GlobPattern.compile("*.java").matches("src/Main.java"));
    assertFalse(GlobPattern.compile("a/**/b?.[ch]").matches("a/deep/b12.c"));
    assertFalse(GlobPattern.compile("[!a].txt").matches("a.txt"));
    assertFalse(GlobPattern.compile("[\\d].txt").matches("1.txt"));
    assertFalse(GlobPattern.compile("a**b").matches("ax/yb"));
    assertFalse(GlobPattern.compile("**.java").matches("src/Main.java"));
    assertFalse(GlobPattern.compile("foo**bar").matches("foo/deep/bar"));
  }

  /** 验证 limit 与 500 code-point 行截断保持有界内联文本，不附加无意义完整 resource。 */
  @Test
  void searchLimitsStayInlineAndTruncateLongLines() throws Exception {
    String longLine = "😀".repeat(600);
    write("a.txt", longLine + "\n");
    write("b.txt", "needle\n");
    write("c.txt", "needle\n");
    GrepCapability grep = grep(config(2000, 50 * 1024));

    EnvironmentCapabilityResult grepResult =
        invoke(
            grep,
            "{\"pattern\":\".+\",\"path\":\".\",\"include\":\"*.txt\",\"limit\":1,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(text(grepResult).contains("line truncated to 500 chars"));
    assertTrue(text(grepResult).contains("1 results limit reached"));
    assertFalse(grepResult.contents().stream().anyMatch(ResourceResultContent.class::isInstance));

    EnvironmentCapabilityResult findResult =
        invoke(
            find(config(2000, 50 * 1024)),
            "{\"pattern\":\"*.txt\",\"path\":\".\",\"limit\":1,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(text(findResult).contains("1 results limit reached"));
    assertFalse(findResult.contents().stream().anyMatch(ResourceResultContent.class::isInstance));
  }

  /** 搜索控制器使用可控时钟验证 deadline，并用 cancellation supplier 验证主动取消检查。 */
  @Test
  void searchControlActivelyChecksTimeoutAndCancellation() {
    AtomicLong clock = new AtomicLong();
    SearchControl timeout = new SearchControl(Duration.ofNanos(5), () -> false, "grep", clock::get);
    clock.set(5);
    IllegalArgumentException timedOut =
        assertThrows(IllegalArgumentException.class, timeout::check);
    assertEquals("grep timed out after 0 milliseconds", timedOut.getMessage());

    SearchControl cancelled =
        new SearchControl(Duration.ofSeconds(1), () -> true, "find", System::nanoTime);
    assertThrows(InterruptedException.class, cancelled::check);
  }

  /**
   * timeout 为 0 表示没有 execution deadline：即使时钟前进任意长，{@link SearchControl#check()} 也绝不触发超时终态，只保留取消检查。
   */
  @Test
  void searchControlTreatsZeroTimeoutAsNoDeadline() {
    AtomicLong clock = new AtomicLong();
    SearchControl noDeadline = new SearchControl(Duration.ZERO, () -> false, "grep", clock::get);
    clock.set(Long.MAX_VALUE);
    assertDoesNotThrow(noDeadline::check);

    // 没有 deadline 不代表没有取消：取消仍必须在同一次检查中被观察到。
    SearchControl cancelledNoDeadline =
        new SearchControl(Duration.ZERO, () -> true, "find", clock::get);
    assertThrows(InterruptedException.class, cancelledNoDeadline::check);

    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchControl(Duration.ofSeconds(-1), () -> false, "grep", clock::get));
  }

  /** 单行模式必须匹配完整行内容：65536 字符之后才出现的匹配也要真实命中，不能被“只匹配前缀”静默变成无匹配（旧实现按 64Ki 截断）。 */
  @Test
  void grepFindsMatchInSecondHalfOfOverLongLine() throws Exception {
    String line = "a".repeat(70_000) + "NEEDLE_TAIL";
    write("long.txt", line + "\n");

    EnvironmentCapabilityResult result =
        invoke(
            grep(config()),
            "{\"pattern\":\"NEEDLE_TAIL\",\"path\":\"long.txt\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    assertFalse(result.error(), text(result));
    assertTrue(text(result).contains("long.txt:1:"), text(result));
    assertTrue(text(result).contains("NEEDLE_TAIL"), text(result));
  }

  /**
   * 超出单行保留上限（{@link TextStreams#MAX_LINE_CHARS}）时无法完整匹配：单文件必须显式报错，目录扫描必须记为“未搜索”， 绝不能在存在未搜索文件时返回无匹配。
   */
  @Test
  void grepFailsExplicitlyWhenSingleLineExceedsRetentionLimit() throws Exception {
    write("huge.txt", "a".repeat(TextStreams.MAX_LINE_CHARS) + "NEEDLE_TAIL");

    EnvironmentCapabilityResult direct =
        invoke(
            grep(config()),
            "{\"pattern\":\"NEEDLE_TAIL\",\"path\":\"huge.txt\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(direct.error(), text(direct));
    assertTrue(text(direct).contains("line longer than"), text(direct));
    assertTrue(text(direct).contains("cannot be searched completely"), text(direct));

    EnvironmentCapabilityResult noMatch =
        invoke(
            grep(config()),
            "{\"pattern\":\"NEEDLE_TAIL\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(noMatch.error(), text(noMatch));
    assertTrue(text(noMatch).contains("could not be searched"), text(noMatch));
    assertTrue(text(noMatch).contains("huge.txt"), text(noMatch));

    // 存在其他命中时仍返回命中，但必须显式提示有文件未被搜索。
    write("visible.txt", "NEEDLE_TAIL\n");
    EnvironmentCapabilityResult partial =
        invoke(
            grep(config()),
            "{\"pattern\":\"NEEDLE_TAIL\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertFalse(partial.error(), text(partial));
    assertTrue(text(partial).contains("visible.txt:1:NEEDLE_TAIL"), text(partial));
    assertTrue(text(partial).contains("could not be searched"), text(partial));
  }

  /** 多行模式超出显式字节上界时必须显式失败（单文件报错、目录记为未搜索），绝不静默跳过。 */
  @Test
  void grepMultilineFailsExplicitlyAboveByteLimit() throws Exception {
    Path huge = workspaceRoot.resolve("huge-multiline.txt");
    try (SeekableByteChannel channel =
        Files.newByteChannel(
            huge,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      channel.position(64L * 1024 * 1024);
      channel.write(ByteBuffer.wrap(new byte[] {'a'}));
    }
    write("visible.txt", "needle\n");

    EnvironmentCapabilityResult direct =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\"huge-multiline.txt\",\"multiline\":true,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(direct.error(), text(direct));
    assertTrue(text(direct).contains("64 MiB maximum for multiline"), text(direct));

    EnvironmentCapabilityResult partial =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":\".\",\"multiline\":true,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertFalse(partial.error(), text(partial));
    assertTrue(text(partial).contains("visible.txt:1:needle"), text(partial));
    assertTrue(text(partial).contains("could not be searched"), text(partial));
  }

  /**
   * 正则输入守卫必须在扫描中途生效：时钟在读取若干字符后越过 deadline 时，{@link SearchControl#deadlineChecked} 主动中断整个扫描，
   * 而不是等调用方读完输入后才检查。
   */
  @Test
  void deadlineCheckedSequenceEnforcesTimeoutMidScan() {
    AtomicInteger clockReads = new AtomicInteger();
    LongSupplier clock = () -> clockReads.getAndIncrement() < 3 ? 0L : 1_000L;
    SearchControl control = new SearchControl(Duration.ofNanos(100), () -> false, "grep", clock);
    CharSequence guarded = control.deadlineChecked("x".repeat(1_000_000));
    AtomicInteger consumed = new AtomicInteger();

    IllegalArgumentException timedOut =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              for (int index = 0; index < guarded.length(); index++) {
                guarded.charAt(index);
                consumed.incrementAndGet();
              }
            });

    assertTrue(timedOut.getMessage().contains("grep timed out"), timedOut.getMessage());
    assertTrue(consumed.get() < guarded.length(), "deadline 必须在整段输入读完前生效，而不是扫描结束后");
  }

  /** 取消同样能在扫描中途生效：守卫把取消还原为 {@link InterruptedException} 语义，供能力层报告 Operation cancelled。 */
  @Test
  void deadlineCheckedSequenceSurfacesCancellationAsInterruption() {
    SearchControl control = new SearchControl(Duration.ZERO, () -> true, "grep", System::nanoTime);
    CharSequence guarded = control.deadlineChecked("x".repeat(16));

    SearchControl.CancelledException cancelled =
        assertThrows(SearchControl.CancelledException.class, () -> guarded.charAt(0));

    assertInstanceOf(InterruptedException.class, cancelled.interruption());
  }

  /** 多行正则扫描的时间预算回归：对无法快速完成的大输入，显式短 deadline 必须收敛为明确的超时错误，而不是长时间占用或误报无匹配。 */
  @Test
  void grepMultilineTimesOutDuringWholeFileScan() throws Exception {
    write("big.txt", "a".repeat(32 * 1024 * 1024));

    EnvironmentCapabilityResult timed =
        invoke(
            grep(config()),
            "{\"pattern\":\"z\",\"path\":\"big.txt\",\"multiline\":true,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}",
            Duration.ofMillis(1));

    assertTrue(timed.error(), text(timed));
    assertTrue(text(timed).contains("grep timed out"), text(timed));
  }

  /** 单行流式扫描同样逐行检查 deadline：大量行上的显式短 deadline 必须收敛为明确的超时错误。 */
  @Test
  void grepSingleLineTimesOutWhileScanningManyLines() throws Exception {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < 1_000_000; index++) {
      builder.append("line ").append(index).append('\n');
    }
    write("many-lines.txt", builder.toString());

    EnvironmentCapabilityResult timed =
        invoke(
            grep(config()),
            "{\"pattern\":\"z\",\"path\":\"many-lines.txt\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}",
            Duration.ofMillis(1));

    assertTrue(timed.error(), text(timed));
    assertTrue(text(timed).contains("grep timed out"), text(timed));
  }

  /** find 也消费显式 deadline：极短 deadline 必须报超时，而不是静默完成或误导为无匹配。 */
  @Test
  void findTimesOutOnTinyDeadline() throws Exception {
    write("visible.txt", "needle\n");

    EnvironmentCapabilityResult timed =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":" + json(workspaceRoot.toString()) + "}",
            Duration.ofNanos(1));

    assertTrue(timed.error(), text(timed));
    assertTrue(text(timed).contains("find timed out"), text(timed));
  }

  /** 无法读取的目录必须作为“未搜索”显式上报，不能等同于“目录为空”。 */
  @Test
  void searchReportsUnreadableDirectoryInsteadOfSilentlySkipping() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    write("unreadable/hidden.txt", "needle\n");
    write("visible.txt", "needle\n");
    Path unreadable = workspaceRoot.resolve("unreadable");
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(unreadable);
    try {
      Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));

      EnvironmentCapabilityResult grepped =
          invoke(
              grep(config()),
              "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                  + json(workspaceRoot.toString())
                  + "}");
      assertFalse(grepped.error(), text(grepped));
      assertTrue(text(grepped).contains("visible.txt:1:needle"), text(grepped));
      assertTrue(text(grepped).contains("could not be searched"), text(grepped));

      EnvironmentCapabilityResult found =
          invoke(
              find(config()),
              "{\"pattern\":\"*.txt\",\"path\":\".\",\"workdir\":"
                  + json(workspaceRoot.toString())
                  + "}");
      assertFalse(found.error(), text(found));
      assertTrue(text(found).contains("visible.txt"), text(found));
      assertTrue(text(found).contains("could not be searched"), text(found));
    } finally {
      Files.setPosixFilePermissions(unreadable, original);
    }
  }

  /** 结果路径是路径协议：文件名尾部空格必须原样保留，不能被 trim 成另一个不存在的目标。 */
  @Test
  void findPreservesTrailingSpaceInMatchedFileName() throws Exception {
    Files.writeString(workspaceRoot.resolve("trailing "), "content");

    EnvironmentCapabilityResult result =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\".\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    assertEquals("trailing ", text(result));
  }

  /** 不存在的检索路径必须是明确错误，不能回退到任何默认目录或上游工具。 */
  @Test
  void searchRejectsMissingPath() throws Exception {
    EnvironmentCapabilityResult found =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":\"missing-dir\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(found.error(), text(found));
    assertTrue(text(found).contains("path does not exist"), text(found));

    EnvironmentCapabilityResult grepped =
        invoke(
            grep(config()),
            "{\"pattern\":\"x\",\"path\":\"missing.txt\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(grepped.error(), text(grepped));
    assertTrue(text(grepped).contains("path does not exist"), text(grepped));
  }

  /** 省略 workdir 时绝对 path 仍可直接检索，命中行以绝对路径展示：既不允许回退到 cwd、HOME 或任何默认目录，也不允许把结果伪装成相对路径。 */
  @Test
  void searchAcceptsAbsolutePathWithoutWorkdirAndReportsAbsolutePaths() throws Exception {
    write("root/nested/hit.txt", "needle\n");
    String realRoot = workspaceRoot.toRealPath().toString().replace('\\', '/');
    String absoluteFile = json(workspaceRoot.resolve("root/nested/hit.txt").toString());

    EnvironmentCapabilityResult grepped =
        invoke(grep(config()), "{\"pattern\":\"needle\",\"path\":" + absoluteFile + "}");
    assertFalse(grepped.error(), text(grepped));
    assertEquals(realRoot + "/root/nested/hit.txt:1:needle", text(grepped));

    EnvironmentCapabilityResult greppedDirectory =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":"
                + json(workspaceRoot.resolve("root").toString())
                + "}");
    assertFalse(greppedDirectory.error(), text(greppedDirectory));
    assertEquals(realRoot + "/root/nested/hit.txt:1:needle", text(greppedDirectory));

    EnvironmentCapabilityResult found =
        invoke(
            find(config()),
            "{\"pattern\":\"*.txt\",\"path\":"
                + json(workspaceRoot.resolve("root").toString())
                + "}");
    assertFalse(found.error(), text(found));
    assertEquals(realRoot + "/root/nested/hit.txt", text(found));
  }

  /** 相对 path 省略 workdir 必须在执行期被拒绝：没有任何默认目录可以替代显式 workdir。 */
  @Test
  void searchRejectsRelativePathWithoutWorkdir() throws Exception {
    write("local.txt", "needle\n");

    for (EnvironmentCapability capability :
        new EnvironmentCapability[] {grep(config()), find(config())}) {
      EnvironmentCapabilityResult result =
          invoke(capability, "{\"pattern\":\"needle\",\"path\":\"local.txt\"}");

      assertTrue(result.error(), text(result));
      assertTrue(text(result).contains("workdir is required"), text(result));
      assertFalse(text(result).contains("local.txt:1"), text(result));
    }
  }

  /** 给了 workdir 就必须仍是现存可读的绝对目录：即使 path 是绝对路径也不能豁免 workdir 校验。 */
  @Test
  void searchStillValidatesExplicitWorkdirForAbsolutePaths() throws Exception {
    write("keep.txt", "needle\n");
    String absoluteFile = json(workspaceRoot.resolve("keep.txt").toString());

    EnvironmentCapabilityResult relativeWorkdir =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":" + absoluteFile + ",\"workdir\":\"relative/dir\"}");
    assertTrue(relativeWorkdir.error(), text(relativeWorkdir));
    assertTrue(text(relativeWorkdir).contains("workdir"), text(relativeWorkdir));

    EnvironmentCapabilityResult missingWorkdir =
        invoke(
            find(config()),
            "{\"pattern\":\"*\",\"path\":"
                + absoluteFile
                + ",\"workdir\":"
                + json(workspaceRoot.resolve("missing-workdir").toString())
                + "}");
    assertTrue(missingWorkdir.error(), text(missingWorkdir));
    assertTrue(text(missingWorkdir).contains("workdir"), text(missingWorkdir));
    assertFalse(Files.exists(workspaceRoot.resolve("missing-workdir")), "不得自动创建 workdir");
  }

  /** 同一个绝对 path 给了 workdir 时保持原有相对展示，省略 workdir 时切换为绝对展示，两种形态不互相污染。 */
  @Test
  void explicitWorkdirKeepsRelativeDisplayForTheSameAbsolutePath() throws Exception {
    write("root/keep.txt", "needle\n");
    String realRoot = workspaceRoot.toRealPath().toString().replace('\\', '/');
    String absoluteFile = json(workspaceRoot.resolve("root/keep.txt").toString());

    EnvironmentCapabilityResult withWorkdir =
        invoke(
            grep(config()),
            "{\"pattern\":\"needle\",\"path\":"
                + absoluteFile
                + ",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    EnvironmentCapabilityResult withoutWorkdir =
        invoke(grep(config()), "{\"pattern\":\"needle\",\"path\":" + absoluteFile + "}");

    assertEquals("root/keep.txt:1:needle", text(withWorkdir));
    assertEquals(realRoot + "/root/keep.txt:1:needle", text(withoutWorkdir));
  }

  /** 设备等非普通文件必须在 I/O 前被拒绝，而不是当作可搜索文本读取。 */
  @Test
  void grepRejectsSpecialFilesBeforeReading() throws Exception {
    Path device = Path.of("/dev/null");
    assumeTrue(Files.exists(device), "需要 POSIX 特殊文件 /dev/null");

    EnvironmentCapabilityResult result =
        invoke(
            grep(config()),
            "{\"pattern\":\"x\",\"path\":"
                + json(device.toString())
                + ",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    assertTrue(result.error(), text(result));
    assertTrue(text(result).contains("regular file or directory"), text(result));
  }

  /** 作为检索起点的不可读目录必须明确报错（不得被当成空目录）。 */
  @Test
  void searchRejectsUnreadableSearchRoot() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path locked = Files.createDirectories(workspaceRoot.resolve("locked-root"));
    Files.writeString(locked.resolve("hidden.txt"), "needle\n");
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
    try {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));

      EnvironmentCapabilityResult found =
          invoke(
              find(config()),
              "{\"pattern\":\"*\",\"path\":\"locked-root\",\"workdir\":"
                  + json(workspaceRoot.toString())
                  + "}");
      assertTrue(found.error(), text(found));
      assertTrue(text(found).contains("path is not readable"), text(found));
    } finally {
      Files.setPosixFilePermissions(locked, original);
    }
  }

  /** 单文件不可读是明确错误，不是“无匹配”。 */
  @Test
  void grepReportsUnreadableDirectFile() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Path secret = Files.writeString(workspaceRoot.resolve("secret.txt"), "needle\n");
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(secret);
    try {
      Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("---------"));

      EnvironmentCapabilityResult result =
          invoke(
              grep(config()),
              "{\"pattern\":\"needle\",\"path\":\"secret.txt\",\"workdir\":"
                  + json(workspaceRoot.toString())
                  + "}");

      assertTrue(result.error(), text(result));
      assertTrue(text(result).contains("path is not readable"), text(result));
    } finally {
      Files.setPosixFilePermissions(secret, original);
    }
  }

  /** 二进制判定必须覆盖探测前缀之后的内容：前缀内正常、后续含 NUL 仍是明确错误。 */
  @Test
  void grepDetectsBinaryContentBeyondProbePrefix() throws Exception {
    byte[] content = new byte[10_000];
    Arrays.fill(content, (byte) 'a');
    content[9_000] = 0;
    Files.write(workspaceRoot.resolve("late-binary.bin"), content);

    EnvironmentCapabilityResult result =
        invoke(
            grep(config()),
            "{\"pattern\":\"a\",\"path\":\"late-binary.bin\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    assertTrue(result.error(), text(result));
    assertTrue(text(result).contains("appears to be binary"), text(result));
  }

  /**
   * 前缀探测无控制字符、但整体不是合法 UTF-8 的文本（如 GBK 字节）也必须显式失败：编码只支持 UTF-8 与 BOM 判定的 UTF-16，严格解码失败按二进制报错，
   * 绝不允许静默漏掉后半段内容的命中。
   */
  @Test
  void grepRejectsInvalidTextEncodingInsteadOfMissingMatches() throws Exception {
    // “中文 beta” 的 GBK 字节不含任何控制字符，因此只能由流式严格解码而非前缀探测判定。
    Files.write(workspaceRoot.resolve("legacy.txt"), "中文 beta\n".getBytes(Charset.forName("GBK")));

    EnvironmentCapabilityResult result =
        invoke(
            grep(config()),
            "{\"pattern\":\"beta\",\"path\":\"legacy.txt\",\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    assertTrue(result.error(), text(result));
    assertTrue(text(result).contains("appears to be binary"), text(result));
  }

  /** 多行模式对空文件返回无匹配，对二进制文件返回明确错误。 */
  @Test
  void grepMultilineHandlesEmptyAndBinaryFiles() throws Exception {
    Files.write(workspaceRoot.resolve("empty.txt"), new byte[0]);
    EnvironmentCapabilityResult empty =
        invoke(
            grep(config()),
            "{\"pattern\":\"a\",\"path\":\"empty.txt\",\"multiline\":true,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertFalse(empty.error(), text(empty));
    assertEquals("No matches found", text(empty));

    Files.write(workspaceRoot.resolve("binary.bin"), new byte[] {'n', 0, 'e'});
    EnvironmentCapabilityResult binary =
        invoke(
            grep(config()),
            "{\"pattern\":\"n\",\"path\":\"binary.bin\",\"multiline\":true,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");
    assertTrue(binary.error(), text(binary));
    assertTrue(text(binary).contains("appears to be binary"), text(binary));
  }

  /** 多行模式同样遵守 limit，但 limit 约束的是命中数：同一命中的覆盖行必须整体输出，不会在中间被截断。 */
  @Test
  void grepMultilineHonorsLimit() throws Exception {
    write("multi.txt", "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\n");

    EnvironmentCapabilityResult result =
        invoke(
            grep(config()),
            "{\"pattern\":\"alpha\\\\nbeta|gamma\\\\ndelta|epsilon\\\\nzeta\","
                + "\"path\":\"multi.txt\",\"multiline\":true,\"limit\":1,\"workdir\":"
                + json(workspaceRoot.toString())
                + "}");

    String text = text(result);
    assertTrue(text.contains("multi.txt:1:alpha"), text);
    assertTrue(text.contains("multi.txt:2:beta"), text);
    assertTrue(text.contains("1 results limit reached"), text);
    assertFalse(text.contains("epsilon"), text);
  }

  /** 未搜索路径在提示中逐条列出，并超过上限时省略后续条目。 */
  @Test
  void grepEnumeratesAndBoundsUnsearchedPaths() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    write("visible.txt", "needle\n");
    List<Path> locked = new ArrayList<>();
    List<Set<PosixFilePermission>> originalPermissions = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      Path file = Files.writeString(workspaceRoot.resolve("locked-" + index + ".txt"), "needle\n");
      originalPermissions.add(Files.getPosixFilePermissions(file));
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
      locked.add(file);
    }
    try {
      EnvironmentCapabilityResult result =
          invoke(
              grep(config()),
              "{\"pattern\":\"needle\",\"path\":\".\",\"workdir\":"
                  + json(workspaceRoot.toString())
                  + "}");

      String text = text(result);
      assertFalse(result.error(), text);
      assertTrue(text.contains("visible.txt:1:needle"), text);
      assertTrue(text.contains("12 path(s) could not be searched"), text);
      assertTrue(text.contains("locked-0.txt (could not be read)"), text);
      assertTrue(text.contains(", locked-1.txt (could not be read)"), text);
      assertTrue(text.endsWith("...]"), text);
    } finally {
      for (int index = 0; index < locked.size(); index++) {
        Files.setPosixFilePermissions(locked.get(index), originalPermissions.get(index));
      }
    }
  }

  /** find 在没有匹配但存在未搜索路径时必须报错并逐条列出、超限省略，绝不伪装成空结果。 */
  @Test
  void findReportsUnsearchedPathsWhenNothingMatches() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    List<Path> locked = new ArrayList<>();
    List<Set<PosixFilePermission>> originalPermissions = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      Path directory = Files.createDirectories(workspaceRoot.resolve("locked-dir-" + index));
      Files.writeString(directory.resolve("hidden.txt"), "content");
      originalPermissions.add(Files.getPosixFilePermissions(directory));
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("---------"));
      locked.add(directory);
    }
    try {
      EnvironmentCapabilityResult result =
          invoke(
              find(config()),
              "{\"pattern\":\"*.nomatch\",\"path\":\".\",\"workdir\":"
                  + json(workspaceRoot.toString())
                  + "}");

      String text = text(result);
      assertTrue(result.error(), text);
      assertTrue(text.contains("No files found matching pattern"), text);
      assertTrue(text.contains("12 path(s) could not be searched"), text);
      assertTrue(text.contains("locked-dir-0 (could not be read)"), text);
      assertTrue(text.contains(", locked-dir-1 (could not be read)"), text);
      assertTrue(text.endsWith("...]"), text);
    } finally {
      for (int index = 0; index < locked.size(); index++) {
        Files.setPosixFilePermissions(locked.get(index), originalPermissions.get(index));
      }
    }
  }

  private CodingToolsConfig config() {
    return config(2000, 50 * 1024);
  }

  private GrepCapability grep(CodingToolsConfig config) {
    return new GrepCapability(config, executor);
  }

  private FindCapability find(CodingToolsConfig config) {
    return new FindCapability(config, executor);
  }

  private CodingToolsConfig config(int lines, int bytes) {
    return TestCodingConfig.withLimits(workspaceRoot, lines, bytes);
  }

  private void write(String relative, String content) throws Exception {
    Path path = workspaceRoot.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  /** 以 JSON 字符串字面量表示任意本地路径，避免手工拼接转义。 */
  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    return invoke(capability, arguments, Duration.ZERO);
  }

  /** 允许显式 deadline 的调用入口：用于验证搜索对 request.timeout 的消费。 */
  private EnvironmentCapabilityResult invoke(
      EnvironmentCapability capability, String arguments, Duration timeout) throws Exception {
    RecordingListener listener = new RecordingListener();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("native-search", arguments),
            timeout),
        listener);
    assertTrue(listener.completed.await(30, TimeUnit.SECONDS));
    return listener.result;
  }

  /** 读取测试资源中的结构化 fixture（按包结构组织在 {@code search/} 下）。 */
  private static String fixture(String name) throws Exception {
    try (InputStream input =
        NativeSearchCapabilitiesTest.class.getResourceAsStream("search/" + name)) {
      assertNotNull(input, "missing test fixture: " + name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream()
        .map(NativeSearchCapabilitiesTest::text)
        .reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent value ? value.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {}

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }
  }
}
