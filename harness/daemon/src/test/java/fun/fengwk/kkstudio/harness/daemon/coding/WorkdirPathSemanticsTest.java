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
 * 显式 workdir 语义契约：workdir 是每次调用 arguments 中的必填绝对目录，不是会话状态、不是沙箱，也没有任何默认值。
 *
 * <p>证明：省略 workdir 一律拒绝且绝不回退到 Environment Root；相对 path 以该次调用的 workdir 为基准；workdir 之外的绝对路径与
 * 越界相对遍历都是普通路径；非法或不存在的 workdir 在执行前确定性拒绝；每次调用独立解析、互不继承。
 */
class WorkdirPathSemanticsTest {

  @TempDir Path workdir;
  @TempDir Path environmentRoot;
  @TempDir Path externalRoot;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void closeExecutors() {
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  /**
   * 省略 workdir 必须被拒绝，且绝不回退到 Environment Root。
   *
   * <p>workdir 是 schema 必填字段，因此拒绝发生在执行请求构造期（即永远不会进入 capability 执行），这是比“执行后报错”更强的 保证：在 Environment
   * Root 下放置同名文件也无法被读取。
   */
  @Test
  void omittedWorkdirIsRejectedWithoutEnvironmentRootFallback() throws Exception {
    Files.writeString(workdir.resolve("local.txt"), "from-explicit-workdir\n");
    Files.writeString(environmentRoot.resolve("local.txt"), "from-environment-root\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> invoke(new ReadCapability(config(), executor), "{\"path\":\"local.txt\"}"));

    assertTrue(error.getMessage().contains("workdir"), error.getMessage());
    assertFalse(error.getMessage().contains("from-environment-root"));
    assertFalse(error.getMessage().contains("from-explicit-workdir"));
  }

  /** 非绝对 workdir（相对形态）同样在构造期被 schema 接受形状但随后被词法校验拒绝，不能按 Backend cwd 或任何基准解析。 */
  @Test
  void relativeWorkdirIsRejected() throws Exception {
    Files.writeString(workdir.resolve("local.txt"), "local\n");

    EnvironmentCapabilityResult read =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":\"local.txt\",\"workdir\":\"relative/dir\"}");

    assertTrue(read.error());
    assertTrue(text(read).contains("absolute"), text(read));
  }

  /** workdir 必须真实存在且为目录：不存在的路径与普通文件都在执行前被拒绝，也不会被自动创建。 */
  @Test
  void unusableWorkdirIsRejected() throws Exception {
    Path missing = workdir.resolve("missing");
    EnvironmentCapabilityResult missingResult =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":\"local.txt\",\"workdir\":" + json(missing.toString()) + "}");
    assertTrue(missingResult.error());
    assertTrue(text(missingResult).contains("workdir"), text(missingResult));
    assertFalse(Files.exists(missing), "不得自动创建 workdir");

    Path file = Files.writeString(workdir.resolve("not-a-directory.txt"), "content");
    EnvironmentCapabilityResult fileResult =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":\"local.txt\",\"workdir\":" + json(file.toString()) + "}");
    assertTrue(fileResult.error());
    assertTrue(text(fileResult).contains("workdir"), text(fileResult));
  }

  /** 相对 path 以本次调用 arguments 中的 workdir 为基准：省略 path 与相对 path 都落在该目录。 */
  @Test
  void relativePathsResolveFromExplicitWorkdir() throws Exception {
    Files.writeString(workdir.resolve("local.txt"), "local\n");

    EnvironmentCapabilityResult read =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":\"local.txt\",\"workdir\":" + json(workdir.toString()) + "}");
    EnvironmentCapabilityResult write =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":\"created/local.txt\",\"content\":\"created\",\"workdir\":"
                + json(workdir.toString())
                + "}");
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

  /** 每次调用的 workdir 完全独立：同一 capability 连续两次调用各自使用自己的 workdir，互不继承。 */
  @Test
  void eachInvocationUsesItsOwnWorkdir() throws Exception {
    Files.writeString(workdir.resolve("first.txt"), "first\n");
    Files.writeString(externalRoot.resolve("second.txt"), "second\n");

    EnvironmentCapability first = new ReadCapability(config(), executor);
    EnvironmentCapabilityResult firstResult =
        invoke(first, "{\"path\":\"first.txt\",\"workdir\":" + json(workdir.toString()) + "}");
    EnvironmentCapabilityResult secondResult =
        invoke(
            first, "{\"path\":\"second.txt\",\"workdir\":" + json(externalRoot.toString()) + "}");

    assertFalse(firstResult.error());
    assertTrue(text(firstResult).contains("first"));
    assertFalse(secondResult.error());
    assertTrue(text(secondResult).contains("second"));

    // 反向验证：first.txt 相对 externalRoot 不存在 → 说明没有沿用上一次的 workdir。
    EnvironmentCapabilityResult crossCheck =
        invoke(first, "{\"path\":\"first.txt\",\"workdir\":" + json(externalRoot.toString()) + "}");
    assertTrue(crossCheck.error());
  }

  /** workdir 不是沙箱：绝对路径与越界相对路径都能照常读写与检索。 */
  @Test
  void workdirIsNotASandbox() throws Exception {
    Files.writeString(externalRoot.resolve("outside.txt"), "needle\n");
    String externalFile = externalRoot.resolve("outside.txt").toString();
    String createdFile = externalRoot.resolve("nested/created.txt").toString();
    String traversalFile = workdir.relativize(externalRoot.resolve("outside.txt")).toString();
    String traversalCreated = workdir.relativize(externalRoot.resolve("created.txt")).toString();

    EnvironmentCapabilityResult absoluteRead =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":" + json(externalFile) + ",\"workdir\":" + json(workdir.toString()) + "}");
    EnvironmentCapabilityResult absoluteWrite =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":"
                + json(createdFile)
                + ",\"content\":\"written\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    EnvironmentCapabilityResult traversalRead =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":" + json(traversalFile) + ",\"workdir\":" + json(workdir.toString()) + "}");
    EnvironmentCapabilityResult traversalWrite =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":"
                + json(traversalCreated)
                + ",\"content\":\"written\",\"workdir\":"
                + json(workdir.toString())
                + "}");

    assertFalse(absoluteRead.error());
    assertTrue(text(absoluteRead).contains("needle"));
    assertFalse(absoluteWrite.error());
    assertEquals("written", Files.readString(Path.of(createdFile)));
    assertFalse(traversalRead.error());
    assertTrue(text(traversalRead).contains("needle"));
    assertFalse(traversalWrite.error());
    assertEquals("written", Files.readString(externalRoot.resolve("created.txt")));
  }

  /** 检索工具同样以显式 workdir 为基准，可以检索 workdir 之外的绝对目录。 */
  @Test
  void searchToolsUseExplicitWorkdir() throws Exception {
    Files.writeString(externalRoot.resolve("outside.txt"), "needle\n");

    EnvironmentCapabilityResult find =
        invoke(
            new FindCapability(config(), executor),
            "{\"pattern\":\"*.txt\",\"path\":"
                + json(externalRoot.toString())
                + ",\"workdir\":"
                + json(workdir.toString())
                + "}");
    EnvironmentCapabilityResult grep =
        invoke(
            new GrepCapability(config(), executor),
            "{\"pattern\":\"needle\",\"path\":"
                + json(externalRoot.resolve("outside.txt").toString())
                + ",\"workdir\":"
                + json(workdir.toString())
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
    return new CodingToolsConfig(
        environmentRoot, 2000, 50 * 1024, "bash", new InMemoryResourceStore());
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
