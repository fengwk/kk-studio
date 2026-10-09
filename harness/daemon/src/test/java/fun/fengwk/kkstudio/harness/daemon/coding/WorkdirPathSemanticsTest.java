package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 路径语义契约：文件工具仅使用绝对路径，拒绝任何相对路径；process.exec (bash) 必须在调用 arguments 中携带绝对存在的 workdir。
 *
 * <p>证明：文件工具相对 path 会拒绝（必须为绝对路径）；绝对路径不受沙箱限制；process.exec 必须携带绝对存在的 workdir；非法或不存在的 workdir
 * 在执行前确定性拒绝。
 */
class WorkdirPathSemanticsTest {

  @TempDir Path workdir;
  @TempDir Path workspaceRoot;
  @TempDir Path externalRoot;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void closeExecutors() {
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  /** 相对 path 必须在执行期被拒绝，且绝不回退到默认路径。 */
  @Test
  void relativePathIsRejectedWithoutDefaultFallback() throws Exception {
    Files.writeString(workdir.resolve("local.txt"), "from-explicit-workdir\n");
    Files.writeString(workspaceRoot.resolve("local.txt"), "from-workspace-root\n");

    EnvironmentCapabilityResult result =
        invoke(new ReadCapability(config(), executor), "{\"path\":\"local.txt\"}");

    assertTrue(result.error());
    assertTrue(text(result).contains("path must be an absolute path"), text(result));
    // 校验发生在任何文件系统访问之前：必须明确声明未执行，并给出下一步。
    assertTrue(text(result).contains("The tool was not executed."), text(result));
    // 非法相对值不回显，只指出字段与绝对路径要求。
    assertFalse(text(result).contains("local.txt"), text(result));
    assertFalse(text(result).contains("from-workspace-root"));
    assertFalse(text(result).contains("from-explicit-workdir"));
  }

  /** process.exec (bash) 需要绝对 workdir；相对 workdir 在执行前拒绝。 */
  @Test
  void relativeWorkdirIsRejected() throws Exception {
    EnvironmentCapabilityResult bash =
        invoke(
            new BashCapability(config(), executor, scheduler),
            "{\"command\":\"pwd\",\"workdir\":\"relative/dir\"}");

    assertTrue(bash.error());
    assertTrue(text(bash).contains("workdir must be an absolute path"), text(bash));
    assertTrue(text(bash).contains("The tool was not executed."), text(bash));
  }

  /** workdir 必须真实存在且为目录：不存在的路径与普通文件都在执行前被拒绝，也不会被自动创建。 */
  @Test
  void unusableWorkdirIsRejected() throws Exception {
    Path missing = workdir.resolve("missing");
    EnvironmentCapabilityResult missingResult =
        invoke(
            new BashCapability(config(), executor, scheduler),
            "{\"command\":\"pwd\",\"workdir\":" + json(missing.toString()) + "}");
    assertTrue(missingResult.error());
    assertTrue(
        text(missingResult).contains("workdir must be an existing directory"), text(missingResult));
    assertFalse(Files.exists(missing), "不得自动创建 workdir");

    Path file = Files.writeString(workdir.resolve("not-a-directory.txt"), "content");
    EnvironmentCapabilityResult fileResult =
        invoke(
            new BashCapability(config(), executor, scheduler),
            "{\"command\":\"pwd\",\"workdir\":" + json(file.toString()) + "}");
    assertTrue(fileResult.error());
    assertTrue(
        text(fileResult).contains("workdir must be an existing directory"), text(fileResult));
  }

  /** 文件工具仅使用绝对路径；process.exec 使用显式 workdir。 */
  @Test
  void absolutePathsAndProcessWorkdirAreIndependent() throws Exception {
    Files.writeString(workdir.resolve("local.txt"), "local\n");

    EnvironmentCapabilityResult read =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":" + json(workdir.resolve("local.txt").toString()) + "}");
    EnvironmentCapabilityResult write =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":"
                + json(workdir.resolve("created/local.txt").toString())
                + ",\"content\":\"created\"}");
    EnvironmentCapabilityResult bash =
        invoke(
            new BashCapability(config(), executor, scheduler),
            "{\"command\":\"pwd\",\"workdir\":" + json(workdir.toString()) + "}");

