package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommandCodec;
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

  /** 冻结回执缓存（operationId -> 回执）：同一 operation 只重放结果，最近若干条供重连重发。仅由 {@link #updateGate} 保护。 */
  private final Map<String, DaemonUpdateResult> updateResults =
      new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DaemonUpdateResult> eldest) {
          return size() > MAX_TRACKED_UPDATE_OPERATIONS;
        }
      };

  /** 在途认领的 operationId；null 表示无在途更新。与 {@link #updateResults} 同受 {@link #updateGate} 保护。 */
  private String activeUpdateOperationId;

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
    DaemonUpdateResult completed;
    synchronized (updateGate) {
      completed = updateResults.get(command.operationId());
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
      sendUpdateResult(completed);
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

  /** 重连 READY 后重发已知更新回执：进行中重发 ACCEPTED，否则重发最近一次已完成回执。 */
  void resendAfterReconnect() {
    DaemonUpdateResult resend;
    synchronized (updateGate) {
      if (activeUpdateOperationId != null && !updateResults.containsKey(activeUpdateOperationId)) {
        resend = DaemonUpdateResult.accepted(activeUpdateOperationId);
      } else {
        List<DaemonUpdateResult> snapshot = new ArrayList<>(updateResults.values());
        resend = snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
      }
    }
    if (resend != null) {
      sendUpdateResult(resend);
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
    DaemonUpdateResult result;
    Path permitHandoff = null;
    try {
      ManagedUpdateOutcome outcome = managedUpdater().prepare(command);
      if (outcome instanceof ManagedUpdateOutcome.Prepared prepared) {
        if (startAndAccept(prepared)) {
          permitHandoff = prepared.handoffDirectory();
          result = DaemonUpdateResult.prepared(command.operationId());
        } else {
          result = DaemonUpdateResult.failed(command.operationId(), LAUNCH_FAILED_MESSAGE);
        }
      } else if (outcome instanceof ManagedUpdateOutcome.AlreadyAccepted accepted) {
        // 已接受的同一 operation 只重放 PREPARED：绝不再次启动 OS 作业，但仍幂等释放 matching permit。
        permitHandoff = accepted.prepared().handoffDirectory();
        result = DaemonUpdateResult.prepared(command.operationId());
      } else {
        ManagedUpdateOutcome.Failed failed = (ManagedUpdateOutcome.Failed) outcome;
        result = DaemonUpdateResult.failed(command.operationId(), failed.message());
      }
    } catch (RuntimeException error) {
      result = DaemonUpdateResult.failed(command.operationId(), "update preparation failed");
    } catch (Error error) {
      // Error 仍向上传播：不进入任何许可释放路径，但必须释放认领，避免后续更新永久阻塞。
      releaseActive(command.operationId());
      throw error;
    }
    // 入缓存与释放认领必须同门完成：中间窗口会让重放越过已完成判定并触发第二次下载。
    synchronized (updateGate) {
      updateResults.put(command.operationId(), result);
      if (command.operationId().equals(activeUpdateOperationId)) {
        activeUpdateOperationId = null;
      }
    }
    try {
      sendUpdateResult(result);
    } finally {
      // 许可只在本次 PREPARED 回执冻结入缓存并完成一次出站尝试后释放；断线也保留实际 accepted 事实。
      if (permitHandoff != null) {
        releasePermit(permitHandoff, command.operationId());
      }
    }
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
      LOG.log(Level.WARNING, "managed update handoff could not be accepted", error);
      markFailed(prepared.handoffDirectory());
      return false;
    }
  }

  /** 启动失败或结果不确定时落 FAILED；即便落盘失败，handoff 仍是 PREPARING，同样不可复用。 */
  private static void markFailed(Path updateRoot) {
    try {
      UpdateHandoff.transition(updateRoot, UpdateHandoff.State.FAILED);
    } catch (IOException | RuntimeException error) {
      LOG.log(Level.WARNING, "managed update handoff could not be marked failed", error);
    }
  }

  /** 释放 matching permit；失败以安全阶段说明可见，但绝不掩盖调用方的原始异常。 */
  private static void releasePermit(Path updateRoot, String operationId) {
    try {
      UpdateHandoff.releasePermit(updateRoot, operationId);
    } catch (IOException | RuntimeException error) {
      LOG.log(Level.WARNING, "managed update permit could not be released", error);
    }
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
