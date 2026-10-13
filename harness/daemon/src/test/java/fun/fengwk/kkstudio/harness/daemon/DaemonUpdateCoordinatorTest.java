package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

  @TempDir Path root;

  private final RecordingSink sink = new RecordingSink();
  private final List<ExecutorService> executors = new ArrayList<>();

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
    CountDownLatch replayStart = new CountDownLatch(1);
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

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    assertTrue(prepareEntered.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    List<Thread> replays = replayThreads(coordinator, replayStart, 8);
    replayStart.countDown();
    joinAll(replays);
    assertNull(sink.pollNoWait(), "an in-flight replay must not be re-acknowledged");

    releasePrepare.countDown();
    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(1, preparations.get(), "in-flight replays must not prepare again");
    assertEquals(1, sink.acceptedCount(), "an operation may be accepted exactly once");
  }

  /**
   * 完成与重放共用同一同步门：测试持门时，完成的「入缓存 + 释放在途认领 + 出站」与重放判定都必须停在门上， 因此两者之间不存在可被重放利用的窗口。用持门加线程状态等待确定地制造竞争，不依赖
   * sleep 或概率。
   */
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

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    assertTrue(launchEntered.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    Object updateGate = updateGate(coordinator);
    Thread replay = new Thread(() -> coordinator.handleUpdate(updateCommand(OP)), "replay");
    synchronized (updateGate) {
      // runUpdate 已返回 launch，下一步就是「入缓存 + 释放在途认领」：此处持门可确定地把它挡在完成临界区之外。
      releaseLaunch.countDown();
      awaitBlocked(runner.get());
      replay.start();
      awaitBlocked(replay);
      assertNull(sink.pollNoWait(), "no receipt may be produced while the update gate is held");
    }
    replay.join(TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
    assertFalse(replay.isAlive());

    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(1, preparations.get(), "the replay must reuse the frozen receipt");
    assertEquals(1, sink.acceptedCount(), "an operation may be accepted exactly once");
  }

  /** 与完成并发的重放不得产生第二次准备或第二次接受：闩栅同时放行，不依赖 sleep。 */
  @Test
  void keepsCompletionAtomicAgainstConcurrentReplays() throws Exception {
    AtomicInteger preparations = new AtomicInteger();
    CountDownLatch launchEntered = new CountDownLatch(1);
    CountDownLatch releaseLaunch = new CountDownLatch(1);
    CountDownLatch replayStart = new CountDownLatch(1);
    DaemonUpdateCoordinator coordinator =
        coordinator(singleThread(), true, DaemonOperatingSystem.LINUX);
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

    coordinator.handleUpdate(updateCommand(OP));
    assertEquals(DaemonUpdatePhase.ACCEPTED, sink.await().phase());
    assertTrue(launchEntered.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    List<Thread> replays = replayThreads(coordinator, replayStart, 8);
    releaseLaunch.countDown();
    replayStart.countDown();
    joinAll(replays);

    assertEquals(DaemonUpdatePhase.PREPARED, sink.await().phase());
    assertEquals(1, preparations.get(), "concurrent replays must not prepare again");
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

  private DaemonUpdateCoordinator coordinator(
      ExecutorService executor, boolean ready, DaemonOperatingSystem operatingSystem) {
    return new DaemonUpdateCoordinator(root, operatingSystem, executor, sink, () -> ready);
  }

  private ExecutorService singleThread() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return executor;
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      release.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  /** 并发重放同一 operation 的线程：在给定闩栅释放后同时发起，用于精确制造与完成过程的竞争。 */
  private static List<Thread> replayThreads(
      DaemonUpdateCoordinator coordinator, CountDownLatch start, int count) {
    List<Thread> threads = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      Thread thread =
          new Thread(
              () -> {
                awaitRelease(start);
                coordinator.handleUpdate(updateCommand(OP));
              },
              "replay-" + index);
      thread.start();
      threads.add(thread);
    }
    return threads;
  }

  private static void joinAll(List<Thread> threads) throws InterruptedException {
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
      assertFalse(thread.isAlive(), "replay must converge within the budget");
    }
  }

  /**
   * 等待线程确定地阻塞在更新同步门上。
   *
   * <p>门由测试线程持有，因此该状态必然到达；这里只是自旋读取线程状态，不引入任何基于时间的概率假设。
   */
  private static void awaitBlocked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TIMEOUT_SECONDS);
    while (thread.getState() != Thread.State.BLOCKED) {
      assertTrue(System.nanoTime() < deadline, "thread must contend on the update gate");
      Thread.onSpinWait();
    }
  }

  /** 取得协调器的更新同步门：测试持门以确定地制造「完成」与「重放」的竞争。 */
  private static Object updateGate(DaemonUpdateCoordinator coordinator) throws Exception {
    Field field = DaemonUpdateCoordinator.class.getDeclaredField("updateGate");
    field.setAccessible(true);
    return field.get(coordinator);
  }

  private ManagedUpdateOutcome.Prepared preparedOutcome(String operationId) {
    Path handoff = root.resolve("updates").resolve(operationId);
    return new ManagedUpdateOutcome.Prepared(
        TARGET_VERSION,
        handoff,
        handoff.resolve(ManagedDaemonUpdater.UNIX_UPDATE_SCRIPT),
        handoff.resolve(DaemonUpdateArtifact.jarName(TARGET_VERSION)),
        root.resolve("lib").resolve(ManagedDaemonUpdater.MANAGED_JAR_NAME));
  }

  private static DaemonEnvelope updateCommand(String operationId) {
    DaemonUpdateCommand command =
        new DaemonUpdateCommand(
            operationId,
            TARGET_VERSION,
            DaemonUpdateArtifact.artifactUrl(TARGET_VERSION),
            ARTIFACT_SHA);
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.UPDATE,
        ENVIRONMENT_ID,
        null,
        new DaemonUpdateCommandCodec().encode(command));
  }

  /** 记录出站回执的窄端口实现：可等待下一条回执，也可无阻塞检查"没有回执"。 */
  private static final class RecordingSink implements DaemonMessageSink {

    private final DaemonUpdateResultCodec codec = new DaemonUpdateResultCodec();
    private final BlockingQueue<DaemonUpdateResult> pending = new LinkedBlockingQueue<>();
    private final List<Outbound> all = Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
      all.add(new Outbound(messageType, invocationId, codec.decode(payloadJson)));
      pending.add(all.get(all.size() - 1).result());
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
