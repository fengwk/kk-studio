package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class ApplyPatchCapabilityTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final long TEST_TIMEOUT_SECONDS = 5;

  @TempDir Path environmentRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void closeExecutor() {
    executor.shutdownNow();
  }

  /** 三种文件操作必须分别成功，并让 Update 严格按 hunk 内容修改目标文本。 */
  @Test
  void appliesAddUpdateAndDeleteHappyPaths() throws Exception {
    ApplyPatchCapability capability = tool();
    EnvironmentCapabilityResult added =
        invoke(
            capability,
            patch(
                "*** Begin Patch",
                "*** Add File: added.txt",
                "+one",
                "+two",
                "*** Add File: empty.txt",
                "*** End Patch"));
    assertFalse(added.error());
    assertEquals("one\ntwo\n", Files.readString(environmentRoot.resolve("added.txt")));
    assertEquals("", Files.readString(environmentRoot.resolve("empty.txt")));

    Files.writeString(environmentRoot.resolve("updated.txt"), "alpha\nbeta\n");
    EnvironmentCapabilityResult updated =
        invoke(
            capability,
            patch(
                "*** Begin Patch",
                "*** Update File: updated.txt",
                "@@",
                " alpha",
                "-beta",
                "+gamma",
                "*** End Patch"));
    assertFalse(updated.error());
    assertEquals("alpha\ngamma\n", Files.readString(environmentRoot.resolve("updated.txt")));

    EnvironmentCapabilityResult deleted =
        invoke(
            capability, patch("*** Begin Patch", "*** Delete File: updated.txt", "*** End Patch"));
    assertFalse(deleted.error());
    assertFalse(Files.exists(environmentRoot.resolve("updated.txt")));
  }

  /** 多个 hunk 必须按顺序应用，且 End of File 要求最终匹配落在文件尾。 */
  @Test
  void appliesMultipleHunksAndEndOfFileMarker() throws Exception {
    Path file = environmentRoot.resolve("multi-hunk.txt");
    Files.writeString(file, "head\none\nmiddle\nend\n");

    EnvironmentCapabilityResult result =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: multi-hunk.txt",
                "@@ head",
                "-one",
                "+ONE",
                "@@",
                "-end",
                "+END",
                "*** End of File",
                "*** End Patch"));

    assertFalse(result.error());
    assertEquals("head\nONE\nmiddle\nEND\n", Files.readString(file));
  }

  /** 一个 patch 的多个文件必须一起完成，并把 Workdir 限制在 invocation workspace 内。 */
  @Test
  void appliesMultipleFilesAndReportsEachTarget() throws Exception {
    Path module = Files.createDirectories(environmentRoot.resolve("module"));
    Files.writeString(module.resolve("updated.txt"), "before\n");
    Files.writeString(module.resolve("deleted.txt"), "remove\n");

    EnvironmentCapabilityResult result =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Workdir: module",
                "*** Add File: added.txt",
                "+created",
                "*** Update File: updated.txt",
                "@@",
                "-before",
                "+after",
                "*** Delete File: deleted.txt",
                "*** End Patch"));

    assertFalse(result.error());
    assertEquals("created\n", Files.readString(module.resolve("added.txt")));
    assertEquals("after\n", Files.readString(module.resolve("updated.txt")));
    assertFalse(Files.exists(module.resolve("deleted.txt")));
    String summary = text(result);
    assertTrue(summary.contains("added.txt"));
    assertTrue(summary.contains("updated.txt"));
    assertTrue(summary.contains("deleted.txt"));
  }

  /** 第二个操作预检失败时，第一个操作也不得落盘，证明 mutation 只发生在全量 preflight 之后。 */
  @Test
  void doesNotCommitEarlierOperationWhenLaterPreflightFails() throws Exception {
    Path first = environmentRoot.resolve("first.txt");
    Files.writeString(first, "old\n");

    EnvironmentCapabilityResult result =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: first.txt",
                "@@",
                "-old",
                "+new",
                "*** Update File: missing.txt",
                "@@",
                "-old",
                "+new",
                "*** End Patch"));

    assertTrue(result.error());
    assertEquals("old\n", Files.readString(first));
    assertFalse(Files.exists(environmentRoot.resolve("missing.txt")));
  }

  /** 未知 directive 与重复路径必须在任何文件写入前拒绝。 */
  @Test
  void rejectsUnknownAndDuplicatePaths() throws Exception {
    EnvironmentCapabilityResult duplicate =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Add File: duplicate.txt",
                "+first",
                "*** Add File: duplicate.txt",
                "+second",
                "*** End Patch"));
    assertTrue(duplicate.error());
    assertTrue(text(duplicate).contains("Duplicate patch path"));
    assertFalse(Files.exists(environmentRoot.resolve("duplicate.txt")));

    EnvironmentCapabilityResult unknown =
        invoke(tool(), patch("*** Begin Patch", "*** Rename File: old.txt", "*** End Patch"));
    assertTrue(unknown.error());
    assertTrue(text(unknown).contains("Unknown patch line"));
  }

  /** 缺失或多重命中的上下文都必须失败，不能静默采用第一个匹配位置。 */
  @Test
  void rejectsMissingAndAmbiguousContext() throws Exception {
    Path missing = environmentRoot.resolve("missing-context.txt");
    Files.writeString(missing, "present\n");
    EnvironmentCapabilityResult missingResult =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: missing-context.txt",
                "@@",
                " absent",
                "-present",
                "+changed",
                "*** End Patch"));
    assertTrue(missingResult.error());
    assertTrue(text(missingResult).contains("could not match"));
    assertEquals("present\n", Files.readString(missing));

    Path ambiguous = environmentRoot.resolve("ambiguous-context.txt");
    Files.writeString(ambiguous, "same\nsame\n");
    EnvironmentCapabilityResult ambiguousResult =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: ambiguous-context.txt",
                "@@",
                "-same",
                "+changed",
                "*** End Patch"));
    assertTrue(ambiguousResult.error());
    assertTrue(text(ambiguousResult).contains("ambiguous"));
    assertEquals("same\nsame\n", Files.readString(ambiguous));
  }

  /** absolute、parent 与 symlink escape 必须全部拒绝，且不能触碰 workspace 外的文件。 */
  @Test
  void rejectsAbsoluteParentAndSymlinkEscapes() throws Exception {
    Path outside = Files.createTempDirectory("apply-patch-outside");
    Path outsideFile = Files.writeString(outside.resolve("outside.txt"), "outside\n");
    Files.createSymbolicLink(environmentRoot.resolve("escape"), outside);

    EnvironmentCapabilityResult absolute =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Add File: " + outside.resolve("absolute.txt"),
                "+blocked",
                "*** End Patch"));
    assertTrue(absolute.error());

    EnvironmentCapabilityResult parent =
        invoke(
            tool(),
            patch("*** Begin Patch", "*** Add File: ../parent.txt", "+blocked", "*** End Patch"));
    assertTrue(parent.error());

    EnvironmentCapabilityResult symlink =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: escape/outside.txt",
                "@@",
                "-outside",
                "+blocked",
                "*** End Patch"));
    assertTrue(symlink.error());
    assertEquals("outside\n", Files.readString(outsideFile));
    assertFalse(Files.exists(outside.resolve("absolute.txt")));

    EnvironmentCapabilityResult workdirSymlink =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Workdir: escape",
                "*** Add File: new.txt",
                "+blocked",
                "*** End Patch"));
    assertTrue(workdirSymlink.error());
    assertFalse(Files.exists(outside.resolve("new.txt")));
  }

  /** preflight 后父目录被替换为 symlink 时必须拒绝，不能把 Add 写到 workspace 外。 */
  @Test
  void rejectsParentSymlinkIntroducedAfterPreflight() throws Exception {
    assumeSymbolicLinksSupported();
    Path outside = Files.createTempDirectory("apply-patch-race-outside");
    Path raced = environmentRoot.resolve("raced");
    ApplyPatchCapability capability =
        new ApplyPatchCapability(
            config(),
            executor,
            () -> {
              try {
                Files.createSymbolicLink(raced, outside);
              } catch (IOException error) {
                throw new UncheckedIOException(error);
              }
            });

    try {
      EnvironmentCapabilityResult result =
          invoke(
              capability,
              patch(
                  "*** Begin Patch",
                  "*** Add File: raced/created.txt",
                  "+blocked",
                  "*** End Patch"));

      assertTrue(result.error());
      assertTrue(Files.isSymbolicLink(raced));
      assertFalse(Files.exists(outside.resolve("created.txt")));
    } finally {
      Files.deleteIfExists(raced);
      Files.deleteIfExists(outside.resolve("created.txt"));
      Files.deleteIfExists(outside);
    }
  }

  /** 二进制和非法 UTF-8 文件都必须在 preflight 阶段失败并保持原始字节。 */
  @Test
  void rejectsBinaryAndInvalidUtf8Files() throws Exception {
    Path binary = environmentRoot.resolve("binary.bin");
    byte[] binaryBytes = {1, 0, 2};
    Files.write(binary, binaryBytes);
    Path invalid = environmentRoot.resolve("invalid.txt");
    byte[] invalidBytes = {(byte) 0xc3, 0x28};
    Files.write(invalid, invalidBytes);

    EnvironmentCapabilityResult binaryResult =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: binary.bin",
                "@@",
                "-x",
                "+y",
                "*** End Patch"));
    assertTrue(binaryResult.error());
    assertTrue(text(binaryResult).contains("binary"));
    assertArrayEquals(binaryBytes, Files.readAllBytes(binary));

    EnvironmentCapabilityResult invalidResult =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: invalid.txt",
                "@@",
                "-x",
                "+y",
                "*** End Patch"));
    assertTrue(invalidResult.error());
    assertTrue(text(invalidResult).contains("invalid UTF-8"));
    assertArrayEquals(invalidBytes, Files.readAllBytes(invalid));
  }

  /** Update 必须保留 CRLF 与文件是否以换行结束的原始语义，且不能丢失 UTF-8 BOM。 */
  @Test
  void preservesLineEndingsFinalNewlineAndBom() throws Exception {
    Path finalNewline = environmentRoot.resolve("final-newline.txt");
    Files.write(finalNewline, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));
    Path noFinalNewline = environmentRoot.resolve("no-final-newline.txt");
    Files.write(noFinalNewline, "a\r\nb".getBytes(StandardCharsets.UTF_8));
    Path bom = environmentRoot.resolve("bom.txt");
    Files.write(bom, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\n', 'b'});

    EnvironmentCapabilityResult result =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: final-newline.txt",
                "@@",
                " a",
                "-b",
                "+c",
                "*** Update File: no-final-newline.txt",
                "@@",
                " a",
                "-b",
                "+c",
                "*** Update File: bom.txt",
                "@@",
                " a",
                "-b",
                "+c",
                "*** End Patch"));

    assertFalse(result.error());
    assertArrayEquals(
        "a\r\nc\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(finalNewline));
    assertArrayEquals(
        "a\r\nc".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(noFinalNewline));
    assertArrayEquals(
        new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\n', 'c'},
        Files.readAllBytes(bom));
  }

  /** Update 使用 replacement temp 时必须保留 POSIX 可执行位和自定义权限集合。 */
  @Test
  void preservesPosixPermissionsWhenUpdating() throws Exception {
    Path file = Files.writeString(environmentRoot.resolve("permissions.txt"), "before\n");
    PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
    Assumptions.assumeTrue(view != null);
    Set<PosixFilePermission> expected =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ);
    Files.setPosixFilePermissions(file, expected);

    EnvironmentCapabilityResult result =
        invoke(
            tool(),
            patch(
                "*** Begin Patch",
                "*** Update File: permissions.txt",
                "@@",
                "-before",
                "+after",
                "*** End Patch"));

    assertFalse(result.error());
    assertEquals("after\n", Files.readString(file));
    assertEquals(expected, Files.getPosixFilePermissions(file));
  }

  /** 提交阶段后续 I/O 失败时，已经提交的前序文件必须尽力恢复到原始字节。 */
  @Test
  void rollsBackEarlierCommitWhenLaterIoFails() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileAttributeView(environmentRoot, PosixFileAttributeView.class) != null);
    Path first = environmentRoot.resolve("first.txt");
    Files.writeString(first, "first-old\n");
    Path lockedDirectory = Files.createDirectory(environmentRoot.resolve("locked"));
    Path second = lockedDirectory.resolve("second.txt");
    Files.writeString(second, "second-old\n");
    Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(lockedDirectory);
    EnumSet<PosixFilePermission> readOnlyPermissions = EnumSet.copyOf(originalPermissions);
    readOnlyPermissions.remove(PosixFilePermission.OWNER_WRITE);
    readOnlyPermissions.remove(PosixFilePermission.GROUP_WRITE);
    readOnlyPermissions.remove(PosixFilePermission.OTHERS_WRITE);

    try {
      Files.setPosixFilePermissions(lockedDirectory, readOnlyPermissions);
      EnvironmentCapabilityResult result =
          invoke(
              tool(),
              patch(
                  "*** Begin Patch",
                  "*** Update File: first.txt",
                  "@@",
                  "-first-old",
                  "+first-new",
                  "*** Update File: locked/second.txt",
                  "@@",
                  "-second-old",
                  "+second-new",
                  "*** End Patch"));

      assertTrue(result.error());
      assertEquals("first-old\n", Files.readString(first));
      assertEquals("second-old\n", Files.readString(second));
    } finally {
      Files.setPosixFilePermissions(lockedDirectory, originalPermissions);
    }
  }

  /** Add 产生的文件和父目录也必须在后续提交失败时一起回滚。 */
  @Test
  void rollsBackAddedFileAndCreatedDirectoriesWhenLaterCommitFails() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileAttributeView(environmentRoot, PosixFileAttributeView.class) != null);
    Path lockedDirectory = Files.createDirectory(environmentRoot.resolve("locked"));
    Path second = lockedDirectory.resolve("second.txt");
    Files.writeString(second, "second-old\n");
    Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(lockedDirectory);
    EnumSet<PosixFilePermission> readOnlyPermissions = EnumSet.copyOf(originalPermissions);
    readOnlyPermissions.remove(PosixFilePermission.OWNER_WRITE);
    readOnlyPermissions.remove(PosixFilePermission.GROUP_WRITE);
    readOnlyPermissions.remove(PosixFilePermission.OTHERS_WRITE);

    try {
      Files.setPosixFilePermissions(lockedDirectory, readOnlyPermissions);
      EnvironmentCapabilityResult result =
          invoke(
              tool(),
              patch(
                  "*** Begin Patch",
                  "*** Add File: created/first.txt",
                  "+first",
                  "*** Update File: locked/second.txt",
                  "@@",
                  "-second-old",
                  "+second-new",
                  "*** End Patch"));

      assertTrue(result.error());
      assertFalse(Files.exists(environmentRoot.resolve("created")));
      assertEquals("second-old\n", Files.readString(second));
    } finally {
      Files.setPosixFilePermissions(lockedDirectory, originalPermissions);
    }
  }

  /** Delete 产生的缺失目标也必须在后续提交失败时恢复，避免多文件 patch 留下半成品。 */
  @Test
  void rollsBackDeletedFileWhenLaterCommitFails() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileAttributeView(environmentRoot, PosixFileAttributeView.class) != null);
    Path deleted = Files.writeString(environmentRoot.resolve("deleted.txt"), "deleted\n");
    Set<PosixFilePermission> deletedPermissions =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    Files.setPosixFilePermissions(deleted, deletedPermissions);
    Path lockedDirectory = Files.createDirectory(environmentRoot.resolve("locked"));
    Path second = lockedDirectory.resolve("second.txt");
    Files.writeString(second, "second-old\n");
    Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(lockedDirectory);
    EnumSet<PosixFilePermission> readOnlyPermissions = EnumSet.copyOf(originalPermissions);
    readOnlyPermissions.remove(PosixFilePermission.OWNER_WRITE);
    readOnlyPermissions.remove(PosixFilePermission.GROUP_WRITE);
    readOnlyPermissions.remove(PosixFilePermission.OTHERS_WRITE);

    try {
      Files.setPosixFilePermissions(lockedDirectory, readOnlyPermissions);
      EnvironmentCapabilityResult result =
          invoke(
              tool(),
              patch(
                  "*** Begin Patch",
                  "*** Delete File: deleted.txt",
                  "*** Update File: locked/second.txt",
                  "@@",
                  "-second-old",
                  "+second-new",
                  "*** End Patch"));

      assertTrue(result.error());
      assertEquals("deleted\n", Files.readString(deleted));
      assertEquals(deletedPermissions, Files.getPosixFilePermissions(deleted));
      assertEquals("second-old\n", Files.readString(second));
    } finally {
      Files.setPosixFilePermissions(lockedDirectory, originalPermissions);
    }
  }

  /** cancel 在 worker 尚未开始时必须得到取消终态，并且不能把排队中的 patch 写入磁盘。 */
  @Test
  void cancellationBeforeWorkerStartsDoesNotMutateFiles() throws Exception {
    ExecutorService blockedExecutor = Executors.newSingleThreadExecutor();
    CountDownLatch blockerStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    blockedExecutor.submit(
        () -> {
          blockerStarted.countDown();
          try {
            releaseBlocker.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      assertTrue(blockerStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      ApplyPatchCapability capability = new ApplyPatchCapability(config(), blockedExecutor);
      RecordingListener listener =
          invokeAsync(
              capability,
              patch("*** Begin Patch", "*** Add File: cancelled.txt", "+blocked", "*** End Patch"),
              Duration.ZERO,
              environmentRoot);
      listener.handle.cancel();

      assertTrue(listener.await());
      assertTrue(listener.result.error());
      assertTrue(text(listener.result).contains("Operation cancelled"));
      assertFalse(Files.exists(environmentRoot.resolve("cancelled.txt")));
    } finally {
      releaseBlocker.countDown();
      blockedExecutor.shutdownNow();
      assertTrue(blockedExecutor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
  }

  private ApplyPatchCapability tool() {
    return new ApplyPatchCapability(config(), executor);
  }

  private CodingToolsConfig config() {
    return new CodingToolsConfig(
        environmentRoot, 2000, 50 * 1024, "bash", new InMemoryResourceStore());
  }

  private EnvironmentCapabilityResult invoke(ApplyPatchCapability capability, String patch)
      throws Exception {
    RecordingListener listener = invokeAsync(capability, patch, Duration.ZERO, environmentRoot);
    assertTrue(listener.await());
    return listener.result;
  }

  private RecordingListener invokeAsync(
      ApplyPatchCapability capability, String patch, Duration timeout, Path workdir) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(),
                new EnvironmentCapabilityCall("apply-patch-test", arguments(patch)),
                timeout,
                workdir),
            listener);
    return listener;
  }

  private static String arguments(String patch) {
    try {
      return "{\"patchText\":" + OBJECT_MAPPER.writeValueAsString(patch) + "}";
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static String patch(String... lines) {
    return String.join("\n", lines);
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream()
        .map(ApplyPatchCapabilityTest::text)
        .reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent text ? text.text() : "";
  }

  private void assumeSymbolicLinksSupported() throws Exception {
    Path target = Files.createTempDirectory("apply-patch-symlink-target");
    Path link = environmentRoot.resolve("symlink-probe");
    try {
      try {
        Files.createSymbolicLink(link, target);
      } catch (UnsupportedOperationException | IOException error) {
        Assumptions.assumeTrue(false, "symbolic links are not supported");
      }
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(target);
    }
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    private volatile EnvironmentCapabilityExecutionHandle handle;
    private volatile EnvironmentCapabilityResult result;

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
      return completed.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
}
