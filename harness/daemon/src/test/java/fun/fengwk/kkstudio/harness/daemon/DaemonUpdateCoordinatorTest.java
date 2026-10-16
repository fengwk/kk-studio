package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommandCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdatePhase;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResultCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 意图：受管更新编排的每条可达路径都收敛为确定回执——未 READY 拒绝、执行器拒绝/停机、同一 operation 幂等与在途互斥、准备失败与准备崩溃都不得谎报成功，
 * 默认准备器/启动器按数据目录惰性构造且启动失败必须回执 FAILED。
 *
 * <p>幂等与在途互斥必须对并发重放成立：同一 operation 的「已完成判定」「在途认领」「完成入缓存并释放在途认领」由同一同步门串起，重放绝不触发第二次下载。
 */
class DaemonUpdateCoordinatorTest {

  private static final long ASYNC_TIMEOUT_SECONDS = 10L;
  private static final String OP = "11111111-1111-1111-1111-111111111111";
  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String TARGET_VERSION = "1.0.9";
  private static final String ARTIFACT_SHA = "a".repeat(64);

  /** 合成秘密：只用于证明安全失败日志不回显原始失败内容。 */
  private static final String SYNTHETIC_SECRET = "registrationToken-synthetic-value";

  @TempDir Path root;

  private final RecordingSink sink = new RecordingSink();
  private final List<ExecutorService> executors = new ArrayList<>();

  /** 交接顺序的共享事件表：准备器、启动器与出站端口都按发生顺序追加。 */
  private final List<String> events = Collections.synchronizedList(new ArrayList<>());

  @AfterEach
  void shutdownExecutors() {
    executors.forEach(ExecutorService::shutdownNow);
  }

