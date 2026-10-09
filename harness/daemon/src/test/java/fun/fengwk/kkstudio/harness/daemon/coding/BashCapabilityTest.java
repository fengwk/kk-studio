package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link BashCapability} 的失败日志与进程收尾语义测试。
 *
 * <p>产品契约决定本测试的重点：成功、非零退出、超时和取消四种收尾都必须保留已捕获输出（唯一的中转文件只能被发布或有界预览替代，绝不能被 close 删除）；终态必须让调用方区分 capture
 * failure/truncated 与 timeout/cancel/exit；取消或失败绝不声称副作用被回滚。这些断言共同防止「失败 即丢失输出」「收尾路径删掉唯一副本」「把 kill
 * 退出码当成命令退出码」这三类回归。
 *
 * <p>另外锁住进程边界的三个事实：启动前已被取消或超时的调用不启动 shell、不产生任何本地文件；命令以参数传入，因此 stdin 立即关闭（等待 EOF 的命令必须自然退出）；超时与取消
 * 必须收敛整棵进程树，而不是只杀主进程。
 */
class BashCapabilityTest {

  /**
   * 「超时由测试触发」的调用预算。
   *
   * <p>这类用例断言的是「超时保留已捕获输出」，触发时机由 {@link ControlledScheduler#fireTimeout()} 决定，因此这个值不再参与断言：
   * 它只需要大到让真实时钟不可能抢先决定去向（机器慢时 helper 与 shell 的冷启动仍在预算内），于是「两秒内能否把 helper 与 Git Bash 拉起来」不再决定用例成败。
   */
  private static final Duration TIMEOUT_TRIGGERED_BY_THE_TEST = Duration.ofMinutes(5);

  @TempDir Path workspaceRoot;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  /** 超时由测试触发的调度器：需要「deadline 何时到达由屏障决定」的用例使用它。 */
  private final ControlledScheduler timer = new ControlledScheduler();

  @AfterEach
  void closeExecutors() {
    scheduler.shutdownNow();
    timer.shutdownNow();
    executor.shutdownNow();
  }

  /** 大到无法表示的超时预算等价于「没有 deadline」，绝不能因为算术溢出退化成立即超时。 */
  @Test
  void unrepresentableTimeoutBudgetDoesNotDegradeIntoImmediateTimeout() throws Exception {
    CodingToolsConfig config = config();
    String arguments =
        "{\"command\":\"printf 'survives-overflow\\n'\",\"workdir\":" + json(workspaceRoot) + "}";
    for (Duration enormous :
        List.of(Duration.ofSeconds(Long.MAX_VALUE / 2), Duration.ofNanos(Long.MAX_VALUE / 2))) {
      EnvironmentCapabilityResult result = invoke(bash(config), arguments, enormous);
      assertFalse(result.error(), "超时预算溢出不得退化成立即超时：" + text(result));
      assertTrue(text(result).contains("survives-overflow"), text(result));
    }
  }

  /**
   * 超时必须保留已捕获输出并明确区分超时与退出码：小输出仍内联返回，且收尾不产生任何 durable 或残留中转文件。
   *
   * <p>触发时机由屏障决定：命令先把那一行写出来并落下就绪标记，测试看到标记之后才让 deadline 到达，因此「超时保留已捕获输出」不会 退化成「机器必须在启动预算内把 helper 与
   * shell 拉起来」。
   */
  @Test
  void timeoutKeepsCapturedOutputWithoutLeavingStagingResidue() throws Exception {
    CodingToolsConfig config = config();
    Path ready = workspaceRoot.resolve("timeout-small.ready");
    RecordingListener listener =
        invokeAsync(
            config,
            executor,
            timer,
            "{\"command\":\"printf 'partial-timeout-output\\\\n'; touch '"
                + embedded(ready)
                + "'; sleep 30\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            TIMEOUT_TRIGGERED_BY_THE_TEST);
    awaitFile(ready);
    timer.fireTimeout();

    assertTrue(listener.await());
    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error(), "超时是失败终态");
    String text = text(result);
    assertTrue(text.contains("partial-timeout-output"), "超时不得丢弃已捕获输出：" + text);
    assertTrue(text.contains("Command timed out"), text);
    assertTrue(text.contains("not rolled back"), "取消或失败不得假称副作用回滚：" + text);

    JsonNode process = details(result).path("process");
    assertEquals("TIMED_OUT", process.path("outcome").asText(), details(result).toString());
    assertTrue(process.path("exitCode").isMissingNode(), "被杀进程的退出码不是命令的事实:" + process);

    assertEquals(1, listener.completions, "每次调用只能有一个终态回调");
    assertTrue(config.textOutputStore().publishedFiles().isEmpty(), "小输出不得产生 durable 文件");
    assertTrue(config.textOutputStore().partialFiles().isEmpty(), "收尾不得残留未发布中转文件");
  }

