package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommandCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdatePhase;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResultCodec;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * 受管更新的编排事实：operation 幂等、在途互斥、回执缓存与分离更新器启动。
 *
 * <p>回执缓存是「已完成 operation 只重放结果、不第二次下载」的唯一归属；断开不改变已接受的更新事实，重连 READY 后由 {@link
 * #resendAfterReconnect()} 重发最近回执让 Platform 重新收敛。
 *
 * <p>本类只负责接受、准备与启动，不代替 OS 分离更新器替换二进制；也不持有连接状态，出站经 {@link DaemonMessageSink}、READY 判定经 {@link
 * BooleanSupplier} 注入。
 *
 * <p><b>显式交接：</b>准备阶段已把 handoff 落为 {@code PREPARING}；只有分离更新器被确定接受后才冻结为 {@code ACCEPTED}， 且许可只在本次
 * {@code PREPARED} 回执入缓存并完成一次出站尝试后释放。启动失败或结果不确定一律落 {@code FAILED} 且不释放许可， 断线也保留已接受的更新事实。
 */
final class DaemonUpdateCoordinator {

  private static final Logger LOG = System.getLogger(DaemonUpdateCoordinator.class.getName());

  /** 进程内存中保留的受管更新回执条数上界：足够覆盖少量重发与重连重放，不无界累积。 */
  private static final int MAX_TRACKED_UPDATE_OPERATIONS = 8;

  /** 分离更新器未能进入运行的固定安全回执说明。 */
  private static final String LAUNCH_FAILED_MESSAGE = "detached updater could not be started";

  private final Path dataDir;
  private final DaemonOperatingSystem operatingSystem;
  private final ExecutorService taskExecutor;
  private final DaemonMessageSink sink;
  private final BooleanSupplier ready;
  private final DaemonUpdateCommandCodec updateCommandCodec = new DaemonUpdateCommandCodec();
  private final DaemonUpdateResultCodec updateResultCodec = new DaemonUpdateResultCodec();

  /** 同步门：同门守护在途认领与结果缓存，使判定/认领/完成入缓存+释放都是原子决定；门外的 prepare、launch 与出站不持锁。 */
  private final Object updateGate = new Object();

  /**
   * 冻结的已完成事实缓存（operationId -> 已完成事实）：同一 operation 只重放结果与许可，最近若干条供重连重发。仅由 {@link #updateGate} 保护。
   */
  private final Map<String, CompletedUpdate> completedUpdates =
      new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompletedUpdate> eldest) {
          return size() > MAX_TRACKED_UPDATE_OPERATIONS;
        }
      };

  /** 在途认领的 operationId；null 表示无在途更新。与 {@link #completedUpdates} 同受 {@link #updateGate} 保护。 */
  private String activeUpdateOperationId;

  /**
   * 已完成 operation 的冻结事实：回执，加上「确实已接受」时才存在的交接目录。
   *
   * <p>重放与重连只需要这一份事实：不再准备、下载或启动，但仍幂等释放 matching permit，因此一次失败的许可释放可由后续重放自然重试。回执自带权威 operationId。
   */
  private record CompletedUpdate(DaemonUpdateResult result, Path acceptedHandoffDirectory) {

    /** 已被 OS 接受并冻结为 {@code PREPARED} 的完成事实：交接目录必须存在，重放时据此幂等补发 permit。 */
    private static CompletedUpdate accepted(
        DaemonUpdateResult result, Path acceptedHandoffDirectory) {
      if (result.phase() != DaemonUpdatePhase.PREPARED) {
        throw new IllegalArgumentException("an accepted handoff requires a PREPARED receipt");
      }
      return new CompletedUpdate(
          Objects.requireNonNull(result, "result"),
          Objects.requireNonNull(acceptedHandoffDirectory, "acceptedHandoffDirectory"));
    }

    /** 未被接受的完成事实：绝不持有可释放的许可。 */
    private static CompletedUpdate unaccepted(DaemonUpdateResult result) {
      if (result.phase() == DaemonUpdatePhase.PREPARED) {
        throw new IllegalArgumentException("a PREPARED receipt requires an accepted handoff");
      }
      return new CompletedUpdate(Objects.requireNonNull(result, "result"), null);
    }
  }

  private volatile ManagedUpdatePreparer managedUpdater;
  private volatile ManagedUpdateLauncher updateLauncher;

  DaemonUpdateCoordinator(
      Path dataDir,
      DaemonOperatingSystem operatingSystem,
      ExecutorService taskExecutor,
      DaemonMessageSink sink,
      BooleanSupplier ready) {
    this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
    this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
    this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.ready = Objects.requireNonNull(ready, "ready");
  }

  /**
   * 处理服务端下发的受管更新命令：只在 READY 绑定连接上接受；同一 operation 幂等。
   *
   * <p>命令先在接收路径上立即回执 {@code ACCEPTED}（让 Platform 尽快进入 RUNNING），下载/校验/预检在独立任务线程执行并以 {@code
   * PREPARED}/{@code FAILED} 回执。任何失败都保留旧二进制不动。
   */
  void handleUpdate(DaemonEnvelope envelope) {
    if (!ready.getAsBoolean() || envelope.environmentId() == null) {
      throw new DaemonProtocolException("UPDATE requires a READY bound connection");
    }
    DaemonUpdateCommand command = updateCommandCodec.decode(envelope.payloadJson());
    CompletedUpdate completed;
    synchronized (updateGate) {
      completed = completedUpdates.get(command.operationId());
      if (completed == null) {
        // 判定与认领必须同门原子：否则重放可能落在「已释放认领、结果尚未入缓存」的窗口，触发第二次下载。
        if (command.operationId().equals(activeUpdateOperationId)) {
          return;
        }
        if (activeUpdateOperationId != null) {
          throw new DaemonProtocolException("a different update operation is already in progress");
        }
        activeUpdateOperationId = command.operationId();
      }
    }
    if (completed != null) {
      // 已完成的 operation 只重放冻结回执；若它来自已接受的交接，这里同时幂等补发 matching permit。
      deliverCompletedUpdate(completed);
      return;
    }
    try {
      sendUpdateResult(DaemonUpdateResult.accepted(command.operationId()));
    } catch (RuntimeException error) {
      // 接受回执未送达即更新尚未提交：释放认领并传播异常，使 Platform 可重试，不留永久在途。
      releaseActive(command.operationId());
      throw error;
    }
    try {
      taskExecutor.execute(() -> runUpdate(command));
    } catch (RejectedExecutionException error) {
      releaseActive(command.operationId());
      sendUpdateResult(
          DaemonUpdateResult.failed(command.operationId(), "update executor rejected"));
    }
  }

  /** 重连 READY 后重发已知更新回执：进行中重发 ACCEPTED，否则重发最近一次已完成回执并幂等补发 matching permit。 */
  void resendAfterReconnect() {
    CompletedUpdate completed = null;
    DaemonUpdateResult inFlight = null;
    synchronized (updateGate) {
      if (activeUpdateOperationId != null
          && !completedUpdates.containsKey(activeUpdateOperationId)) {
        inFlight = DaemonUpdateResult.accepted(activeUpdateOperationId);
      } else {
        List<CompletedUpdate> snapshot = new ArrayList<>(completedUpdates.values());
        completed = snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
      }
    }
    if (inFlight != null) {
      sendUpdateResult(inFlight);
    } else if (completed != null) {
      deliverCompletedUpdate(completed);
    }
  }

  /**
   * 交付一条已完成回执：出站成功与否都在 finally 中对「确实已接受」的交接幂等释放许可。
   *
   * <p>显式重放与重连重发都经由此处，因此它们既是回执重发，也是许可释放的天然重试；绝不再次准备、下载或启动。
   */
  private void deliverCompletedUpdate(CompletedUpdate completed) {
    try {
      sendUpdateResult(completed.result());
    } finally {
      Path acceptedHandoffDirectory = completed.acceptedHandoffDirectory();
      if (acceptedHandoffDirectory != null) {
        // 释放失败只记录固定阶段与异常类型，绝不掩盖出站异常。
        releasePermit(acceptedHandoffDirectory, completed.result().operationId());
      }
    }
  }

  /** 测试注入点：替换更新准备器，避免真实网络下载。 */
  void setPreparer(ManagedUpdatePreparer updater) {
    this.managedUpdater = updater;
  }

  /** 测试注入点：替换分离更新器启动器，避免真实 fork 进程。 */
  void setLauncher(ManagedUpdateLauncher launcher) {
    this.updateLauncher = launcher;
  }

  private void runUpdate(DaemonUpdateCommand command) {
    CompletedUpdate completed;
    try {
      ManagedUpdateOutcome outcome = managedUpdater().prepare(command);
      if (outcome instanceof ManagedUpdateOutcome.Prepared prepared) {
        if (startAndAccept(prepared)) {
          completed =
              CompletedUpdate.accepted(
                  DaemonUpdateResult.prepared(command.operationId()), prepared.handoffDirectory());
        } else {
          completed =
              CompletedUpdate.unaccepted(
                  DaemonUpdateResult.failed(command.operationId(), LAUNCH_FAILED_MESSAGE));
        }
      } else if (outcome instanceof ManagedUpdateOutcome.AlreadyAccepted accepted) {
        // 已接受的同一 operation 只重放 PREPARED：绝不再次启动 OS 作业。
        completed =
            CompletedUpdate.accepted(
                DaemonUpdateResult.prepared(command.operationId()),
                accepted.prepared().handoffDirectory());
      } else {
        ManagedUpdateOutcome.Failed failed = (ManagedUpdateOutcome.Failed) outcome;
        completed =
            CompletedUpdate.unaccepted(
                DaemonUpdateResult.failed(command.operationId(), failed.message()));
      }
    } catch (RuntimeException error) {
      completed =
          CompletedUpdate.unaccepted(
              DaemonUpdateResult.failed(command.operationId(), "update preparation failed"));
    } catch (Error error) {
      // Error 仍向上传播：不进入任何许可释放路径，但必须释放认领，避免后续更新永久阻塞。
      releaseActive(command.operationId());
      throw error;
    }
    // 入缓存与释放认领必须同门完成：中间窗口会让重放越过已完成判定并触发第二次下载。
    synchronized (updateGate) {
      completedUpdates.put(command.operationId(), completed);
      if (command.operationId().equals(activeUpdateOperationId)) {
        activeUpdateOperationId = null;
      }
    }
    deliverCompletedUpdate(completed);
  }

  /**
   * 启动分离更新器并把交接冻结为 ACCEPTED：只有 OS 接受且接受事实已持久化时才视为接受。
   *
   * <p>启动返回 false、抛出 {@link RuntimeException} 或无法持久化 {@code ACCEPTED} 都收敛为固定的安全失败并返回
   * false，调用方据此拒绝释放许可； 启动抛出 {@link Error} 时不在此捕获，由外层释放认领后向上传播。
   */
  private boolean startAndAccept(ManagedUpdateOutcome.Prepared prepared) {
    boolean launched;
    try {
      launched = updateLauncher().launch(prepared);
    } catch (RuntimeException error) {
      launched = false;
    }
    if (!launched) {
      markFailed(prepared.handoffDirectory());
      return false;
    }
    try {
      UpdateHandoff.transition(prepared.handoffDirectory(), UpdateHandoff.State.ACCEPTED);
      return true;
    } catch (IOException | RuntimeException error) {
      // 元数据在 readState 之后被改写/损坏时同样收敛为固定的安全失败，绝不带着不确定继续、也不释放许可。
      logSafeFailure("managed update handoff could not be accepted", error);
      markFailed(prepared.handoffDirectory());
      return false;
    }
  }

  /** 启动失败或结果不确定时落 FAILED；即便落盘失败，handoff 仍是 PREPARING，同样不可复用。 */
  private static void markFailed(Path updateRoot) {
    try {
      UpdateHandoff.transition(updateRoot, UpdateHandoff.State.FAILED);
    } catch (IOException | RuntimeException error) {
      logSafeFailure("managed update handoff could not be marked failed", error);
    }
  }

  /** 释放 matching permit；失败以安全阶段说明可见，但绝不掩盖调用方的原始异常。 */
  private static void releasePermit(Path updateRoot, String operationId) {
    try {
      UpdateHandoff.releasePermit(updateRoot, operationId);
    } catch (IOException | RuntimeException error) {
      logSafeFailure("managed update permit could not be released", error);
    }
  }

  /** 只记录固定阶段与异常类型：绝不输出原始异常、其消息或可能携带元数据/未知内容的堆栈。 */
  private static void logSafeFailure(String stage, Throwable error) {
    LOG.log(Level.WARNING, stage + ": " + error.getClass().getSimpleName());
  }

  /** 在同一门内释放在途认领；仅当它仍属于同一次 operation 时释放。 */
  private void releaseActive(String operationId) {
    synchronized (updateGate) {
      if (operationId.equals(activeUpdateOperationId)) {
        activeUpdateOperationId = null;
      }
    }
  }

  private void sendUpdateResult(DaemonUpdateResult result) {
    if (ready.getAsBoolean()) {
      sink.send(DaemonMessageType.UPDATE_RESULT, null, updateResultCodec.encode(result));
    }
  }

  private ManagedUpdatePreparer managedUpdater() {
    ManagedUpdatePreparer current = managedUpdater;
    if (current == null) {
      synchronized (this) {
        current = managedUpdater;
        if (current == null) {
          current = new ManagedDaemonUpdater(dataDir);
          managedUpdater = current;
        }
      }
    }
    return current;
  }

  private ManagedUpdateLauncher updateLauncher() {
    ManagedUpdateLauncher current = updateLauncher;
    if (current == null) {
      synchronized (this) {
        current = updateLauncher;
        if (current == null) {
          current = new DetachedUpdateLauncher(operatingSystem);
          updateLauncher = current;
        }
      }
    }
    return current;
  }
}
