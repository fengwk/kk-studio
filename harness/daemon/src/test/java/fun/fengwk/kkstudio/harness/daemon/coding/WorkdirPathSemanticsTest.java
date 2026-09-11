package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 缺省 cwd 语义契约：workdir 只是省略路径时的解析基准与默认工作目录，不是文件系统沙箱。
 *
 * <p>证明省略路径时仍以 invocation workspace 为基准，同时绝对路径与越出 workdir 的相对遍历对读、写、检索、列目录与命令执行都是普通路径。
 */
class WorkdirPathSemanticsTest {

  @TempDir Path workdir;
  @TempDir Path externalRoot;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void closeExecutors() {
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  /** 省略 path/workdir 时必须落在 invocation workspace：读取、写入与命令的默认 cwd 都以它为准。 */
  @Test
  void omittedPathsResolveFromInvocationWorkdir() throws Exception {
    Files.writeString(workdir.resolve("local.txt"), "local\n");

    EnvironmentCapabilityResult read =
        invoke(new ReadCapability(config(), executor), "{\"path\":\"local.txt\"}");
    EnvironmentCapabilityResult write =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":\"created/local.txt\",\"content\":\"created\"}");
    EnvironmentCapabilityResult bash =
        invoke(new BashCapability(config(), executor, scheduler), "{\"command\":\"pwd\"}");

    assertFalse(read.error());
    assertTrue(text(read).contains("local"));
    assertFalse(write.error());
    assertEquals("created", Files.readString(workdir.resolve("created/local.txt")));
    assertEquals(workdir.toRealPath().toString(), text(bash).strip());
  }

  /** 绝对路径可以直接指向 workdir 之外，读、写、检索与列目录都照常执行。 */
  @Test
  void acceptsExternalAbsolutePaths() throws Exception {
    Files.writeString(externalRoot.resolve("outside.txt"), "needle\n");
    String externalFile = externalRoot.resolve("outside.txt").toString();
    String createdFile = externalRoot.resolve("nested/created.txt").toString();

    EnvironmentCapabilityResult read =
        invoke(new ReadCapability(config(), executor), "{\"path\":" + json(externalFile) + "}");
    EnvironmentCapabilityResult write =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":" + json(createdFile) + ",\"content\":\"written\"}");
    EnvironmentCapabilityResult find =
        invoke(
            new FindCapability(config(), executor),
            "{\"pattern\":\"*.txt\",\"path\":" + json(externalRoot.toString()) + "}");
    EnvironmentCapabilityResult grep =
        invoke(
            new GrepCapability(config(), executor),
            "{\"pattern\":\"needle\",\"path\":" + json(externalFile) + "}");
    EnvironmentCapabilityResult bash =
        invoke(
            new BashCapability(config(), executor, scheduler),
            "{\"command\":\"cat outside.txt\",\"workdir\":" + json(externalRoot.toString()) + "}");

    assertFalse(read.error());
    assertTrue(text(read).contains("needle"));
    assertFalse(write.error());
    assertEquals("written", Files.readString(Path.of(createdFile)));
    assertFalse(find.error());
    assertTrue(text(find).contains("outside.txt"));
    assertTrue(text(find).contains("created.txt"));
    assertFalse(grep.error());
    assertTrue(text(grep).contains("outside.txt:1:needle"));
    assertFalse(bash.error());
    assertTrue(text(bash).contains("needle"));
  }

  /** 越出 workdir 的相对遍历同样是普通路径：写入落点与检索结果都在 workdir 之外。 */
  @Test
  void acceptsRelativeTraversalOutsideWorkdir() throws Exception {
    Files.writeString(externalRoot.resolve("outside.txt"), "needle\n");
    String traversalFile = workdir.relativize(externalRoot.resolve("outside.txt")).toString();
    String traversalCreated = workdir.relativize(externalRoot.resolve("created.txt")).toString();

    EnvironmentCapabilityResult read =
        invoke(new ReadCapability(config(), executor), "{\"path\":" + json(traversalFile) + "}");
    EnvironmentCapabilityResult write =
        invoke(
            new WriteCapability(config(), executor),
            "{\"path\":" + json(traversalCreated) + ",\"content\":\"written\"}");
    EnvironmentCapabilityResult find =
        invoke(
            new FindCapability(config(), executor),
            "{\"pattern\":\"outside.txt\",\"path\":" + json(parentTraversal(traversalFile)) + "}");
    EnvironmentCapabilityResult grep =
        invoke(
            new GrepCapability(config(), executor),
            "{\"pattern\":\"needle\",\"path\":" + json(traversalFile) + "}");
    // 列目录复用 read：外部目录以 workdir 相对形式列出条目。
    EnvironmentCapabilityResult list =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":" + json(parentTraversal(traversalFile)) + "}");

    assertFalse(read.error());
    assertTrue(text(read).contains("needle"));
    assertFalse(write.error());
    assertEquals("written", Files.readString(externalRoot.resolve("created.txt")));
    assertFalse(find.error());
    assertTrue(text(find).contains("outside.txt"));
    assertFalse(grep.error());
    assertTrue(text(grep).contains("outside.txt:1:needle"));
    assertFalse(list.error());
    assertTrue(text(list).contains("outside.txt"));
  }

  /** 显式 workdir 参数可以指向 workdir 之外，并成为本次调用的默认解析基准。 */
  @Test
  void workdirArgumentRedirectsDefaultBase() throws Exception {
    Files.createDirectories(externalRoot.resolve("nested"));
    Files.writeString(externalRoot.resolve("nested/target.txt"), "redirected\n");

    EnvironmentCapabilityResult read =
        invoke(
            new ReadCapability(config(), executor),
            "{\"path\":\"target.txt\",\"workdir\":"
                + json(externalRoot.resolve("nested").toString())
                + "}");

    assertFalse(read.error());
    assertTrue(text(read).contains("redirected"));
  }

  private static String parentTraversal(String traversalPath) {
    return traversalPath.substring(0, traversalPath.lastIndexOf('/'));
  }

  /** 以 JSON 字符串字面量表示任意本地路径，避免手工拼接转义。 */
  private static String json(String value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value);
  }

  private CodingToolsConfig config() {
    return new CodingToolsConfig(workdir, 2000, 50 * 1024, "bash", new InMemoryResourceStore());
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String arguments)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call", arguments),
            Duration.ZERO,
            workdir),
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