  /**
   * 超时前已经落盘的输出必须发布为 durable 全文：唯一的中转文件只能被发布，不能被 close 删除。
   *
   * <p>同时断言终态说明不进入全文、也不计入捕获总量，因此 {@code totalLines} 仍忠实等于进程自己的输出行数。
   *
   * <p>触发时机由屏障决定：命令写完 5000 行之后才落下就绪标记，测试看到标记才让 deadline 到达。因此这些字节要么已经在管道里、要么 已经被捕获，收尾读出的一定是完整 5000
   * 行；慢机器上 helper 与 Git Bash 的冷启动不再影响结论。标记由 shell 内建写入，然后 exec 等待进程，避免 shell 为被杀子进程追加平台相关的 stderr。
   */
  @Test
  void timeoutPublishesSpilledOutputAndKeepsCountsFaithful() throws Exception {
    CodingToolsConfig config = config();
    Path ready = workspaceRoot.resolve("timeout-spill.ready");
    RecordingListener listener =
        invokeAsync(
            config,
            executor,
            timer,
            "{\"command\":\"seq 1 5000; : > '"
                + embedded(ready)
                + "'; exec sleep 30\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            TIMEOUT_TRIGGERED_BY_THE_TEST);
    awaitFile(ready);
    timer.fireTimeout();

    assertTrue(listener.await());
    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error(), text(result));

    JsonNode textOutput = details(result).path("textOutput");
    assertEquals("TIMED_OUT", details(result).path("process").path("outcome").asText());
    assertFalse(textOutput.path("captureTruncated").asBoolean(), textOutput.toString());
    assertEquals(5000, textOutput.path("totalLines").asLong(), "终态说明不得计入捕获总量：" + textOutput);

    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(published.isAbsolute());
    assertTrue(Files.isRegularFile(published), "唯一的中转文件必须被发布为该路径");
    List<String> lines = Files.readAllLines(published);
    assertEquals(5000, lines.size());
    assertEquals("1", lines.getFirst());
    assertEquals("5000", lines.getLast(), "durable 全文必须是纯进程输出，不含终态说明");
    assertTrue(text(result).contains(published.toString()), "预览必须内联已发布路径：" + text(result));
    assertTrue(config.textOutputStore().partialFiles().isEmpty(), "发布后不得残留中转文件");
  }

