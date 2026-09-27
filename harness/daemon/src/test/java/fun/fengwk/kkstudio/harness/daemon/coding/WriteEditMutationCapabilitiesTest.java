package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * {@link WriteCapability} 与 {@link EditCapability} 的行为契约测试。
 *
 * <p>用例来源与改写理由记录在 docs/operations/builtin-mutation-tests.md：写入按调用内容原样落盘、编码/BOM 无损保留、
 * 原子替换继承既有文件权限、非普通文件在任何 I/O 之前拒绝，以及 edit 的精确替换、行尾保留与错误语义。
 */
class WriteEditMutationCapabilitiesTest {

  @TempDir Path workdir;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private CodingToolsConfig config() {
    return TestCodingConfig.withLsp(workdir);
  }

  private WriteCapability write() {
    return new WriteCapability(config(), executor);
  }

  private EditCapability edit() {
    return new EditCapability(config(), executor);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String json(String value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private EnvironmentCapabilityExecutionRequest request(
      EnvironmentCapability capability, String arguments) {
    return new EnvironmentCapabilityExecutionRequest(
        capability.descriptor(),
        new EnvironmentCapabilityCall("call", arguments),
        Duration.ofSeconds(30));
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    capability.execute(request(capability, arguments), listener);
    assertTrue(listener.await(), "capability did not settle in time");
    return listener.result;
  }

  private EnvironmentCapabilityExecutionHandle invokeAsync(
      EnvironmentCapability capability, String arguments, RecordingListener listener) {
    return capability.execute(request(capability, arguments), listener);
  }

  private String writeArguments(String path, String content) {
    return "{\"path\":"
        + json(path)
        + ",\"content\":"
        + json(content)
        + ",\"workdir\":"
        + json(workdir.toString())
        + "}";
  }

  private String editArguments(
      String path, String oldString, String newString, boolean replaceAll) {
    return "{\"path\":"
        + json(path)
        + ",\"old_string\":"
        + json(oldString)
        + ",\"new_string\":"
        + json(newString)
        + ",\"replace_all\":"
        + replaceAll
        + ",\"workdir\":"
        + json(workdir.toString())
        + "}";
  }

  private static String text(EnvironmentCapabilityResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {

    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;

    @Override
    public void onComplete(EnvironmentCapabilityResult completed) {
      result = completed;
      latch.countDown();
    }

    @Override
    public void onError(Throwable error) {
      result = EnvironmentCapabilityResult.error("call", String.valueOf(error.getMessage()));
      latch.countDown();
    }

    private boolean await() throws InterruptedException {
      return latch.await(30, TimeUnit.SECONDS);
    }
  }

  /** 等到 worker 真正排队在该变更锁上，取消才必然发生在读取与提交之前；这是对 worker 进度的同步，而不是固定等待。 */
  private static void awaitLockWaiter(ReentrantLock lock) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(lock.hasQueuedThreads(), "worker 未进入等待变更锁的状态");
  }

  private static boolean posixSupported(Path path) throws IOException {
    FileStore store = Files.getFileStore(path);
    return store.supportsFileAttributeView(PosixFileAttributeView.class);
  }

  /** 是否以 root 运行：权限位回归用例只在非 root 下可确定性复现（root 会绕过文件权限位）。 */
  private static boolean runningAsRoot() {
    return "root".equals(System.getProperty("user.name"));
  }

  private static Set<PosixFilePermission> permissions(String symbolic) {
    return PosixFilePermissions.fromString(symbolic);
  }

  // ---------------------------------------------------------------- write

  /** 来源：pi-base write-behavior「requires path and defaults workdir during execution」。 */
  @Test
  void writesToExplicitWorkdirAndRejectsMissingArguments() throws Exception {
    WriteCapability write = write();
    IllegalArgumentException missingPath =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                request(write, "{\"content\":\"x\",\"workdir\":" + json(workdir.toString()) + "}"));
    assertTrue(missingPath.getMessage().contains("$.path is required"), missingPath.getMessage());

    IllegalArgumentException missingContent =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                request(write, "{\"path\":\"x.ts\",\"workdir\":" + json(workdir.toString()) + "}"));
    assertTrue(
        missingContent.getMessage().contains("$.content is required"), missingContent.getMessage());

    EnvironmentCapabilityResult created = invoke(write, writeArguments("x.ts", "x"));
    assertFalse(created.error(), text(created));
    assertEquals("x", Files.readString(workdir.resolve("x.ts")));
  }