  /** 未 READY（或未绑定 Environment）的连接不得接受更新命令。 */
  @Test
  void rejectsUpdateBeforeTheConnectionIsReady() {
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), false, DaemonOperatingSystem.LINUX);

    assertThrows(DaemonProtocolException.class, () -> coordinator.handleUpdate(updateCommand(OP)));
    assertTrue(sink.isEmpty(), "no receipt may be emitted for a rejected command");
  }

  /** 执行器已停机（关闭竞态）时提交被拒绝：必须回执 FAILED 且不留下在途事实，同一 operation 可重新提交。 */
  @Test
  void reportsFailedWhenTheUpdateExecutorIsRejectedAndReleasesTheInFlightGate() {
    ExecutorService closed = Executors.newSingleThreadExecutor();
    closed.shutdown();
    executors.add(closed);
    DaemonUpdateCoordinator coordinator = coordinator(closed, true, DaemonOperatingSystem.LINUX);

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(
        List.of(DaemonUpdatePhase.ACCEPTED, DaemonUpdatePhase.FAILED), sink.phasesInOrder());
    assertEquals("update executor rejected", sink.resultAt(1).message());

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(
        List.of(
            DaemonUpdatePhase.ACCEPTED,
            DaemonUpdatePhase.FAILED,
            DaemonUpdatePhase.ACCEPTED,
            DaemonUpdatePhase.FAILED),
        sink.phasesInOrder(),
        "a rejected submission must not leave the operation in flight");
  }

  /** 同一 operation 在途期间重发只被忽略：不重复 ACK，也不产生第二次准备。 */
  @Test
  void ignoresRepeatedInFlightOperationAndPreparesOnlyOnce() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger preparations = new AtomicInteger();
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(
        command -> {
          preparations.incrementAndGet();
          awaitRelease(release);
          return preparedOutcome(command.operationId());
        });
    coordinator.setLauncher(prepared -> true);

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());

    coordinator.handleUpdate(updateCommand(OP));
    assertNull(sink.pollNoWait(), "a repeated in-flight operation must not be re-acknowledged");

    release.countDown();
    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(1, preparations.get());
  }

  /** 重连重发：在途重发 ACCEPTED，完成后重放冻结回执，始终不重新准备。 */
  @Test
  void resendAfterReconnectReportsActiveThenFrozenResult() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger preparations = new AtomicInteger();
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(
        command -> {
          preparations.incrementAndGet();
          awaitRelease(release);
          return preparedOutcome(command.operationId());
        });
    coordinator.setLauncher(prepared -> true);

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());

    coordinator.resendAfterReconnect();
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());

    release.countDown();
    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());

    coordinator.resendAfterReconnect();
    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(1, preparations.get());
  }

  /** 在途期间的并发重放必须全部幂等忽略：既有调用被确定地阻塞在准备阶段（不持门），所有重放只能看到「在途」，不重复接受、不第二次准备。 */
  @Test
  void ignoresConcurrentInFlightReplaysWithoutSecondPreparation() throws Exception {
    AtomicInteger preparations = new AtomicInteger();
    CountDownLatch prepareEntered = new CountDownLatch(1);
    CountDownLatch releasePrepare = new CountDownLatch(1);
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(
        command -> {
          preparations.incrementAndGet();
          prepareEntered.countDown();
          awaitRelease(releasePrepare);
          return preparedOutcome(command.operationId());
        });
    coordinator.setLauncher(prepared -> true);

    Replayers replayers = null;
    try {
      coordinator.handleUpdate(updateCommand(OP));
      assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
      assertTrue(prepareEntered.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));

      replayers = new Replayers(coordinator, 8);
      replayers.release();
      replayers.awaitAll();
      assertNull(sink.pollNoWait(), "an in-flight replay must not be re-acknowledged");
    } finally {
      releasePrepare.countDown();
      if (replayers != null) {
        replayers.close();
      }
    }

    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(1, preparations.get(), "in-flight replays must not prepare again");
    assertEquals(1, sink.acceptedCount(), "an operation may be accepted exactly once");
  }

  /** 完成与重放共用同一同步门：测试持门时，完成的「入缓存 + 释放在途认领 + 出站」与重放判定都必须停在门上， 因此两者之间不存在可被重放利用的窗口。 */
  @Test
  void completionAndReplayShareTheUpdateGate() throws Exception {
    AtomicReference<Thread> runner = new AtomicReference<>();
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "run-update");
              runner.set(thread);
              return thread;
            });
    executors.add(executor);
    AtomicInteger preparations = new AtomicInteger();
    CountDownLatch launchEntered = new CountDownLatch(1);
    CountDownLatch releaseLaunch = new CountDownLatch(1);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(root, DaemonOperatingSystem.LINUX, executor, sink, () -> true);
    coordinator.setPreparer(
        command -> {
          preparations.incrementAndGet();
          return preparedOutcome(command.operationId());
        });
    coordinator.setLauncher(
        prepared -> {
          launchEntered.countDown();
          awaitRelease(releaseLaunch);
          return true;
        });

    Replayers replayers = null;
    try {
      coordinator.handleUpdate(updateCommand(OP));
      assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
      assertTrue(launchEntered.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));

      Object updateGate = updateGate(coordinator);
      replayers = new Replayers(coordinator, 1);
      synchronized (updateGate) {
        // runUpdate 已返回 launch，下一步就是「入缓存 + 释放在途认领」：此处持门可确定地把它挡在完成临界区之外。
        releaseLaunch.countDown();
        awaitBlocked(runner.get());
        replayers.release();
        awaitBlocked(replayers.thread(0));
        assertNull(sink.pollNoWait(), "no receipt may be produced while the update gate is held");
      }
      replayers.awaitAll();
    } finally {
      releaseLaunch.countDown();
      if (replayers != null) {
        replayers.close();
      }
    }

    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(1, preparations.get(), "the replay must reuse the frozen receipt");
    assertEquals(1, sink.acceptedCount(), "an operation may be accepted exactly once");
  }

  /** 接受回执本身抛出 RuntimeException 时不得留下永久在途：操作尚未提交，释放在途认领并传播异常，Platform 重新提交后只准备一次、不产生重复副作用。 */
  @Test
  void releasesTheInFlightGateWhenTheAcceptedReceiptFails() throws InterruptedException {
    FailingFirstSendSink failingSink = new FailingFirstSendSink();
    AtomicInteger preparations = new AtomicInteger();
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root, DaemonOperatingSystem.LINUX, singleThread(), failingSink, () -> true);
    coordinator.setPreparer(
        command -> {
          preparations.incrementAndGet();
          return preparedOutcome(command.operationId());
        });
    coordinator.setLauncher(prepared -> true);

    assertThrows(IllegalStateException.class, () -> coordinator.handleUpdate(updateCommand(OP)));
    assertEquals(
        DaemonUpdatePhase.ACCEPTED,
        failingSink.failedPayloadPhase(),
        "the failing receipt is the accepted acknowledgement");
    assertEquals(0, preparations.get(), "a failed acknowledgement must not start a download");
    assertNull(failingSink.pollNoWait(), "a failed receipt must not be recorded as delivered");

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, failingSink.await().phase());
    assertEquals(DaemonUpdatePhase.PREPARED, failingSink.await().phase());
    assertEquals(1, preparations.get(), "a retried operation must prepare exactly once");
  }

  /** 准备阶段的确定性失败必须把去敏原因原样回执为 FAILED，绝不谎报 PREPARED。 */
  @Test
  void reportsPreparationFailureWithoutClaimingSuccess() throws InterruptedException {
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(
        command ->
            new ManagedUpdateOutcome.Failed(
                "update artifact URL is not the official release target"));

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    DaemonUpdateResult failed = sink.await();
    assertEquals(DaemonUpdatePhase.FAILED, failed.phase());
    assertEquals("update artifact URL is not the official release target", failed.message());
  }

  /** 准备阶段抛出异常同样是确定失败：只回执安全说明，不泄漏异常文本。 */
  @Test
  void reportsPreparerCrashAsDeSensitisedFailure() throws InterruptedException {
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(
        command -> {
          throw new IllegalStateException("registrationToken=secret-value");
        });

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    DaemonUpdateResult failed = sink.await();
    assertEquals(DaemonUpdatePhase.FAILED, failed.phase());
    assertEquals("update preparation failed", failed.message());
    assertFalse(failed.message().contains("secret-value"), "diagnostics must stay de-sensitised");
  }

  /** 未注入准备器时按数据目录惰性构造真实 {@link ManagedDaemonUpdater}：缺失受管布局是确定性失败，不触网。 */
  @Test
  void defaultUpdaterIsBuiltLazilyForTheDataDirectory() throws Exception {
    Path dataDir = root.resolve("data");
    Files.createDirectories(dataDir);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            dataDir, DaemonOperatingSystem.LINUX, singleThread(), sink, () -> true);

    coordinator.handleUpdate(updateCommand(OP));

    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    DaemonUpdateResult failed = sink.await();
    assertEquals(DaemonUpdatePhase.FAILED, failed.phase());
    assertEquals("managed installation layout is not present", failed.message());
  }

  /**
   * 未注入启动器时惰性构造真实 OS 分离更新器：其启动失败必须回执 FAILED，绝不谎报 PREPARED。
   *
   * <p>非 Windows 主机上不存在 Windows PowerShell，真实启动器确定无法进入运行，因此这条路径可以在不注册任何计划任务的前提下验证。
   */
  @Test
  void defaultLauncherStartFailureIsReportedAsFailed() throws InterruptedException {
    assumeTrue(
        DaemonOperatingSystemDetector.detectCurrent() != DaemonOperatingSystem.WINDOWS,
        "the real Windows launcher registers a scheduled task; it is only verified on non-Windows hosts");
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root, DaemonOperatingSystem.WINDOWS, singleThread(), sink, () -> true);
    coordinator.setPreparer(command -> preparedOutcome(command.operationId()));

    coordinator.handleUpdate(updateCommand(OP));

    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    DaemonUpdateResult failed = sink.await();
    assertEquals(DaemonUpdatePhase.FAILED, failed.phase());
    assertEquals("detached updater could not be started", failed.message());
  }

  /**
   * 交接顺序：handoff 先落 PREPARING，OS 接受后才冻结 ACCEPTED，许可只在本次 PREPARED 回执入缓存并完成一次出站尝试后释放。
   *
   * <p>准备器替身使用真实 {@link UpdateHandoff} 文件，因此事件表与文件事实互相印证，而不是只验证调用顺序。
   */
  @Test
  void releasesPermitOnlyAfterThePreparedReceiptIsFrozenAndSent()
      throws InterruptedException, IOException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    HandoffSink handoffSink = new HandoffSink(preparer);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root, DaemonOperatingSystem.LINUX, singleThread(), handoffSink, () -> true);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          events.add("launch");
          return true;
        });

    coordinator.handleUpdate(updateCommand(OP));
    awaitUntil(preparer::permitExists, "the released permit");

    assertEquals(
        List.of("send:ACCEPTED", "prepare", "launch", "send:PREPARED"), List.copyOf(events));
    assertEquals(
        UpdateHandoff.State.ACCEPTED,
        handoffSink.stateAtPrepared,
        "the PREPARED receipt must be sent only after the handoff is ACCEPTED");
    assertFalse(
        handoffSink.permitAtPrepared,
        "the permit must not exist when the PREPARED receipt is sent");
    assertEquals(
        OP,
        Files.readString(updateRoot.resolve(UpdateHandoff.PERMIT_FILE)).strip(),
        "the permit must carry the operationId after delivery");
  }

  /** 已接受的同一 operation 只重放 PREPARED：绝不再次启动 OS 作业，但仍幂等释放 matching permit（崩溃在许可落盘前也能被重放救回）。 */
  @Test
  void doesNotRelaunchAnAlreadyAcceptedOperation() throws InterruptedException, IOException {
    AtomicInteger launches = new AtomicInteger();
    Path updateRoot = root.resolve("updates").resolve(OP);
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(command -> alreadyAcceptedOutcome(command.operationId()));
    coordinator.setLauncher(
        prepared -> {
          launches.incrementAndGet();
          return true;
        });

    coordinator.handleUpdate(updateCommand(OP));
    awaitUntil(
        () -> Files.exists(updateRoot.resolve(UpdateHandoff.PERMIT_FILE)), "the released permit");

    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(0, launches.get(), "an accepted operation must not be launched again");
    assertEquals(
        OP,
        Files.readString(updateRoot.resolve(UpdateHandoff.PERMIT_FILE)).strip(),
        "the replay must idempotently release the matching permit");
  }

  /** 启动失败必须落 FAILED 且零许可：独立更新器绝不会据此替换发行目录。 */
  @Test
  void leavesNoPermitWhenTheLaunchIsRejected() throws InterruptedException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root, DaemonOperatingSystem.LINUX, singleThread(), sink, () -> true);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          events.add("launch");
          return false;
        });

    coordinator.handleUpdate(updateCommand(OP));

    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    DaemonUpdateResult failed = sink.await();
    assertEquals(DaemonUpdatePhase.FAILED, failed.phase());
    assertEquals("detached updater could not be started", failed.message());
    assertEquals(UpdateHandoff.State.FAILED, preparer.state());
    assertFalse(preparer.permitExists(), "a rejected launch must not release the permit");
  }

  /** 无法持久化 ACCEPTED 等价于未被接受：落 FAILED 且零许可，绝不把不确定当成成功。 */
  @Test
  void leavesNoPermitWhenAcceptanceCannotBePersisted() throws InterruptedException, IOException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root, DaemonOperatingSystem.LINUX, singleThread(), sink, () -> true);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          events.add("launch");
          // 让 ACCEPTED 无法持久化：handoff 换成目录，原子改写前的「非符号链接普通文件」校验必然失败。
          Path handoff = updateRoot.resolve(UpdateHandoff.HANDOFF_FILE);
          try {
            Files.delete(handoff);
            Files.createDirectory(handoff);
          } catch (IOException error) {
            throw new UncheckedIOException(error);
          }
          return true;
        });

    coordinator.handleUpdate(updateCommand(OP));

    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    DaemonUpdateResult failed = sink.await();
    assertEquals(DaemonUpdatePhase.FAILED, failed.phase());
    assertFalse(preparer.permitExists(), "an unpersisted acceptance must not release the permit");
  }

  /** 启动抛出 {@link Error} 时：许可不释放，但认领必须释放，后续同一 operation 仍可被重新接受。 */
  @Test
  void releasesTheInFlightGateAndNoPermitWhenTheLaunchCannotRun() throws InterruptedException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    AtomicReference<Throwable> fatal = new AtomicReference<>();
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "run-update");
              thread.setUncaughtExceptionHandler((source, error) -> fatal.set(error));
              return thread;
            });
    executors.add(executor);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(root, DaemonOperatingSystem.LINUX, executor, sink, () -> true);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          throw new AssertionError("fatal bootstrap error");
        });

    coordinator.handleUpdate(updateCommand(OP));
    awaitUntil(() -> fatal.get() != null, "the fatal launch error");
    assertInstanceOf(AssertionError.class, fatal.get());
    assertFalse(preparer.permitExists(), "a fatal launch must not release the permit");

    // 认领必须已被释放：同一 operation 可以重新走完整流程。
    coordinator.setLauncher(prepared -> true);
    coordinator.handleUpdate(updateCommand(OP));
    awaitUntil(() -> sink.phasesInOrder().size() >= 3, "the retried update receipts");
    assertEquals(
        List.of(DaemonUpdatePhase.ACCEPTED, DaemonUpdatePhase.ACCEPTED, DaemonUpdatePhase.PREPARED),
        sink.phasesInOrder());
  }

  /** handoff 在启动后被损坏/改写时同样收敛为固定安全失败：绝不带着不确定继续，也绝不释放许可。 */
  @Test
  void leavesNoPermitWhenTheHandoffIsCorruptedAfterLaunch()
      throws InterruptedException, IOException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root, DaemonOperatingSystem.LINUX, singleThread(), sink, () -> true);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          try {
            Files.writeString(updateRoot.resolve(UpdateHandoff.HANDOFF_FILE), "[1,2,3]");
          } catch (IOException error) {
            throw new UncheckedIOException(error);
          }
          return true;
        });

    coordinator.handleUpdate(updateCommand(OP));

    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    assertEquals(DaemonUpdatePhase.FAILED, sink.await().phase());
    assertFalse(preparer.permitExists(), "a corrupted handoff must not release the permit");
  }

  /**
   * 许可释放失败必须可见但绝不掩盖主异常：回执发送抛出的是调用方的原始异常，许可失败只作为日志。
   *
   * <p>把 permit 造成目录可确定地让释放失败；断言被观测到的仍是回执发送异常本身。
   */
  @Test
  void permitReleaseFailureNeverMasksThePrimaryException()
      throws InterruptedException, IOException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    AtomicReference<Throwable> fatal = new AtomicReference<>();
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "run-update");
              thread.setUncaughtExceptionHandler((source, error) -> fatal.set(error));
              return thread;
            });
    executors.add(executor);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root, DaemonOperatingSystem.LINUX, executor, new PreparedThrowingSink(), () -> true);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          try {
            Files.createDirectory(updateRoot.resolve(UpdateHandoff.PERMIT_FILE));
          } catch (IOException error) {
            throw new UncheckedIOException(error);
          }
          return true;
        });

    coordinator.handleUpdate(updateCommand(OP));
    awaitUntil(() -> fatal.get() != null, "the primary receipt exception");

    assertInstanceOf(
        IllegalStateException.class,
        fatal.get(),
        "the receipt-send exception must remain the observed primary failure");
    assertEquals(
        UpdateHandoff.State.ACCEPTED,
        preparer.state(),
        "the accepted fact is cached even when the permit could not be released");
  }

  /** 首次许可释放失败（permit 被目录占位）后，显式重放与重连重发必须只补发 matching permit，绝不重复准备/下载/启动。 */
  @Test
  void replayAndReconnectRetryThePermitReleaseWithoutRepreparing()
      throws InterruptedException, IOException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    Path permit = updateRoot.resolve(UpdateHandoff.PERMIT_FILE);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    AtomicInteger launches = new AtomicInteger();
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          launches.incrementAndGet();
          return true;
        });
    // 许可目标被目录占位：首次释放必然失败，但已接受的更新事实仍然成立。
    Files.createDirectories(updateRoot);
    Files.createDirectory(permit);

    try (CoordinatorLogCapture capture = new CoordinatorLogCapture()) {
      coordinator.handleUpdate(updateCommand(OP));
      assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
      assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
      awaitUntil(
          () ->
              capture.records().stream()
                  .anyMatch(
                      record ->
                          record
                              .getMessage()
                              .startsWith("managed update permit could not be released: ")),
          "the failed first permit release");
      assertTrue(
          Files.isDirectory(permit, LinkOption.NOFOLLOW_LINKS), "the permit is still blocked");

      // 阻塞消失后，同一 operation 的重放只重发回执并补发 matching permit。
      Files.delete(permit);
      coordinator.handleUpdate(updateCommand(OP));
      awaitUntil(() -> Files.isRegularFile(permit), "the retried permit");
      assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
      assertEquals(OP, Files.readString(permit).strip());
      assertEquals(1, preparer.preparations(), "a replay must not prepare again");
      assertEquals(1, launches.get(), "a replay must not launch again");

      // 重连重发同样是许可释放的重试触发点。
      Files.delete(permit);
      coordinator.resendAfterReconnect();
      awaitUntil(() -> Files.isRegularFile(permit), "the permit retried by the reconnect resend");
      assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
      assertEquals(OP, Files.readString(permit).strip());
      assertEquals(1, preparer.preparations(), "a reconnect resend must not prepare again");
      assertEquals(1, launches.get(), "a reconnect resend must not launch again");
    }
  }

  /** 安全失败日志只记录固定阶段与异常类型：绝不回显原始异常、元数据或其中的敏感内容。 */
  @Test
  void safeFailureLogsRecordOnlyTheFixedStageAndType() throws IOException, InterruptedException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(
        prepared -> {
          // 启动后把 handoff 覆盖成携带合成秘密的损坏元数据：接受阶段的失败与秘密同源。
          try {
            Files.writeString(
                updateRoot.resolve(UpdateHandoff.HANDOFF_FILE),
                "registrationToken=" + SYNTHETIC_SECRET);
          } catch (IOException error) {
            throw new UncheckedIOException(error);
          }
          return true;
        });

    try (CoordinatorLogCapture capture = new CoordinatorLogCapture()) {
      coordinator.handleUpdate(updateCommand(OP));
      awaitUntil(() -> !capture.records().isEmpty(), "the safe failure log");

      assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
      assertEquals(DaemonUpdatePhase.FAILED, sink.await().phase());
      assertFalse(preparer.permitExists(), "a corrupted handoff must not release the permit");

      for (LogRecord record : capture.records()) {
        assertNull(record.getThrown(), "a raw throwable must never be logged");
        assertTrue(
            record
                .getMessage()
                .matches(
                    "managed update (handoff could not be (accepted|marked failed)"
                        + "|permit could not be released): [A-Za-z0-9_]+"),
            record.getMessage());
        assertFalse(
            record.getMessage().contains(SYNTHETIC_SECRET),
            "the safe log must not echo the raw failure content");
      }
    }
  }

  /** 出站失败（断线）仍保留已接受的更新事实：许可必须释放，独立更新器不被卡住。 */
  @Test
  void releasesPermitEvenWhenThePreparedReceiptCannotBeSent()
      throws InterruptedException, IOException {
    Path updateRoot = root.resolve("updates").resolve(OP);
    HandoffPreparer preparer = new HandoffPreparer(updateRoot);
    DaemonUpdateCoordinator coordinator =
        new DaemonUpdateCoordinator(
            root,
            DaemonOperatingSystem.LINUX,
            singleThread(),
            new PreparedThrowingSink(),
            () -> true);
    coordinator.setPreparer(preparer);
    coordinator.setLauncher(prepared -> true);

    coordinator.handleUpdate(updateCommand(OP));

    awaitUntil(preparer::permitExists, "the released permit");
    assertEquals(UpdateHandoff.State.ACCEPTED, preparer.state());
    assertEquals(OP, Files.readString(updateRoot.resolve(UpdateHandoff.PERMIT_FILE)).strip());
  }

  private DaemonUpdateCoordinator coordinator(
      ExecutorService executor, boolean ready, DaemonOperatingSystem operatingSystem) {
    return new DaemonUpdateCoordinator(root, operatingSystem, executor, sink, () -> ready);
  }

  private ExecutorService singleThread() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return executor;
  }

  /** 等待闩栅释放：超时或中断都必须让测试明确失败，绝不静默继续并产生假绿。 */
  private static void awaitRelease(CountDownLatch release) {
    try {
      assertTrue(
          release.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "latch must be released within " + ASYNC_TIMEOUT_SECONDS + "s");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the test latch", error);
    }
  }

  /**
   * 等待线程确定地阻塞在更新同步门上。
   *
   * <p>门由测试线程持有，因此该状态必然到达；这里只是自旋读取线程状态，不引入基于时间的概率假设。
   */
  private static void awaitBlocked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TIMEOUT_SECONDS);
    while (thread.getState() != Thread.State.BLOCKED) {
      assertTrue(System.nanoTime() < deadline, "thread must contend on the update gate");
      Thread.onSpinWait();
    }
  }

  /** 自旋等待一个短时条件成立：超时即让测试明确失败，绝不静默继续。 */
  private static void awaitUntil(BooleanSupplier condition, String description) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TIMEOUT_SECONDS);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "timed out waiting for " + description);
      Thread.onSpinWait();
    }
  }

  /** 取得协调器的更新同步门：测试持门以确定地制造「完成」与「重放」的竞争。 */
  private static Object updateGate(DaemonUpdateCoordinator coordinator) throws Exception {
    Field field = DaemonUpdateCoordinator.class.getDeclaredField("updateGate");
    field.setAccessible(true);
    return field.get(coordinator);
  }

  /** 受控并发重放：子线程异常必须回传主线程使测试失败，且必须能被彻底终止、不留存活线程。 */
  private static final class Replayers implements AutoCloseable {

    private final CountDownLatch start = new CountDownLatch(1);
    private final List<FutureTask<Void>> tasks = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    private Replayers(DaemonUpdateCoordinator coordinator, int count) {
      for (int index = 0; index < count; index++) {
        FutureTask<Void> task =
            new FutureTask<>(
                () -> {
                  awaitRelease(start);
                  coordinator.handleUpdate(updateCommand(OP));
                  return null;
                });
        Thread thread = new Thread(task, "replay-" + index);
        tasks.add(task);
        threads.add(thread);
        thread.start();
      }
    }

    private void release() {
      start.countDown();
    }

    private Thread thread(int index) {
      return threads.get(index);
    }

    /** 汇合全部重放并回传其异常：单纯 join 不会暴露子线程失败，必须 get 才能让测试真实失败。 */
    private void awaitAll() throws Exception {
      for (FutureTask<Void> task : tasks) {
        task.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    }

    @Override
    public void close() {
      start.countDown();
      threads.forEach(Thread::interrupt);
      for (Thread thread : threads) {
        try {
          thread.join(TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /** 写出真实 {@code PREPARING} handoff 的准备结果：协调器随后必须把它冻结为 ACCEPTED/FAILED。 */
  private ManagedUpdateOutcome.Prepared preparedOutcome(String operationId) {
    Path handoff = root.resolve("updates").resolve(operationId);
    try {
      Files.createDirectories(handoff);
      UpdateHandoff.writePreparing(
          handoff,
          updateCommandValue(operationId),
          handoff.resolve(ManagedDaemonUpdater.UNIX_UPDATE_SCRIPT),
          handoff.resolve(DaemonUpdateArtifact.jarName(TARGET_VERSION)),
          root.resolve("lib").resolve(ManagedDaemonUpdater.MANAGED_JAR_NAME),
          handoff.resolve(ManagedDaemonUpdater.MANAGED_CONFIG_NAME),
          "/usr/bin/java");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    return new ManagedUpdateOutcome.Prepared(
        TARGET_VERSION,
        handoff,
        handoff.resolve(ManagedDaemonUpdater.UNIX_UPDATE_SCRIPT),
        handoff.resolve(DaemonUpdateArtifact.jarName(TARGET_VERSION)),
        root.resolve("lib").resolve(ManagedDaemonUpdater.MANAGED_JAR_NAME));
  }

  /** 已接受交接的重放事实：真实写出 {@code ACCEPTED} handoff，重放只应幂等释放 matching permit，绝不再次启动。 */
  private ManagedUpdateOutcome.AlreadyAccepted alreadyAcceptedOutcome(String operationId) {
    ManagedUpdateOutcome.Prepared prepared = preparedOutcome(operationId);
    try {
      UpdateHandoff.transition(prepared.handoffDirectory(), UpdateHandoff.State.ACCEPTED);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    return new ManagedUpdateOutcome.AlreadyAccepted(prepared);
  }

  private static DaemonEnvelope updateCommand(String operationId) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.UPDATE,
        ENVIRONMENT_ID,
        null,
        new DaemonUpdateCommandCodec().encode(updateCommandValue(operationId)));
  }

  private static DaemonUpdateCommand updateCommandValue(String operationId) {
    return new DaemonUpdateCommand(
        operationId,
        TARGET_VERSION,
        DaemonUpdateArtifact.artifactUrl(TARGET_VERSION),
        ARTIFACT_SHA);
  }

  /**
   * 使用真实 handoff 文件的准备器替身：只负责写出 {@code PREPARING} 并暴露文件事实；接受/失败/许可的生命周期完全由协调器驱动 {@link
   * UpdateHandoff} 落盘，因此断言看到的是真实文件状态而非替身编造的事件。
   */
  private final class HandoffPreparer implements ManagedUpdatePreparer {

    private final Path updateRoot;
    private int preparations;

    private HandoffPreparer(Path updateRoot) {
      this.updateRoot = updateRoot;
    }

    @Override
    public ManagedUpdateOutcome prepare(DaemonUpdateCommand command) {
      preparations++;
      events.add("prepare");
      try {
        Files.createDirectories(updateRoot);
        UpdateHandoff.writePreparing(
            updateRoot,
            command,
            updateRoot.resolve("kk-studio-daemon-update.sh"),
            updateRoot.resolve("kk-studio-daemon-v1.0.9.jar"),
            updateRoot.resolve("kk-studio-daemon.jar"),
            updateRoot.resolve("daemon.json"),
            "/usr/bin/java");
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
      return new ManagedUpdateOutcome.Prepared(
          TARGET_VERSION,
          updateRoot,
          updateRoot.resolve("kk-studio-daemon-update.sh"),
          updateRoot.resolve("kk-studio-daemon-v1.0.9.jar"),
          updateRoot.resolve("kk-studio-daemon.jar"));
    }

    private UpdateHandoff.State state() {
      try {
        return UpdateHandoff.readState(updateRoot, updateCommandValue(OP));
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }

    private boolean permitExists() {
      return Files.exists(updateRoot.resolve(UpdateHandoff.PERMIT_FILE));
    }

    private int preparations() {
      return preparations;
    }
  }

  /**
   * 捕获协调器日志的 JUL 端口：安全失败日志是普通的 {@code System.Logger} 记录，只有挂接处理器才能断言其内容。
   *
   * <p>{@link #close()} 恢复接管前的级别与父处理器设置，避免影响其它测试。
   */
  private static final class CoordinatorLogCapture implements AutoCloseable {

    private final Logger logger = Logger.getLogger(DaemonUpdateCoordinator.class.getName());
    private final Level previousLevel = logger.getLevel();
    private final boolean previousUseParentHandlers = logger.getUseParentHandlers();
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    private CoordinatorLogCapture() {
      handler.setLevel(Level.ALL);
      logger.addHandler(handler);
      logger.setLevel(Level.ALL);
      logger.setUseParentHandlers(false);
    }

    private List<LogRecord> records() {
      return List.copyOf(records);
    }

    @Override
    public void close() {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
      logger.setUseParentHandlers(previousUseParentHandlers);
    }
  }

  /** 出站记录端口：把回执阶段追加到共享事件表，并在 PREPARED 送达瞬间核对 handoff 状态与许可。 */
  private final class HandoffSink implements DaemonMessageSink {

    private final DaemonUpdateResultCodec codec = new DaemonUpdateResultCodec();
    private final HandoffPreparer preparer;
    private volatile UpdateHandoff.State stateAtPrepared;
    private volatile boolean permitAtPrepared;

    private HandoffSink(HandoffPreparer preparer) {
      this.preparer = preparer;
    }

    @Override
    public boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
      DaemonUpdateResult result = codec.decode(payloadJson);
      events.add("send:" + result.phase());
      if (result.phase() == DaemonUpdatePhase.PREPARED) {
        stateAtPrepared = preparer.state();
        permitAtPrepared = preparer.permitExists();
      }
      return true;
    }
  }

  /** 接受回执正常、PREPARED 回执抛出异常的出站端口：验证断线也不阻止许可释放。 */
  private static final class PreparedThrowingSink implements DaemonMessageSink {

    private final DaemonUpdateResultCodec codec = new DaemonUpdateResultCodec();

    @Override
    public boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
      DaemonUpdateResult result = codec.decode(payloadJson);
      if (result.phase() == DaemonUpdatePhase.PREPARED) {
        throw new IllegalStateException("connection dropped before the prepared receipt");
      }
      return true;
    }
  }

  /** 记录出站回执的窄端口实现：可等待下一条回执，也可无阻塞检查"没有回执"。 */
  private static final class RecordingSink implements DaemonMessageSink {

    private final DaemonUpdateResultCodec codec = new DaemonUpdateResultCodec();
    private final BlockingQueue<DaemonUpdateResult> pending = new LinkedBlockingQueue<>();
    private final List<Outbound> all = Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
      // 先解码成局部结果再分别入队：并发发送时按索引回头取会读到别人的回执。
      DaemonUpdateResult result = codec.decode(payloadJson);
      all.add(new Outbound(messageType, invocationId, result));
      pending.add(result);
      return true;
    }

    DaemonUpdateResult await() throws InterruptedException {
      DaemonUpdateResult result = pending.poll(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertNotNull(result, "no UPDATE_RESULT within " + ASYNC_TIMEOUT_SECONDS + "s");
      return result;
    }

    DaemonUpdateResult pollNoWait() {
      return pending.poll();
    }

    boolean isEmpty() {
      return pending.isEmpty();
    }

    DaemonUpdateResult resultAt(int index) {
      synchronized (all) {
        return all.get(index).result();
      }
    }

    List<DaemonUpdatePhase> phasesInOrder() {
      synchronized (all) {
        all.forEach(
            outbound -> {
              assertEquals(DaemonMessageType.UPDATE_RESULT, outbound.type());
              assertNull(outbound.invocationId(), "update receipts are not invocation bound");
            });
        return all.stream().map(outbound -> outbound.result().phase()).toList();
      }
    }

    /** 已发出的 ACCEPTED 回执条数：同一 operation 只能被接受一次。 */
    long acceptedCount() {
      return phasesInOrder().stream().filter(phase -> phase == DaemonUpdatePhase.ACCEPTED).count();
    }

    private record Outbound(
        DaemonMessageType type, String invocationId, DaemonUpdateResult result) {}
  }

  /** 第一条回执抛出 RuntimeException、之后正常记录的出站端口：用于验证接受回执发送失败不会冻结在途认领。 */
  private static final class FailingFirstSendSink implements DaemonMessageSink {

    private final DaemonUpdateResultCodec codec = new DaemonUpdateResultCodec();
    private final BlockingQueue<DaemonUpdateResult> pending = new LinkedBlockingQueue<>();
    private boolean firstSendFails = true;
    private DaemonUpdateResult failedSend;

    @Override
    public boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
      DaemonUpdateResult result = codec.decode(payloadJson);
      if (firstSendFails) {
        firstSendFails = false;
        failedSend = result;
        throw new IllegalStateException("accepted receipt send failed");
      }
      pending.add(result);
      return true;
    }

    DaemonUpdatePhase failedPayloadPhase() {
      return failedSend.phase();
    }

    DaemonUpdateResult await() throws InterruptedException {
      DaemonUpdateResult result = pending.poll(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertNotNull(result, "no UPDATE_RESULT within " + ASYNC_TIMEOUT_SECONDS + "s");
      return result;
    }

    DaemonUpdateResult pollNoWait() {
      return pending.poll();
    }
  }
}