    assertFalse(read.error());
    assertTrue(text(read).contains("local"));
    assertFalse(write.error());
    assertEquals("created", Files.readString(workdir.resolve("created/local.txt")));
    assertEquals(workdir.toRealPath().toString(), text(bash).strip());
  }

  /** 文件工具每次调用独立使用自己的绝对路径。 */
  @Test
  void eachInvocationUsesItsOwnAbsolutePath() throws Exception {
    Files.writeString(workdir.resolve("first.txt"), "first\n");
    Files.writeString(externalRoot.resolve("second.txt"), "second\n");

    EnvironmentCapability first = new ReadCapability(config(), executor);
    EnvironmentCapabilityResult firstResult =
        invoke(first, "{\"path\":" + json(workdir.resolve("first.txt").toString()) + "}");
    EnvironmentCapabilityResult secondResult =
        invoke(first, "{\"path\":" + json(externalRoot.resolve("second.txt").toString()) + "}");

    assertFalse(firstResult.error());
    assertTrue(text(firstResult).contains("first"));
    assertFalse(secondResult.error());
    assertTrue(text(secondResult).contains("second"));
  }

  /** 文件工具不是沙箱：绝对路径都能照常读写，相对路径直接被拒绝。 */
  @Test
  void workdirIsNotASandbox() throws Exception {
    Files.writeString(externalRoot.resolve("outside.txt"), "needle\n");
    String externalFile = externalRoot.resolve("outside.txt").toString();
    String createdFile = externalRoot.resolve("nested/created.txt").toString();

    EnvironmentCapabilityResult absoluteRead =
        invoke(new ReadCapability(config(), executor), "{\"path\":" + json(externalFile) + "}");
    EnvironmentCapabilityResult absoluteWrite =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":" + json(createdFile) + ",\"content\":\"written\"}");
    EnvironmentCapabilityResult relativeRead =
        invoke(new ReadCapability(config(), executor), "{\"path\":\"outside.txt\"}");
    EnvironmentCapabilityResult relativeWrite =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":\"created.txt\",\"content\":\"written\"}");

    assertFalse(absoluteRead.error());
    assertTrue(text(absoluteRead).contains("needle"));
    assertFalse(absoluteWrite.error());
    assertEquals("written", Files.readString(Path.of(createdFile)));
    assertTrue(relativeRead.error());
    assertTrue(text(relativeRead).contains("path must be an absolute path"));
    assertTrue(relativeWrite.error());
    assertTrue(text(relativeWrite).contains("path must be an absolute path"));
  }

  /** 检索工具使用绝对路径检索目录。 */
  @Test
  void searchToolsUseAbsolutePath() throws Exception {
    Files.writeString(externalRoot.resolve("outside.txt"), "needle\n");

    EnvironmentCapabilityResult find =
        invoke(
            new FindCapability(config(), executor),
            "{\"pattern\":\"*.txt\",\"path\":" + json(externalRoot.toString()) + "}");
    EnvironmentCapabilityResult grep =
        invoke(
            new GrepCapability(config(), executor),
            "{\"pattern\":\"needle\",\"path\":"
                + json(externalRoot.resolve("outside.txt").toString())
                + "}");

    assertFalse(find.error());
    assertTrue(text(find).contains("outside.txt"));
    assertFalse(grep.error());
    assertTrue(text(grep).contains("outside.txt:1:needle"));
  }

  /** {@link EnvironmentPaths#workdir} 是唯一入口：缺失/相对/非目录都在执行前抛出确定性异常，绝不返回默认目录。 */
  @Test
  void environmentPathsWorkdirValidatesShapeAndExistence() throws Exception {
    assertEquals(workdir.toRealPath(), EnvironmentPaths.workdir(workdir.toString()), "现存目录解析为真实路径");

    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () -> EnvironmentPaths.workdir(workdir.resolve("missing").toString()));
    assertTrue(missing.getMessage().contains("exist"), missing.getMessage());

    Path file = Files.writeString(workdir.resolve("plain-file.txt"), "content");
    IllegalArgumentException notDirectory =
        assertThrows(
            IllegalArgumentException.class, () -> EnvironmentPaths.workdir(file.toString()));
    assertTrue(notDirectory.getMessage().contains("existing directory"), notDirectory.getMessage());

    IllegalArgumentException relative =
        assertThrows(
            IllegalArgumentException.class, () -> EnvironmentPaths.workdir("relative/dir"));
    assertTrue(relative.getMessage().contains("absolute"), relative.getMessage());

    assertThrows(IllegalArgumentException.class, () -> EnvironmentPaths.workdir(null));
  }

  /** 以 JSON 字符串字面量表示任意本地路径，避免手工拼接转义。 */
  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private CodingToolsConfig config() {
    return TestCodingConfig.withoutLsp(workspaceRoot);
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call", arguments),
            Duration.ZERO),
        listener);
    assertTrue(listener.await(), "capability must complete within the test timeout");
    return listener.result;
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream()
        .map(WorkdirPathSemanticsTest::text)
        .reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent text ? text.text() : "";
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      // 本测试只关心终态结果。
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