  /** workdir 对 write 是可选的：绝对 path 直接解析，相对 path 缺少显式 workdir 则在执行前拒绝且不回退到 cwd/HOME 或任何默认目录。 */
  @Test
  void writeAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt() throws Exception {
    Path absolute = workdir.resolve("absolute.txt");
    WriteCapability write = write();

    EnvironmentCapabilityResult created =
        invoke(write, "{\"path\":" + json(absolute.toString()) + ",\"content\":\"absolute\"}");
    assertFalse(created.error(), text(created));
    assertEquals("absolute", Files.readString(absolute));

    EnvironmentCapabilityResult relative =
        invoke(write, "{\"path\":\"relative.txt\",\"content\":\"x\"}");
    assertTrue(relative.error(), text(relative));
    assertTrue(text(relative).contains("workdir is required"), text(relative));
    assertFalse(Files.exists(workdir.resolve("relative.txt")));
  }

  /** 来源：pi-base write-behavior「write returns a simple success message」。 */
  @Test
  void reportsCreateAndOverwriteWithSimpleSuccessMessage() throws Exception {
    EnvironmentCapabilityResult created =
        invoke(write(), writeArguments("src/new.ts", "export const demo = 1;\n"));
    assertFalse(created.error(), text(created));
    assertEquals("Created src/new.ts successfully.", text(created));
    assertEquals("export const demo = 1;\n", Files.readString(workdir.resolve("src/new.ts")));

    EnvironmentCapabilityResult overwritten =
        invoke(write(), writeArguments("src/new.ts", "export const demo = 2;\n"));
    assertFalse(overwritten.error(), text(overwritten));
    assertEquals("Overwrote src/new.ts successfully.", text(overwritten));
    assertEquals("export const demo = 2;\n", Files.readString(workdir.resolve("src/new.ts")));
  }