  /** 取消与超时对称：即使没有 deadline，取消也保留已捕获输出、只回调一次，并把取消与退出码区分开。 */
  @Test
  void cancellationKeepsCapturedOutputAndIsIdempotent() throws Exception {
    Path marker = workspaceRoot.resolve("cancel-marker");
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"printf 'before-cancel-output\\\\n'; touch '"
                + embedded(marker)
                + "'; sleep 30\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ZERO);
    awaitFile(marker);

    listener.handle.cancel();
    listener.handle.cancel();
    assertTrue(listener.await());

    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error());
    String text = text(result);
    assertTrue(text.contains("before-cancel-output"), "取消不得丢弃已捕获输出：" + text);
    assertTrue(text.contains("Operation cancelled"), text);
    assertTrue(text.contains("not rolled back"), text);
    JsonNode process = details(result).path("process");
    assertEquals("CANCELLED", process.path("outcome").asText());
    assertTrue(process.path("exitCode").isMissingNode(), "取消不是命令退出：" + process);
    assertEquals(1, listener.completions, "重复取消仍只能有一个终态回调");
  }

  /** 取消已经落盘的大输出时同样必须发布 durable 全文，不能因为 close 而丢失唯一副本。 */
  @Test
  void cancellationPublishesSpilledOutput() throws Exception {
    Path marker = workspaceRoot.resolve("cancel-spill-marker");
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"seq 1 5000; touch '"
                + embedded(marker)
                + "'; sleep 30\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ZERO);
    awaitFile(marker);

    listener.handle.cancel();
    assertTrue(listener.await());

    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error(), text(result));
    JsonNode textOutput = details(result).path("textOutput");
    assertEquals("CANCELLED", details(result).path("process").path("outcome").asText());
    Path published = Path.of(textOutput.path("path").asText());
    assertTrue(Files.isRegularFile(published), "取消也必须发布已捕获的 durable 全文");
    assertEquals(5000, Files.readAllLines(published).size());
  }

  /** 成功与非零退出必须在同一份 details 形状里给出可判定的 outcome 与权威退出码，成功不追加任何终结说明。 */
  @Test
  void exitOutcomesCarryAuthoritativeExitCode() throws Exception {
    EnvironmentCapabilityResult success =
        invoke(
            bash(config()),
            "{\"command\":\"echo ok\",\"workdir\":" + json(workspaceRoot) + "}",
            Duration.ZERO);
    assertFalse(success.error(), text(success));
    assertEquals("EXITED", details(success).path("process").path("outcome").asText());
    assertEquals(0, details(success).path("process").path("exitCode").asInt());
    assertEquals("ok\n", text(success), "成功输出不含任何合成说明");

    EnvironmentCapabilityResult nonZero =
        invoke(
            bash(config()),
            "{\"command\":\"echo failure; exit 9\",\"workdir\":" + json(workspaceRoot) + "}",
            Duration.ZERO);
    assertTrue(nonZero.error());
    assertEquals("EXITED", details(nonZero).path("process").path("outcome").asText());
    assertEquals(9, details(nonZero).path("process").path("exitCode").asInt());
    assertTrue(text(nonZero).contains("Command exited with code 9"), text(nonZero));
  }

  /**
   * 运行时的超时收尾必须报告为超时而不是取消：原因由运行时显式传递，能力不得凭自己的标记臆测。
   *
   * <p>这条测试锁住「distinct 区分 timeout/cancel」：同一个 handle 收到 {@code terminate(TIMED_OUT)} 时，终态说明与 {@code
   * process.outcome} 都必须是超时。
   */
  @Test
  void runtimeTimeoutTerminationReportsTimedOutOutcome() throws Exception {
    Path marker = workspaceRoot.resolve("runtime-timeout-marker");
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"printf 'runtime-timeout-output\\\\n'; touch '"
                + embedded(marker)
                + "'; sleep 30\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ZERO);
    awaitFile(marker);

    assertFalse(listener.handle.isCancelled(), "尚未收尾的调用不应报告已终止");
    listener.handle.terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
    assertTrue(listener.handle.isCancelled(), "超时收尾同样是「已被请求终止」");
    assertTrue(listener.await());

    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error());
    String text = text(result);
    assertTrue(text.contains("runtime-timeout-output"), "超时收尾不得丢弃已捕获输出：" + text);
    assertTrue(text.contains("Command timed out"), text);
    assertFalse(text.contains("Operation cancelled"), "超时不得被报告成调用方取消：" + text);
    assertEquals("TIMED_OUT", details(result).path("process").path("outcome").asText(), text);
  }

  /** 运行时的取消收尾必须报告为取消：原因显式传递后能力仍按取消语义产出说明。 */
  @Test
  void runtimeCancelTerminationReportsCancelledOutcome() throws Exception {
    Path marker = workspaceRoot.resolve("runtime-cancel-marker");
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"printf 'runtime-cancel-output\\\\n'; touch '"
                + embedded(marker)
                + "'; sleep 30\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ZERO);
    awaitFile(marker);

    listener.handle.terminate(EnvironmentCapabilityTerminationCause.CANCELLED);
    assertTrue(listener.await());

    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error());
    String text = text(result);
    assertTrue(text.contains("runtime-cancel-output"), text);
    assertTrue(text.contains("Operation cancelled"), text);
    assertFalse(text.contains("Command timed out"), text);
    assertEquals("CANCELLED", details(result).path("process").path("outcome").asText(), text);
  }

  /** 无法启动 shell 时必须 fail closed 且不留下任何中转文件：没有进程就没有可发布的输出。 */
  @Test
  void startFailureIsReportedWithoutStagingResidue() throws Exception {
    CodingToolsConfig missingShell =
        TestCodingConfig.withBash(workspaceRoot, 2000, 50 * 1024, "kk-studio-missing-bash");
    EnvironmentCapabilityResult result =
        invoke(
            bash(missingShell),
            "{\"command\":\"echo never-runs\",\"workdir\":" + json(workspaceRoot) + "}",
            Duration.ZERO);

    assertTrue(result.error());
    assertTrue(text(result).contains("kk-studio-missing-bash"), text(result));
    assertEquals("{}", result.detailsJson(), "启动失败时没有捕获事实可报告");
    assertTrue(missingShell.textOutputStore().partialFiles().isEmpty(), "启动失败不得留下幽灵中转文件");
  }

  /**
   * 已取消的调用不得启动 shell，也不得创建任何本地文件：取消是启动前的 fail-closed 前置检查，而不是「先启动再杀掉」。
   *
   * <p>为了让「收尾先于执行线程开始」可确定复现，这里用独占线程的执行器把能力任务排在一个阻塞任务之后，先取消再放开执行。
   */
  @Test
  void preCancelledCallDoesNotStartShellOrTouchStore() throws Exception {
    CodingToolsConfig config = config();
    Path marker = workspaceRoot.resolve("pre-cancel-marker");
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService gated = gatedExecutor(occupied, release);
    try {
      assertTrue(occupied.await(10, TimeUnit.SECONDS), "阻塞任务必须先占住执行线程");
      RecordingListener listener =
          invokeAsync(
              config,
              gated,
              "{\"command\":\"touch '"
                  + embedded(marker)
                  + "'\",\"workdir\":"
                  + json(workspaceRoot)
                  + "}",
              Duration.ofSeconds(5));
      listener.handle.cancel();
      release.countDown();

      assertTrue(listener.await());
      EnvironmentCapabilityResult result = listener.result;
      assertTrue(result.error());
      assertTrue(text(result).contains("Operation cancelled"), text(result));
      assertEquals(
          "CANCELLED", details(result).path("process").path("outcome").asText(), text(result));
      assertEquals(1, listener.completions, "启动前的收尾同样只能有一个终态回调");
      assertFalse(Files.exists(marker), "已取消的调用不得执行命令，也不得留下命令副作用");
      assertTrue(config.textOutputStore().partialFiles().isEmpty(), "不得创建中转文件");
      assertTrue(config.textOutputStore().publishedFiles().isEmpty(), "不得创建 durable 文件");
    } finally {
      release.countDown();
      gated.shutdownNow();
    }
  }

  /** 运行时的超时收尾若早于进程启动到达，必须同样不启动 shell：超时与取消共享同一条启动前检查。 */
  @Test
  void preTimedOutCallDoesNotStartShellOrTouchStore() throws Exception {
    CodingToolsConfig config = config();
    Path marker = workspaceRoot.resolve("pre-timeout-marker");
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService gated = gatedExecutor(occupied, release);
    try {
      assertTrue(occupied.await(10, TimeUnit.SECONDS), "阻塞任务必须先占住执行线程");
      RecordingListener listener =
          invokeAsync(
              config,
              gated,
              "{\"command\":\"touch '"
                  + embedded(marker)
                  + "'\",\"workdir\":"
                  + json(workspaceRoot)
                  + "}",
              Duration.ofSeconds(5));
      listener.handle.terminate(EnvironmentCapabilityTerminationCause.TIMED_OUT);
      release.countDown();

      assertTrue(listener.await());
      EnvironmentCapabilityResult result = listener.result;
      assertTrue(result.error());
      String text = text(result);
      assertTrue(text.contains("Command timed out"), text);
      assertFalse(text.contains("Operation cancelled"), "超时不得被报告成取消：" + text);
      assertEquals("TIMED_OUT", details(result).path("process").path("outcome").asText(), text);
      assertFalse(Files.exists(marker), "超时收尾不得执行命令副作用");
      assertTrue(config.textOutputStore().partialFiles().isEmpty());
    } finally {
      release.countDown();
      gated.shutdownNow();
    }
  }

  /**
   * 命令以参数传入，能力不向 stdin 写数据：等待 EOF 的命令必须自然退出，而不是一直阻塞到超时。
   *
   * <p>两条调用都不设 deadline：本用例断言的是「stdin 的写端已经关闭」，期限只会把它变成对机器启动速度的断言——命令启动慢时 deadline
   * 先到，收尾去向就不再是自然退出。真正需要的是「命令必须自己结束」，这由 {@code listener.await()} 的有界等待守卫。
   */
  @Test
  void closesStdinSoCommandsWaitingForEofFinishNaturally() throws Exception {
    EnvironmentCapabilityResult readUntilEof =
        invoke(
            bash(config()),
            "{\"command\":\"cat\",\"workdir\":" + json(workspaceRoot) + "}",
            Duration.ZERO);

    assertFalse(readUntilEof.error(), "等待 EOF 的命令必须以成功退出收尾：" + text(readUntilEof));
    JsonNode process = details(readUntilEof).path("process");
    assertEquals("EXITED", process.path("outcome").asText());
    assertEquals(0, process.path("exitCode").asInt());
    assertEquals("", text(readUntilEof));

    // 完全不读 stdin 的命令不受影响：没有写端就不存在中途写失败。
    EnvironmentCapabilityResult ignoringStdin =
        invoke(
            bash(config()),
            "{\"command\":\"echo done\",\"workdir\":" + json(workspaceRoot) + "}",
            Duration.ZERO);
    assertFalse(ignoringStdin.error(), text(ignoringStdin));
    assertEquals("done\n", text(ignoringStdin));
  }

  /** stdout 是调用方载荷：超过管道缓冲与内联阈值时仍必须完整发布，排空绝不停在某个输出体积上。 */
  @Test
  void keepsPayloadBeyondPipeBufferComplete() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 构造大输出");
    int bytes = 300_000;
    EnvironmentCapabilityResult result =
        invoke(
            bash(config()),
            "{\"command\":\"yes A | head -c "
                + bytes
                + "\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ofSeconds(30));

    assertFalse(result.error(), text(result));
    JsonNode textOutput = details(result).path("textOutput");
    assertEquals(bytes, textOutput.path("totalBytes").asLong(), "捕获总量必须等于进程输出字节数");
    assertEquals(bytes / 2, textOutput.path("totalLines").asLong(), "每行两字节");
    Path published = Path.of(textOutput.path("path").asText());
    assertEquals(bytes, Files.size(published), "durable 全文必须与进程输出等长");
  }

  /**
   * 用户命令必须能自己处理温和信号：范围收尾先对整组广播 SIGTERM，宽限窗口之后才强杀，脚本因此可以用 {@code trap} 做优雅清理。
   *
   * <p>helper 自己忽略 SIGTERM——否则父进程广播整组信号时它会被提前打死、来不及收敛；但命令不能继承这个忽略状态：POSIX 规定非交互 shell
   * 无法注册「进入时已被忽略」的信号，继承下去等于用户脚本永远等不到 TERM trap，收尾会直接从温和信号跳到强杀。
   */
  @Test
  void commandCanTrapTerminationBeforeTheForceKill() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与信号语义");
    Path marker = workspaceRoot.resolve("term-trap.log");
    Path ready = workspaceRoot.resolve("term-trap.ready");
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"trap 'echo trapped >> "
                + embedded(marker)
                + "; exit 0' TERM; echo ready >> "
                + embedded(ready)
                + "; while true; do sleep 0.05; done\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ofSeconds(30));
    awaitFile(ready);

    listener.handle.cancel();
    assertTrue(listener.await());

    assertTrue(lineCount(marker) > 0, "命令必须能自己处理温和信号，trap 必须真的执行：" + text(listener.result));
  }

  /** stdout 与 stderr 合并到同一管道：两路同时写满管道缓冲时仍必须完成，不能互相等待。 */
  @Test
  void mergedStreamsBeyondPipeBufferCompleteWithoutDeadlock() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 构造并发大输出");
    int bytes = 512 * 1024;
    long started = System.nanoTime();
    EnvironmentCapabilityResult result =
        invoke(
            bash(config()),
            "{\"command\":\"(yes O | head -c "
                + bytes
                + ") & (yes E | head -c "
                + bytes
                + " 1>&2) & wait\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ofSeconds(30));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    assertFalse(result.error(), text(result));
    assertEquals(
        bytes * 2L,
        details(result).path("textOutput").path("totalBytes").asLong(),
        "合并流必须完整捕获两路输出");
    assertTrue(elapsedMillis < 25_000, "并发排空必须在 deadline 内完成，实际 " + elapsedMillis + "ms");
  }

  /** 超时必须终止整棵进程树：主进程与后代在收尾后都不得继续写入。 */
  @Test
  void timeoutTerminatesWholeProcessTree() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程树语义");
    CodingToolsConfig config = config();
    Path marker = workspaceRoot.resolve("timeout-ticks.log");
    RecordingListener listener =
        invokeAsync(
            config,
            executor,
            timer,
            "{\"command\":\"(while true; do echo child >> '"
                + embedded(marker)
                + "'; sleep 0.05; done) & while true; do echo parent >> '"
                + embedded(marker)
                + "'; sleep 0.05; done\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            TIMEOUT_TRIGGERED_BY_THE_TEST);
    // 屏障：整棵进程树已经在产出输出（两个写者都写同一个标记文件），此时才让 deadline 到达。
    awaitFile(marker);
    timer.fireTimeout();

    assertTrue(listener.await());
    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error(), text(result));
    assertEquals("TIMED_OUT", details(result).path("process").path("outcome").asText());
    long ticksAtTermination = lineCount(marker);
    assertTrue(ticksAtTermination > 0, "超时前进程树必须已经在产出输出");
    Thread.sleep(700);
    assertEquals(ticksAtTermination, lineCount(marker), "超时必须终止整棵进程树，后代不得继续写入");
  }

  /** 取消与超时对称：进程树必须整体收敛，且不把 kill 的退出码当成命令事实。 */
  @Test
  void cancellationTerminatesWholeProcessTree() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程树语义");
    Path marker = workspaceRoot.resolve("cancel-ticks.log");
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\"(while true; do echo child >> '"
                + embedded(marker)
                + "'; sleep 0.05; done) & while true; do echo parent >> '"
                + embedded(marker)
                + "'; sleep 0.05; done\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ZERO);
    awaitFile(marker);

    listener.handle.cancel();
    assertTrue(listener.await());

    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error(), text(result));
    JsonNode process = details(result).path("process");
    assertEquals("CANCELLED", process.path("outcome").asText(), text(result));
    assertTrue(process.path("exitCode").isMissingNode(), "取消不是命令退出：" + process);
    long ticksAtTermination = lineCount(marker);
    Thread.sleep(500);
    assertEquals(ticksAtTermination, lineCount(marker), "取消必须终止整棵进程树，后代不得继续写入");
  }

  /**
   * 调度超时被拒（例如运行时已停机）时，整棵进程树必须在终态通知之前收敛：callback 一旦触发，调用方就据此认为调用已结束，不得再有进程在写输出或改副作用。
   *
   * <p>确定性构造：调度器先放行命令，等它就绪（主进程与后台子进程的 PID 都已落盘、就绪标记已写出）后才抛 {@code RejectedExecutionException}，
   * 因此触发收尾时进程树已经成形，不存在「fork 晚于快照」的竞态。整树存活情况只在 onComplete 这个事件点采集，不使用等待掩盖收尾顺序； 「先温和后强制」的优雅契约由 {@code
   * ProcessScopeTest.terminateLetsTheCommandRunItsTerminationTrap} 单独守护，这里不要求每个进程都执行 TERM trap。
   */
  @Test
  void rejectedTimeoutSchedulingConvergesBeforeTerminalCallback() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程树语义");
    CodingToolsConfig config = config();
    Path pidFile = workspaceRoot.resolve("rejected-schedule.pid");
    Path readyFile = workspaceRoot.resolve("rejected-schedule.ready");
    CountDownLatch gate = new CountDownLatch(1);
    ScheduledExecutorService gated = rejectingScheduler(gate);
    try {
      RecordingListener listener =
          invokeAsync(
              new BashCapability(config, executor, gated),
              "{\"command\":\""
                  + trackedCommand(pidFile, readyFile)
                  + "wait\",\"workdir\":"
                  + json(workspaceRoot)
                  + "}",
              Duration.ofSeconds(30));
      awaitFile(readyFile);
      List<Long> tree = trackedTree(pidFile);
      AtomicReference<List<Long>> aliveAtCompletion = new AtomicReference<>();
      listener.observeAtCompletion(() -> aliveAtCompletion.set(aliveMembers(tree)));
      gate.countDown();

      assertTrue(listener.await(), "调度失败必须以终态收敛，而不是把调用悬在能力里");
      EnvironmentCapabilityResult result = listener.result;
      assertTrue(result.error(), text(result));
      assertTrue(text(result).contains("Error:"), text(result));
      assertEquals(1, listener.completions, "调度失败同样只能有一个终态回调");
      assertEquals(List.of(), aliveAtCompletion.get(), "终态通知时整棵进程树必须已经收敛：" + tree);
      assertTrue(config.textOutputStore().partialFiles().isEmpty(), "不得残留中转文件");
      assertTrue(config.textOutputStore().publishedFiles().isEmpty(), "不得创建 durable 文件");
    } finally {
      gate.countDown();
      gated.shutdownNow();
    }
  }

  /**
   * 监听器抛错时：异常不能泄露为「静默无终态」，已捕获输出必须随失败说明返回，且整棵进程树必须在终态通知之前收敛。
   *
   * <p>partial 回调在测试放行后才抛错，因此「异常发生在排空循环中」与「异常发生时进程树已在产出输出」都是确定性事实； 整树存活情况同样只在 onComplete
   * 这个事件点采集，不使用等待。
   */
  @Test
  void listenerFailureConvergesAndKeepsCapturedOutput() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 构造触发 partial 的大输出");
    Path pidFile = workspaceRoot.resolve("listener-failure.pid");
    Path readyFile = workspaceRoot.resolve("listener-failure.ready");
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<EnvironmentCapabilityResult> failure = new AtomicReference<>();
    AtomicReference<List<Long>> tree = new AtomicReference<>();
    AtomicReference<List<Long>> aliveAtCompletion = new AtomicReference<>();
    EnvironmentCapabilityExecutionListener exploding =
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("listener exploded");
          }

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            aliveAtCompletion.set(aliveMembers(tree.get()));
            failure.set(result);
            completed.countDown();
          }

          @Override
          public void onError(Throwable error) {
            throw new AssertionError(error);
          }
        };

    BashCapability capability = bash(config());
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall(
                "call",
                "{\"command\":\""
                    + trackedCommand(pidFile, readyFile)
                    + "yes X | head -c 200000\",\"workdir\":"
                    + json(workspaceRoot)
                    + "}"),
            Duration.ofSeconds(30)),
        exploding);

    awaitFile(readyFile);
    tree.set(trackedTree(pidFile));
    release.countDown();
    assertTrue(completed.await(15, TimeUnit.SECONDS), "监听器抛错后仍必须提交唯一终态");

    EnvironmentCapabilityResult result = failure.get();
    assertTrue(result.error(), text(result));
    String text = text(result);
    assertTrue(text.contains("listener exploded"), "失败原因必须随终态返回：" + text);
    Path published = Path.of(details(result).path("textOutput").path("path").asText());
    assertTrue(Files.isRegularFile(published), "已捕获输出必须被发布，不能随异常丢弃");
    assertTrue(Files.size(published) > 0, "已捕获输出不得为空");
    assertEquals(List.of(), aliveAtCompletion.get(), "终态通知时整棵进程树必须已经收敛：" + tree.get());
  }

  /**
   * 自然退出同样必须先收敛执行范围再通知终态：命令结束后不得留下任何普通同组后台后代。
   *
   * <p>PID 与就绪标记都由命令自己落盘，因此 onComplete 这个事件点上采集到的存活集合是确定性事实，而不是等待出来的
   * 结论；「整组已收敛」由进程组判定给出，已退出但尚未回收的僵尸不会被误判为存活。
   */
  @Test
  void naturalExitReapsBackgroundDescendantsBeforeTerminalCallback() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程组语义");
    Path pidFile = workspaceRoot.resolve("natural-exit.pid");
    Path readyFile = workspaceRoot.resolve("natural-exit.ready");
    RecordingListener listener =
        invokeAsync(
            bash(config()),
            "{\"command\":\""
                + trackedCommand(pidFile, readyFile)
                + "sleep 0.3; exit 0\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ZERO);
    awaitFile(readyFile);
    List<Long> tree = trackedTree(pidFile);
    AtomicReference<List<Long>> aliveAtCompletion = new AtomicReference<>();
    listener.observeAtCompletion(() -> aliveAtCompletion.set(aliveMembers(tree)));

    assertTrue(listener.await());
    EnvironmentCapabilityResult result = listener.result;
    assertFalse(result.error(), text(result));
    assertEquals("EXITED", details(result).path("process").path("outcome").asText(), text(result));
    assertEquals(0, details(result).path("process").path("exitCode").asInt());
    assertEquals(List.of(), aliveAtCompletion.get(), "自然退出后终态通知时整棵进程树必须已收敛：" + tree);
  }

  /**
   * 后台作业持有 stdout 时，自然退出仍必须立刻结束：否则排空循环会一直等到那个作业自己退出。
   *
   * <p>确定性事实是「自然退出必须收敛持有管道写端的后代」：若收尾没有整组收敛，读取会在 sleep 60 上阻塞，测试会在调用 超时或 15 秒上界上失败。
   */
  @Test
  void naturalExitCompletesEvenWhenBackgroundJobHoldsStdout() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与后台作业");
    Path pidFile = workspaceRoot.resolve("holding-stdout.pid");
    long started = System.nanoTime();
    EnvironmentCapabilityResult result =
        invoke(
            bash(config()),
            "{\"command\":\"sleep 60 & echo $! >> "
                + embedded(pidFile)
                + "; echo done\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ofSeconds(30));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    assertFalse(result.error(), text(result));
    assertEquals("done\n", text(result));
    assertEquals(0, details(result).path("process").path("exitCode").asInt());
    assertTrue(elapsedMillis < 15_000, "持有 stdout 的后台作业不得拖住自然退出，实际 " + elapsedMillis + "ms");
    for (long pid : recordedPids(pidFile)) {
      assertFalse(alive(pid), "自然退出必须收敛持有 stdout 的后台后代 " + pid);
    }
  }

  /** 用户的 EXIT trap 与精确退出码都必须原样穿过执行范围：helper 的收尾退出码绝不是命令的事实。 */
  @Test
  void userExitTrapAndExactExitCodeSurviveTheScope() throws Exception {
    EnvironmentCapabilityResult result =
        invoke(
            bash(config()),
            "{\"command\":\"trap 'echo trap-ran' EXIT; exit 42\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ofSeconds(30));

    assertTrue(result.error());
    assertEquals("EXITED", details(result).path("process").path("outcome").asText(), text(result));
    assertEquals(42, details(result).path("process").path("exitCode").asInt());
    assertTrue(text(result).contains("trap-ran"), text(result));
    assertTrue(text(result).contains("Command exited with code 42"), text(result));
  }

  /** {@code exec} 让命令替换掉 shell 本身：进程还是同一个，退出码必须原样保留。 */
  @Test
  void execReplacedShellKeepsTheCommandExitCode() throws Exception {
    EnvironmentCapabilityResult result =
        invoke(
            bash(config()),
            "{\"command\":\"exec sh -c 'exit 7'\",\"workdir\":" + json(workspaceRoot) + "}",
            Duration.ofSeconds(30));

    assertTrue(result.error());
    assertEquals(7, details(result).path("process").path("exitCode").asInt());
    assertEquals("EXITED", details(result).path("process").path("outcome").asText());
  }

  /** {@code disown} 只从 shell 的作业表里摘掉作业，进程组不受影响：自然退出必须照样收敛它。 */
  @Test
  void disownedBackgroundJobIsReapedOnNaturalExit() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与 disown");
    Path pidFile = workspaceRoot.resolve("disowned.pid");
    EnvironmentCapabilityResult result =
        invoke(
            bash(config()),
            "{\"command\":\"sleep 60 & echo $! >> "
                + embedded(pidFile)
                + "; disown; echo disowned\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ofSeconds(30));

    assertFalse(result.error(), text(result));
    assertEquals(0, details(result).path("process").path("exitCode").asInt());
    List<Long> pids = recordedPids(pidFile);
    assertFalse(pids.isEmpty(), "命令必须记录被 disown 的作业 PID");
    for (long pid : pids) {
      assertFalse(alive(pid), "被 disown 的同组作业也必须随自然退出收敛 " + pid);
    }
  }

  /** 嵌套 fork 的后代仍在同一个执行范围内：自然退出时整棵嵌套结构都必须收敛。 */
  @Test
  void nestedForkDescendantsAreReapedOnNaturalExit() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与嵌套 fork");
    Path pidFile = workspaceRoot.resolve("nested.pid");
    EnvironmentCapabilityResult result =
        invoke(
            bash(config()),
            "{\"command\":\"sh -c 'sleep 60 & echo $! >> "
                + embedded(pidFile)
                + "; wait' & sleep 0.3; exit 0\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ofSeconds(30));

    assertFalse(result.error(), text(result));
    assertEquals(0, details(result).path("process").path("exitCode").asInt());
    for (long pid : recordedPids(pidFile)) {
      assertFalse(alive(pid), "嵌套 fork 的后代也必须随自然退出收敛 " + pid);
    }
  }

  /**
   * 忽略温和信号的后代必须在强杀阶段收敛：这是对「只发一次 SIGTERM」实现的显式反向断言。
   *
   * <p>timeout 触发整组收敛，后台子进程显式忽略 TERM 并持续写入；若收尾没有升级到强制信号，标记文件会在终态之后继续 增长。
   */
  @Test
  void termIgnoringDescendantIsForceKilledOnTimeout() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与信号语义");
    CodingToolsConfig config = config();
    Path marker = workspaceRoot.resolve("ignore-term-ticks.log");
    RecordingListener listener =
        invokeAsync(
            config,
            executor,
            timer,
            "{\"command\":\"(trap '' TERM; while true; do echo tick >> '"
                + embedded(marker)
                + "'; sleep 0.05; done) & while true; do sleep 0.05; done\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            TIMEOUT_TRIGGERED_BY_THE_TEST);
    // 屏障：忽略 TERM 的后代已经在产出输出，此时才让 deadline 到达。
    awaitFile(marker);
    timer.fireTimeout();

    assertTrue(listener.await());
    EnvironmentCapabilityResult result = listener.result;
    assertTrue(result.error(), text(result));
    assertEquals(
        "TIMED_OUT", details(result).path("process").path("outcome").asText(), text(result));
    long ticksAtTermination = lineCount(marker);
    assertTrue(ticksAtTermination > 0, "超时前忽略 TERM 的后代必须已经在产出输出");
    Thread.sleep(500);
    assertEquals(ticksAtTermination, lineCount(marker), "忽略温和信号的后代必须在强杀阶段收敛，不得继续写入");
  }

  /**
   * 调用私有的进程范围状态目录必须在收尾时被删除，且与命令去向无关。
   *
   * <p>状态目录只在调用期间承载 helper 的握手文件；残留会把私有握手信息留在本地临时目录里。
   */
  @Test
  void privateScopeStateIsRemovedAfterEveryOutcome() throws Exception {
    Set<String> before = scopeStateEntries();
    invoke(
        bash(config()),
        "{\"command\":\"echo ok\",\"workdir\":" + json(workspaceRoot) + "}",
        Duration.ZERO);

    Path marker = workspaceRoot.resolve("scope-cleanup-marker");
    RecordingListener cancelled =
        invokeAsync(
            bash(config()),
            "{\"command\":\"touch '"
                + embedded(marker)
                + "'; sleep 30\",\"workdir\":"
                + json(workspaceRoot)
                + "}",
            Duration.ZERO);
    awaitFile(marker);
    cancelled.handle.cancel();
    assertTrue(cancelled.await());
    assertTrue(cancelled.result.error());

    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!scopeStateEntries().equals(before) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(before, scopeStateEntries(), "调用结束必须删除私有的进程范围状态目录");
  }

  /** 临时目录里当前存在的进程范围状态目录名；只比较测试前后差集，不关心其它调用留下的历史残留。 */
  private static Set<String> scopeStateEntries() throws IOException {
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    try (var entries = Files.list(tmp)) {
      Set<String> names = new TreeSet<>();
      entries
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith(ProcessScope.STATE_DIR_PREFIX))
          .forEach(names::add);
      return names;
    }
  }

  private BashCapability bash(CodingToolsConfig config) {
    return new BashCapability(config, executor, scheduler);
  }

  private CodingToolsConfig config() {
    return TestCodingConfig.withBash(workspaceRoot, 2000, 50 * 1024, bashExecutable());
  }

  /**
   * 真实 bash 可执行文件。
   *
   * <p>Windows 上不能用裸名 {@code bash}：{@code System32\bash.exe} 是 WSL 的转发程序，而 {@code CreateProcess}
   * 的搜索顺序 让系统目录永远先于 PATH，因此裸名只会启动一个没有发行版的 WSL 并以退出码 1 结束。生产侧同样需要 operator 用 {@code
   * --bash-executable} 指向 Git Bash，这里只是把同一件事在测试里固定下来。
   */
  private static String bashExecutable() {
    if (!isWindows()) {
      return "bash";
    }
    for (Path candidate : gitBashCandidates()) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toString();
      }
    }
    assumeTrue(false, "需要 Git Bash：Windows 上裸名 bash 只会命中 System32 的 WSL 转发程序");
    return "bash";
  }

  private static List<Path> gitBashCandidates() {
    List<Path> candidates = new ArrayList<>();
    for (String variable : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
      String root = System.getenv(variable);
      if (root != null && !root.isBlank()) {
        candidates.add(Path.of(root, "Git", "bin", "bash.exe"));
      }
    }
    String localAppData = System.getenv("LOCALAPPDATA");
    if (localAppData != null && !localAppData.isBlank()) {
      candidates.add(Path.of(localAppData, "Programs", "Git", "bin", "bash.exe"));
    }
    return candidates;
  }

  /**
   * 命令文本里内嵌的路径。
   *
   * <p>统一成 {@code /}：同一个值既不会破坏外层 JSON（Windows 反斜杠会被当成非法转义），也不会被 Git Bash 与 POSIX shell 区别对待。
   */
  private static String embedded(Path value) {
    return value.toString().replace('\\', '/');
  }

  private EnvironmentCapabilityResult invoke(
      BashCapability capability, String arguments, Duration timeout) throws Exception {
    RecordingListener listener = invokeAsync(capability, arguments, timeout);
    assertTrue(listener.await());
    assertNotNull(listener.result);
    return listener.result;
  }

  private RecordingListener invokeAsync(
      BashCapability capability, String arguments, Duration timeout) {
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(), new EnvironmentCapabilityCall("call", arguments), timeout),
            listener);
    return listener;
  }

  /** 用指定执行器发起调用：调用方决定能力任务何时真正开始，便于构造启动前收尾的确定性时序。 */
  private RecordingListener invokeAsync(
      CodingToolsConfig config, ExecutorService target, String arguments, Duration timeout) {
    return invokeAsync(config, target, scheduler, arguments, timeout);
  }

  /**
   * 用指定 scheduler 发起调用：超时何时到达由测试决定，而不是由真实时钟决定。
   *
   * <p>生产代码把超时做成一次 {@code schedule}：到点后置位 {@code timedOut}，再由执行线程收敛整组。真实时钟下这次调度会和 helper 与 shell
   * 的冷启动赛跑，断言「已经捕获的输出」的用例因此会变成对机器启动速度的断言；这里换上传入的 scheduler，触发时机就落在测试手里。
   */
  private RecordingListener invokeAsync(
      CodingToolsConfig config,
      ExecutorService target,
      ScheduledExecutorService timer,
      String arguments,
      Duration timeout) {
    BashCapability capability = new BashCapability(config, target, timer);
    RecordingListener listener = new RecordingListener();
    listener.handle =
        capability.execute(
            new EnvironmentCapabilityExecutionRequest(
                capability.descriptor(), new EnvironmentCapabilityCall("call", arguments), timeout),
            listener);
    return listener;
  }

  /** 独占线程的执行器：先占住唯一线程，能力任务排队，从而确定性地构造「收尾先于执行线程开始」。 */
  private static ExecutorService gatedExecutor(CountDownLatch occupied, CountDownLatch release) {
    ExecutorService gated = Executors.newSingleThreadExecutor();
    gated.execute(
        () -> {
          occupied.countDown();
          try {
            release.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    return gated;
  }

  /** 命令输出的行数；读取失败折叠为 0，避免受检异常逃出断言。 */
  private static long lineCount(Path file) {
    try {
      return Files.readAllLines(file).size();
    } catch (IOException error) {
      return 0;
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * 可确定观测整棵进程树的命令：主进程记录自身 PID，fork 后台子进程并记录它的 PID，最后写出就绪标记并持续等待。
   *
   * <p>就绪标记在 PID 落盘之后写出，因此测试读到它就说明进程树已经成形（不存在「fork 晚于快照」的竞态）； 后台子进程的 PID
   * 必须显式记录，否则「整树已收敛」的断言只会验证主进程。
   */
  private static String trackedCommand(Path pidFile, Path readyFile) {
    return "echo $$ >> "
        + pidFile
        + "; ( while true; do sleep 0.01; done ) & echo $! >> "
        + pidFile
        + "; echo ready >> "
        + readyFile
        + "; ";
  }

  /** 放行后才拒绝的调度器：让命令先真正跑起来，再复现「调度超时被拒」的收尾路径。 */
  private static ScheduledExecutorService rejectingScheduler(CountDownLatch gate) {
    return new ScheduledThreadPoolExecutor(1) {
      @Override
      public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        try {
          gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
        throw new RejectedExecutionException("runtime stopped");
      }
    };
  }

  /**
   * 就绪时刻的整棵进程树：PID 文件记录的成员，加上当时可从这些成员枚举到的全部后代。
   *
   * <p>存活判定沿用 {@link ProcessHandle#isAlive()}（与 {@link ProcessScope} 的收敛判定一致）；后代在收尾后可能被重新挂到 init
   * 之下，但这里记录的是 PID，判定不依赖父子关系。
   */
  private static List<Long> trackedTree(Path pidFile) throws IOException {
    List<Long> recorded = recordedPids(pidFile);
    assertTrue(recorded.size() >= 2, "PID 文件必须记录主进程与后台子进程，整树断言才有意义：" + recorded);
    List<Long> tree = new ArrayList<>(recorded);
    for (long pid : List.copyOf(recorded)) {
      ProcessHandle.of(pid)
          .ifPresent(
              handle -> handle.descendants().forEach(descendant -> tree.add(descendant.pid())));
    }
    return tree.stream().distinct().toList();
  }

  /** 命令自己记录的成员 PID。 */
  private static List<Long> recordedPids(Path pidFile) throws IOException {
    return Files.readAllLines(pidFile).stream()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(Long::parseLong)
        .toList();
  }

  /** 事件点仍然存活的成员；空表示整棵树在通知时已经收敛。 */
  private static List<Long> aliveMembers(List<Long> tree) {
    return tree.stream().filter(BashCapabilityTest::alive).toList();
  }

  private static boolean alive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private static JsonNode details(EnvironmentCapabilityResult result) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson());
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream().map(BashCapabilityTest::text).reduce("", String::concat);
  }

  private static String text(ResultContent content) {
    return content instanceof TextResultContent text ? text.text() : "";
  }

  private static String json(Path value) throws Exception {
    return AbstractCodingCapability.OBJECT_MAPPER.writeValueAsString(value.toString());
  }

  /** 等待命令进入可取消阶段；超时上限保证失败时快速暴露而不是挂起测试。 */
  private static void awaitFile(Path file) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!Files.exists(file) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(file), "命令必须在取消前进入可观测阶段：" + file);
  }

  /**
   * 只捕获超时任务的 scheduler：deadline 何时到达由测试决定，而不是由真实时钟与机器启动速度比赛。
   *
   * <p>捕获到的任务就是生产代码在 deadline 到点时执行的那一个（置位 {@code timedOut} 并让执行线程收敛整组），因此手动运行它与真实
   * 时钟触发走的是同一条路径；这里只是不再让「两秒内能否启动完 helper 与 shell」来决定它何时发生。
   */
  private static final class ControlledScheduler extends ScheduledThreadPoolExecutor {

    /** 真实调度器上的占位延迟：比任何用例都长，因此真正触发超时的只有 {@link #fireTimeout()}。 */
    private static final long PLACEHOLDER_DELAY = 1;

    private final AtomicReference<Runnable> timeoutTask = new AtomicReference<>();

    ControlledScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      timeoutTask.set(command);
      return super.schedule(command, PLACEHOLDER_DELAY, TimeUnit.DAYS);
    }

    /** 手动到达 deadline：等价于真实时钟走到超时点时调度器执行那个任务。 */
    void fireTimeout() {
      Runnable task = timeoutTask.get();
      assertNotNull(task, "调用必须先把超时任务交给 scheduler");
      task.run();
    }
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    private volatile EnvironmentCapabilityResult result;
    private volatile EnvironmentCapabilityExecutionHandle handle;
    private volatile int completions;
    private volatile Runnable completionProbe = () -> {};

    /** 设置终态回调这个事件点的观测动作：探针只采集事实，断言仍留在测试线程里，避免断言异常逃出回调。 */
    private void observeAtCompletion(Runnable probe) {
      completionProbe = probe;
    }

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completionProbe.run();
      completions++;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }

    private boolean await() throws InterruptedException {
      return completed.await(15, TimeUnit.SECONDS);
    }
  }
}