  /**
   * 来源：pi-base write-behavior「writes caller-provided line endings when overwriting an existing
   * file」。
   */
  @Test
  void writesCallerProvidedContentWithoutRewritingLineEndings() throws Exception {
    Files.writeString(workdir.resolve("crlf.txt"), "old\r\ntext\r\n");
    Files.write(workdir.resolve("cr.txt"), "old\rtext\r".getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult crlf = invoke(write(), writeArguments("crlf.txt", "new\ntext\n"));
    EnvironmentCapabilityResult cr = invoke(write(), writeArguments("cr.txt", "new\ntext\n"));

    assertFalse(crlf.error(), text(crlf));
    assertFalse(cr.error(), text(cr));
    // content 就是写入内容本身：不沿用既有 CRLF/CR，也不做任何行尾变换。
    assertEquals("new\ntext\n", Files.readString(workdir.resolve("crlf.txt")));
    assertArrayEquals(
        "new\ntext\n".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(workdir.resolve("cr.txt")));
  }

  /** 来源：pi-base edit-write-index「write preserves an existing BOM while overwriting content」。 */
  @Test
  void preservesExistingUtf8BomWhileOverwriting() throws Exception {
    Path file = workdir.resolve("bom.ts");
    Files.write(file, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\n'});

    EnvironmentCapabilityResult result = invoke(write(), writeArguments("bom.ts", "beta\n"));

    assertFalse(result.error(), text(result));
    byte[] written = Files.readAllBytes(file);
    assertEquals((byte) 0xef, written[0]);
    assertEquals((byte) 0xbb, written[1]);
    assertEquals((byte) 0xbf, written[2]);
    assertEquals("beta\n", new String(written, 3, written.length - 3, StandardCharsets.UTF_8));
  }

  /** 来源：pi-base edit-write-index「write preserves existing utf-16le encoding and BOM」。 */
  @Test
  void preservesExistingUtf16LeEncodingAndBomWhileOverwriting() throws Exception {
    Path file = workdir.resolve("legacy.txt");
    Files.write(file, TextFileCodec.encode("alpha\n", StandardCharsets.UTF_16LE, 2));

    EnvironmentCapabilityResult result = invoke(write(), writeArguments("legacy.txt", "beta\n"));

    assertFalse(result.error(), text(result));
    byte[] written = Files.readAllBytes(file);
    assertEquals((byte) 0xff, written[0]);
    assertEquals((byte) 0xfe, written[1]);
    assertEquals("beta\n", new String(written, 2, written.length - 2, StandardCharsets.UTF_16LE));
  }

  /** 来源：pi-base write-behavior「rejects an existing binary file without modifying it」。 */
  @Test
  void rejectsExistingBinaryFileWithoutModifyingIt() throws Exception {
    Path target = workdir.resolve("image.png");
    byte[] original = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01};
    Files.write(target, original);

    EnvironmentCapabilityResult result =
        invoke(write(), writeArguments("image.png", "replacement"));

    assertTrue(result.error());
    assertTrue(text(result).contains("binary"), text(result));
    assertArrayEquals(original, Files.readAllBytes(target));
  }

  /**
   * 来源：pi-base edit-write-index「write rejects text that cannot be represented in the existing
   * legacy encoding」。
   *
   * <p>改写理由：kk 明确不引入旧编码识别框架，既有编码只可能是 UTF-8/UTF-16LE/BE；这里用 UTF-16LE 无法表示的孤立代理项验证同一约束。
   */
  @Test
  void rejectsContentThatCannotBeRepresentedInExistingEncoding() throws Exception {
    Path file = workdir.resolve("legacy.txt");
    byte[] original = TextFileCodec.encode("café\n", StandardCharsets.UTF_16LE, 2);
    Files.write(file, original);

    EnvironmentCapabilityResult result =
        invoke(write(), writeArguments("legacy.txt", "snow \ud800\n"));

    assertTrue(result.error());
    assertTrue(text(result).contains("cannot be losslessly encoded"), text(result));
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  /** 来源：pi-base write-behavior「rejects an existing binary file」的目录分支；目录不是写目标。 */
  @Test
  void rejectsDirectoryAsWriteTarget() throws Exception {
    Files.createDirectory(workdir.resolve("mydir"));

    EnvironmentCapabilityResult result = invoke(write(), writeArguments("mydir", "x"));

    assertTrue(result.error());
    assertTrue(text(result).contains("path is a directory"), text(result));
  }

  /**
   * 来源：pi-base special-file-tools「rejects non-regular nodes before read, grep, edit, or write
   * performs file I/O」。
   *
   * <p>只覆盖本切片拥有的 write/edit；字符设备不是普通文件，必须在任何 I/O 前拒绝而不是丢弃写入或挂起。
   */
  @Test
  void rejectsNonRegularNodesBeforeIo() throws Exception {
    EnvironmentCapabilityResult writeResult =
        invoke(write(), writeArguments("/dev/null", "discarded"));
    EnvironmentCapabilityResult editResult =
        invoke(edit(), editArguments("/dev/null", "x", "y", false));

    assertTrue(writeResult.error(), text(writeResult));
    assertTrue(text(writeResult).contains("not a regular file"), text(writeResult));
    assertTrue(editResult.error(), text(editResult));
    assertTrue(text(editResult).contains("not a regular file"), text(editResult));
  }

  /** 悬空符号链接不是待创建的新文件：替换它会静默丢掉链接本身。 */
  @Test
  void rejectsDanglingSymbolicLinkWriteTarget() throws Exception {
    assumeTrue(posixSupported(workdir), "POSIX 符号链接语义");
    Path link = workdir.resolve("dangling");
    Files.createSymbolicLink(link, workdir.resolve("not-created"));

    EnvironmentCapabilityResult result = invoke(write(), writeArguments("dangling", "x"));

    assertTrue(result.error(), text(result));
    assertTrue(text(result).contains("symbolic link"), text(result));
    assertTrue(Files.isSymbolicLink(link));
  }

  /** 原子替换必须继承既有文件的 POSIX 权限：0755 不得被 staging 临时文件的 0600 覆盖。 */
  @Test
  void preservesExistingPosixPermissionsOfReplacedFile() throws Exception {
    assumeTrue(posixSupported(workdir), "POSIX 权限语义");
    for (String symbolic : List.of("rwxr-xr-x", "rw-r-----", "rw-------")) {
      Path file = workdir.resolve("mode-" + symbolic + ".sh");
      Files.writeString(file, "old\n");
      Files.setPosixFilePermissions(file, permissions(symbolic));

      EnvironmentCapabilityResult result =
          invoke(write(), writeArguments(file.getFileName().toString(), "new\n"));

      assertFalse(result.error(), text(result));
      assertEquals(
          permissions(symbolic), Files.getPosixFilePermissions(file), "write 必须保留既有权限 " + symbolic);
    }
  }

  /** edit 与 write 共享同一原子提交，因此权限继承对 edit 同样成立。 */
  @Test
  void preservesExistingPosixPermissionsOfEditedFile() throws Exception {
    assumeTrue(posixSupported(workdir), "POSIX 权限语义");
    Path file = workdir.resolve("script.sh");
    Files.writeString(file, "run alpha\n");
    Files.setPosixFilePermissions(file, permissions("rwxr-xr-x"));

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("script.sh", "alpha", "beta", false));

    assertFalse(result.error(), text(result));
    assertEquals("run beta\n", Files.readString(file));
    assertEquals(permissions("rwxr-xr-x"), Files.getPosixFilePermissions(file));
  }

  /**
   * 只读原文件（0444）只要目录可写就必须能合法原子覆盖并保留 0444。
   *
   * <p>回归：staging 曾在写入内容之前就被设成目标权限，导致 0444 目标连 staging 都写不进去；改为「先写内容、后收敛权限」后，写 staging 不再依赖目标自身可写，
   * staging 的权限也只从 0600 收敛到目标权限。
   */
  @Test
  void overwritesReadOnlyFileWhenDirectoryIsWritable() throws Exception {
    assumeTrue(posixSupported(workdir), "POSIX 权限语义");
    assumeTrue(!runningAsRoot(), "root 会绕过文件权限位，无法构造只读目标");
    Path file = workdir.resolve("readonly.sh");
    Files.writeString(file, "old\n");
    Files.setPosixFilePermissions(file, permissions("r--r--r--"));

    EnvironmentCapabilityResult wrote = invoke(write(), writeArguments("readonly.sh", "new\n"));

    assertFalse(wrote.error(), text(wrote));
    assertEquals("new\n", Files.readString(file));
    assertEquals(permissions("r--r--r--"), Files.getPosixFilePermissions(file));

    // edit 共享同一原子提交：只读目标同样可被合法覆盖并保留 0444。
    EnvironmentCapabilityResult edited =
        invoke(edit(), editArguments("readonly.sh", "new", "edited", false));
    assertFalse(edited.error(), text(edited));
    assertEquals("edited\n", Files.readString(file));
    assertEquals(permissions("r--r--r--"), Files.getPosixFilePermissions(file));
  }

  /**
   * 目标权限 0000 时原内容不可读：write 在读取原文阶段就拒绝，目标保持不变。
   *
   * <p>这解释了为什么 0000 不在「合法覆盖」范围内：提交要做的第一件事是无损解码原内容，读不到就只能显式失败。
   */
  @Test
  void rejectsWhollyUnreadableTargetWithoutModifyingIt() throws Exception {
    assumeTrue(posixSupported(workdir), "POSIX 权限语义");
    assumeTrue(!runningAsRoot(), "root 会绕过文件权限位，无法构造不可读目标");
    Path file = workdir.resolve("hidden.txt");
    Files.writeString(file, "secret\n");
    Files.setPosixFilePermissions(file, permissions("---------"));
    try {
      EnvironmentCapabilityResult result = invoke(write(), writeArguments("hidden.txt", "new\n"));

      assertTrue(result.error(), text(result));
      assertEquals(permissions("---------"), Files.getPosixFilePermissions(file));
      assertEquals("secret\n".getBytes(StandardCharsets.UTF_8).length, Files.size(file));
    } finally {
      // 恢复可读权限，避免影响 @TempDir 清理与后续断言。
      Files.setPosixFilePermissions(file, permissions("rw-------"));
    }
  }

  /** 新文件必须沿用平台默认创建权限（POSIX 上由进程 umask 派生），不能继承 staging 临时文件的 0600，也不得放宽为固定宽权限。 */
  @Test
  void createsNewFileWithPlatformDefaultPermissions() throws Exception {
    assumeTrue(posixSupported(workdir), "POSIX 权限语义");
    Path probe = workdir.resolve("probe.txt");
    Files.createFile(probe);
    Set<PosixFilePermission> expected = Files.getPosixFilePermissions(probe);

    EnvironmentCapabilityResult result = invoke(write(), writeArguments("created.txt", "x\n"));

    assertFalse(result.error(), text(result));
    Set<PosixFilePermission> actual = Files.getPosixFilePermissions(workdir.resolve("created.txt"));
    assertEquals(expected, actual, "新文件权限必须与平台默认创建语义一致");
    assertNotEquals(PosixFilePermissions.fromString("rw-------"), actual, "不得沿用 staging 临时文件的收敛权限");
  }

  /** 提交只留下目标文件：staging 文件必须在成功与失败路径上都被清理。 */
  @Test
  void leavesNoStagingFilesBehind() throws Exception {
    invoke(write(), writeArguments("kept.txt", "content\n"));
    invoke(edit(), editArguments("kept.txt", "content", "edited", false));

    try (Stream<Path> entries = Files.list(workdir)) {
      assertEquals(
          List.of(),
          entries
              .map(path -> path.getFileName().toString())
              .filter(name -> name.startsWith(".kk-mutation-"))
              .toList());
    }
    assertEquals("edited\n", Files.readString(workdir.resolve("kept.txt")));
  }

  /**
   * 来源：pi-base write-behavior「reports success once the file write has committed even if
   * cancellation arrives at completion」。
   *
   * <p>已提交的修改只返回成功终态：提交之后到达的取消不能把结果翻成未写入。
   */
  @Test
  void keepsCommittedWriteSuccessfulWhenCancelArrivesAfterCompletion() throws Exception {
    WriteCapability write = write();
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        invokeAsync(write, writeArguments("committed.txt", "committed\n"), listener);
    assertTrue(listener.await());
    assertFalse(listener.result.error(), text(listener.result));

    handle.cancel();

    assertTrue(listener.await());
    assertFalse(listener.result.error(), "已提交结果不得被迟到的取消改写");
    assertEquals("committed\n", Files.readString(workdir.resolve("committed.txt")));
  }

  /** 提交前的取消必须生效：目标尚未写入，调用得到取消终态。 */
  @Test
  void cancelledWriteBeforeCommitLeavesTargetUntouched() throws Exception {
    WriteCapability write = write();
    // 先持有同一 stripe 的变更锁：worker 无法越过它开始读取或提交，取消必然发生在提交之前。
    ReentrantLock lock = FileMutations.lock(workdir.toRealPath().resolve("blocked.txt"));
    try {
      RecordingListener listener = new RecordingListener();
      EnvironmentCapabilityExecutionHandle handle =
          invokeAsync(write, writeArguments("blocked.txt", "blocked\n"), listener);
      awaitLockWaiter(lock);
      handle.cancel();
      assertTrue(listener.await());
      assertTrue(listener.result.error(), text(listener.result));
      assertTrue(text(listener.result).contains("Operation cancelled"), text(listener.result));
      assertFalse(Files.exists(workdir.resolve("blocked.txt")));
      // 释放后 worker 必须真的走进取消检查并退出临界区：能重新拿到锁即证明它没有继续提交。
      lock.unlock();
      assertTrue(lock.tryLock(10, TimeUnit.SECONDS), "worker 未退出变更临界区");
      lock.unlock();
      assertFalse(Files.exists(workdir.resolve("blocked.txt")));
    } finally {
      if (lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    }
  }

  /** edit 与 write 共享同一取消语义：提交前的取消必须生效，原文件保持原样。 */
  @Test
  void cancelledEditBeforeCommitLeavesFileUntouched() throws Exception {
    Path target = workdir.resolve("blocked.txt");
    Files.writeString(target, "before\n");
    EditCapability edit = edit();
    ReentrantLock lock = FileMutations.lock(target.toRealPath());
    try {
      RecordingListener listener = new RecordingListener();
      EnvironmentCapabilityExecutionHandle handle =
          invokeAsync(edit, editArguments("blocked.txt", "before", "after", false), listener);
      awaitLockWaiter(lock);
      handle.cancel();
      assertTrue(listener.await());
      assertTrue(listener.result.error(), text(listener.result));
      assertTrue(text(listener.result).contains("Operation cancelled"), text(listener.result));
      assertEquals("before\n", Files.readString(target));
      lock.unlock();
      assertTrue(lock.tryLock(10, TimeUnit.SECONDS), "worker 未退出变更临界区");
      lock.unlock();
      assertEquals("before\n", Files.readString(target));
    } finally {
      if (lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    }
  }

  // ----------------------------------------------------------------- edit

  /** 来源：pi-base edit-write-index「edits a file using old_string/new_string」。 */
  @Test
  void replacesExactTextAndReportsReplacements() throws Exception {
    Files.writeString(workdir.resolve("src.ts"), "export const demo = 1;\n");

    EnvironmentCapabilityResult result =
        invoke(
            edit(),
            editArguments("src.ts", "export const demo = 1;", "export const demo = 2;", false));

    assertFalse(result.error(), text(result));
    assertTrue(text(result).contains("Edited src.ts successfully."), text(result));
    assertTrue(text(result).contains("Replacements: 1"), text(result));
    assertEquals("export const demo = 2;\n", Files.readString(workdir.resolve("src.ts")));
  }

  /**
   * 来源：pi-base edit-write-index「edits the current file contents without cross-call stale-read
   * protection」。
   */
  @Test
  void editsCurrentFileContents() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "alpha\nbeta\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "alpha", "gamma", false));

    assertFalse(result.error(), text(result));
    assertEquals("gamma\nbeta\n", Files.readString(workdir.resolve("example.ts")));
  }

  /**
   * 来源：pi-base edit-queue「serializes same-file concurrent edits through one read-modify-write
   * critical section」。
   */
  @Test
  void serializesConcurrentEditsOfTheSameFile() throws Exception {
    Path file = workdir.resolve("queue.txt");
    Files.writeString(file, "token-0\ntoken-1\ntoken-2\ntoken-3\n");
    EditCapability edit = edit();

    List<RecordingListener> listeners = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      RecordingListener listener = new RecordingListener();
      listeners.add(listener);
      invokeAsync(
          edit, editArguments("queue.txt", "token-" + index, "done-" + index, false), listener);
    }
    for (RecordingListener listener : listeners) {
      assertTrue(listener.await());
      assertFalse(listener.result.error(), text(listener.result));
    }
    // 整段读-改-写都在同一临界区内：并发编辑不会互相覆盖，四个替换全部生效。
    assertEquals("done-0\ndone-1\ndone-2\ndone-3\n", Files.readString(file));
  }

  /** 来源：pi-base edit-write-index「rejects edit when old_string is not found」。 */
  @Test
  void rejectsMissingOldStringWithoutEchoingIt() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "alpha\nbeta\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "SECRET_PASSWORD_123", "x", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("Could not find old_string"), text(result));
    assertFalse(text(result).contains("SECRET_PASSWORD_123"), text(result));
  }

  /** 来源：pi-base edit-write-index「rejects edit when old_string matches multiple times」。 */
  @Test
  void rejectsMultipleMatchesWithoutReplaceAll() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "alpha\nalpha\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "alpha", "beta", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("Found 2 exact matches"), text(result));
    assertEquals("alpha\nalpha\n", Files.readString(workdir.resolve("example.ts")));
  }

  /** 来源：pi-base edit-write-index「rejects edit when old_string has overlapping matches」。 */
  @Test
  void rejectsOverlappingMatchesWithoutReplaceAll() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "aaa\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "aa", "b", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("Found 2 exact matches"), text(result));
    assertEquals("aaa\n", Files.readString(workdir.resolve("example.ts")));
  }

  /** 来源：pi-base edit-write-index「supports replace_all for multiple matches」。 */
  @Test
  void supportsReplaceAllForMultipleMatches() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "alpha\nalpha\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "alpha", "beta", true));

    assertFalse(result.error(), text(result));
    assertTrue(text(result).contains("Replacements: 2"), text(result));
    assertEquals("beta\nbeta\n", Files.readString(workdir.resolve("example.ts")));
  }

  /** 来源：pi-base edit-write-index「rejects replace_all when matches overlap」。 */
  @Test
  void rejectsOverlappingReplaceAll() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "aaa\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "aa", "b", true));

    assertTrue(result.error());
    assertTrue(text(result).contains("overlapping exact matches"), text(result));
    assertEquals("aaa\n", Files.readString(workdir.resolve("example.ts")));
  }

  /** 来源：pi-base edit-write-index「rejects identical old_string and new_string」。 */
  @Test
  void rejectsIdenticalOldAndNewString() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "alpha\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "alpha", "alpha", false));

    assertTrue(result.error());
    // kk 把“完全相同”与“只在换行书写上等价”统一成一条 must differ 文案，两者都不得产生写入。
    assertTrue(text(result).contains("No changes to apply"), text(result));
    assertTrue(text(result).contains("must differ"), text(result));
  }

  /** 来源：pi-base edit-write-index「rejects no-op edits that differ only by line-ending spelling」。 */
  @Test
  void rejectsNoOpEditDifferingOnlyByLineEndingSpelling() throws Exception {
    Path file = workdir.resolve("example.ts");
    Files.write(file, "alpha\r\n".getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "alpha\r\n", "alpha\n", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("No changes to apply"), text(result));
    assertArrayEquals("alpha\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
  }

  /** 来源：pi-base edit-write-index「rejects empty old_string」。 */
  @Test
  void rejectsEmptyOldString() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "alpha\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("example.ts", "", "beta", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("old_string must not be empty"), text(result));
  }

  /** 来源：pi-base edit-write-index「rejects missing new_string when schema validation is bypassed」。 */
  @Test
  void rejectsMissingNewString() throws Exception {
    Files.writeString(workdir.resolve("example.ts"), "alpha\n");

    EditCapability edit = edit();
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                request(
                    edit,
                    "{\"path\":\"example.ts\",\"old_string\":\"alpha\",\"workdir\":"
                        + json(workdir.toString())
                        + "}"));

    assertTrue(error.getMessage().contains("$.new_string is required"), error.getMessage());
    assertEquals("alpha\n", Files.readString(workdir.resolve("example.ts")));
  }

  /** 来源：pi-base edit-write-index「edit reports missing path」。 */
  @Test
  void rejectsMissingPath() throws Exception {
    EditCapability edit = edit();
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                request(
                    edit,
                    "{\"old_string\":\"a\",\"new_string\":\"b\",\"workdir\":"
                        + json(workdir.toString())
                        + "}"));

    assertTrue(error.getMessage().contains("$.path is required"), error.getMessage());
  }

  /**
   * 来源：pi-base edit-write-index「adds retry guidance to edit argument validation failures」的参数校验部分。
   *
   * <p>改写理由：kk 的模型侧参数校验由共享 schema 承担，Node 的“重新调整参数并重跑”提示属于 TUI 文案；这里核对 schema 必填集与实现一致。 workdir 对
   * write/edit 都是可选的：绝对 path 无需它，相对 path 缺少它时由实现拒绝，因此不在必填集内。
   */
  @Test
  void editSchemaRequiresEveryExecutedArgument() {
    assertEquals(
        Set.of("path", "old_string", "new_string"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "content"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_WRITE)
            .inputSchema()
            .required());
  }

  /** workdir 对 edit 同样可选：绝对 path 直接编辑，相对 path 缺少显式 workdir 则在执行前拒绝。 */
  @Test
  void editAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt() throws Exception {
    Path absolute = workdir.resolve("absolute.ts");
    Files.writeString(absolute, "alpha\n");
    EditCapability edit = edit();

    EnvironmentCapabilityResult edited =
        invoke(
            edit,
            "{\"path\":"
                + json(absolute.toString())
                + ",\"old_string\":\"alpha\",\"new_string\":\"beta\"}");
    assertFalse(edited.error(), text(edited));
    assertEquals("beta\n", Files.readString(absolute));

    EnvironmentCapabilityResult relative =
        invoke(edit, "{\"path\":\"absolute.ts\",\"old_string\":\"beta\",\"new_string\":\"gamma\"}");
    assertTrue(relative.error(), text(relative));
    assertTrue(text(relative).contains("workdir is required"), text(relative));
    assertEquals("beta\n", Files.readString(absolute));
  }

  /** 编辑目录必须在 I/O 前拒绝，并保持目录内容不变。 */
  @Test
  void rejectsDirectoryEditTarget() throws Exception {
    Files.createDirectories(workdir.resolve("dir"));
    Files.writeString(workdir.resolve("dir/kept.txt"), "kept\n");

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("dir", "kept", "changed", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("must be a file"), text(result));
    assertEquals("kept\n", Files.readString(workdir.resolve("dir/kept.txt")));
  }

  /**
   * 来源：pi-base edit-write-index「edit rejects text that cannot be represented in the existing legacy
   * encoding」。
   */
  @Test
  void editRejectsContentThatCannotBeRepresentedInExistingEncoding() throws Exception {
    Path file = workdir.resolve("legacy.txt");
    byte[] original = TextFileCodec.encode("café\n", StandardCharsets.UTF_16LE, 2);
    Files.write(file, original);

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("legacy.txt", "café", "漢字\ud800", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("cannot be losslessly encoded"), text(result));
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  /** 编辑二进制文件必须在任何 I/O 前拒绝。 */
  @Test
  void rejectsBinaryEditTarget() throws Exception {
    Path file = workdir.resolve("binary.bin");
    byte[] original = {0, 1, 2};
    Files.write(file, original);

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("binary.bin", "a", "b", false));

    assertTrue(result.error());
    assertTrue(text(result).contains("binary"), text(result));
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  /** 来源：pi-base edit-write-index「preserves CRLF line endings during edit」。 */
  @Test
  void preservesCrlfLineEndings() throws Exception {
    Path file = workdir.resolve("crlf.txt");
    Files.write(file, "alpha\r\nbeta\r\n".getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("crlf.txt", "beta", "gamma", false));

    assertFalse(result.error(), text(result));
    assertEquals("alpha\r\ngamma\r\n", Files.readString(file));
  }

  /** 来源：pi-base edit-write-index「preserves CR line endings during multiline edit」。 */
  @Test
  void preservesCrOnlyLineEndings() throws Exception {
    Path file = workdir.resolve("cr.txt");
    Files.write(file, "alpha\rbeta\rgamma\r".getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("cr.txt", "alpha\nbeta", "left\nright", false));

    assertFalse(result.error(), text(result));
    assertArrayEquals(
        "left\rright\rgamma\r".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
  }

  /** 来源：pi-base edit-write-index「preserves matched mixed line endings for replaced newlines」。 */
  @Test
  void preservesMatchedMixedLineEndings() throws Exception {
    Path file = workdir.resolve("mixed.txt");
    Files.write(file, "alpha\r\nbeta\rgamma".getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("mixed.txt", "alpha\nbeta", "left\nright", false));

    assertFalse(result.error(), text(result));
    assertArrayEquals(
        "left\r\nright\rgamma".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
  }

  /** 来源：pi-base edit-write-index「uses LF for ambiguous inserted newlines in mixed-ending files」。 */
  @Test
  void usesLfForAmbiguousInsertedNewlines() throws Exception {
    Path file = workdir.resolve("ambiguous.txt");
    Files.write(file, "head\r\nmiddle\rtail".getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("ambiguous.txt", "middle", "left\nright", false));

    assertFalse(result.error(), text(result));
    assertArrayEquals(
        "head\r\nleft\nright\rtail".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
  }

  /**
   * 来源：pi-base edit-write-index「can add and remove a final newline through the normalized LF view」。
   */
  @Test
  void canAddAndRemoveFinalNewline() throws Exception {
    Path withoutNewline = workdir.resolve("no-newline.txt");
    Path withNewline = workdir.resolve("with-newline.txt");
    Files.writeString(withoutNewline, "tail");
    Files.writeString(withNewline, "tail\n");
    EditCapability edit = edit();

    EnvironmentCapabilityResult added =
        invoke(edit, editArguments("no-newline.txt", "tail", "tail\n", false));
    EnvironmentCapabilityResult removed =
        invoke(edit, editArguments("with-newline.txt", "tail\n", "tail", false));

    assertFalse(added.error(), text(added));
    assertEquals("tail\n", Files.readString(withoutNewline));
    assertFalse(removed.error(), text(removed));
    assertEquals("tail", Files.readString(withNewline));
  }

  /** 来源：pi-base edit-write-index「preserves BOM during edit」。 */
  @Test
  void preservesBomDuringEdit() throws Exception {
    Path file = workdir.resolve("bom.ts");
    Files.write(
        file, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', 'l', 'p', 'h', 'a', '\n'});

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("bom.ts", "alpha", "gamma", false));

    assertFalse(result.error(), text(result));
    byte[] written = Files.readAllBytes(file);
    assertEquals((byte) 0xef, written[0]);
    assertEquals((byte) 0xbb, written[1]);
    assertEquals((byte) 0xbf, written[2]);
    assertEquals("gamma\n", new String(written, 3, written.length - 3, StandardCharsets.UTF_8));
  }

  /** 来源：pi-base edit-write-index「preserves utf-16le encoding during edit」。 */
  @Test
  void preservesUtf16LeEncodingDuringEdit() throws Exception {
    Path file = workdir.resolve("legacy.txt");
    Files.write(file, TextFileCodec.encode("alpha\nbeta\n", StandardCharsets.UTF_16LE, 2));

    EnvironmentCapabilityResult result =
        invoke(edit(), editArguments("legacy.txt", "alpha", "gamma", false));

    assertFalse(result.error(), text(result));
    byte[] written = Files.readAllBytes(file);
    assertEquals((byte) 0xff, written[0]);
    assertEquals((byte) 0xfe, written[1]);
    assertEquals(
        "gamma\nbeta\n", new String(written, 2, written.length - 2, StandardCharsets.UTF_16LE));
  }

  /** 提交后的通知与展示失败不得回滚文件：成功终态与磁盘内容必须一致。 */
  @Test
  void keepsCommittedEditSuccessfulWhenCancelArrivesAfterCompletion() throws Exception {
    Files.writeString(workdir.resolve("committed.txt"), "before\n");
    EditCapability edit = edit();
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        invokeAsync(edit, editArguments("committed.txt", "before", "after", false), listener);
    assertTrue(listener.await());
    assertFalse(listener.result.error(), text(listener.result));

    handle.cancel();

    assertTrue(listener.await());
    assertFalse(listener.result.error(), "已提交结果不得被迟到的取消改写");
    assertEquals("after\n", Files.readString(workdir.resolve("committed.txt")));
  }
}
