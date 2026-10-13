package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommandCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResultCodec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 受管更新的编排事实：operation 幂等、在途互斥、回执缓存与分离更新器启动。
 *
 * <p>回执缓存是「已完成 operation 只重放结果、不第二次下载」的唯一归属；断开不改变已接受的更新事实，重连 READY 后由 {@link
 * #resendAfterReconnect()} 重发最近回执让 Platform 重新收敛。
 *
 * <p>本类只负责接受、准备与启动，不代替 OS 分离更新器替换二进制；也不持有连接状态，出站经 {@link DaemonMessageSink}、READY 判定经 {@link
 * BooleanSupplier} 注入。
 */
final class DaemonUpdateCoordinator {

  /** 进程内存中保留的受管更新回执条数上界：足够覆盖少量重发与重连重放，不无界累积。 */
  private static final int MAX_TRACKED_UPDATE_OPERATIONS = 8;

  private final Path dataDir;
  private final DaemonOperatingSystem operatingSystem;
  private final ExecutorService taskExecutor;
  private final DaemonMessageSink sink;
  private final BooleanSupplier ready;
  private final DaemonUpdateCommandCodec updateCommandCodec = new DaemonUpdateCommandCodec();
  private final DaemonUpdateResultCodec updateResultCodec = new DaemonUpdateResultCodec();

  /**
   * 受管更新操作的结果缓存（operationId -> 冻结回执）：同一 operation 的重发只重放结果，不产生第二次下载。
   *
   * <p>保留最近若干条并在重连后重发，使断开不等于失败时 Platform 能重新收敛。
   */
  private final Map<String, DaemonUpdateResult> updateResults =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DaemonUpdateResult> eldest) {
              return size() > MAX_TRACKED_UPDATE_OPERATIONS;
            }
          });

  /** 正在准备的 operationId；null 表示当前没有更新在途。 */
  private final AtomicReference<String> activeUpdateOperationId = new AtomicReference<>();

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
    DaemonUpdateResult completed = updateResults.get(command.operationId());
    if (completed != null) {
      sendUpdateResult(completed);
      return;
    }
    if (!activeUpdateOperationId.compareAndSet(null, command.operationId())) {
      if (command.operationId().equals(activeUpdateOperationId.get())) {
        return;
      }
      throw new DaemonProtocolException("a different update operation is already in progress");
    }
    sendUpdateResult(DaemonUpdateResult.accepted(command.operationId()));
    try {
      taskExecutor.execute(() -> runUpdate(command));
    } catch (RejectedExecutionException error) {
      activeUpdateOperationId.compareAndSet(command.operationId(), null);
      sendUpdateResult(
          DaemonUpdateResult.failed(command.operationId(), "update executor rejected"));
    }
  }

  /** 重连 READY 后重发已知更新回执：进行中重发 ACCEPTED，否则重发最近一次已完成回执。 */
  void resendAfterReconnect() {
    String active = activeUpdateOperationId.get();
    if (active != null && !updateResults.containsKey(active)) {
      sendUpdateResult(DaemonUpdateResult.accepted(active));
      return;
    }
    List<DaemonUpdateResult> snapshot;
    synchronized (updateResults) {
      snapshot = new ArrayList<>(updateResults.values());
    }
    if (!snapshot.isEmpty()) {
      sendUpdateResult(snapshot.get(snapshot.size() - 1));
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
    try {
      result =
          switch (managedUpdater().prepare(command)) {
            case ManagedUpdateOutcome.Prepared prepared -> updateLauncher().launch(prepared)
                ? DaemonUpdateResult.prepared(command.operationId())
                : DaemonUpdateResult.failed(
                    command.operationId(), "detached updater could not be started");
            case ManagedUpdateOutcome.Failed failed -> DaemonUpdateResult.failed(
                command.operationId(), failed.message());
          };
    } catch (RuntimeException error) {
      result = DaemonUpdateResult.failed(command.operationId(), "update preparation failed");
    } finally {
      activeUpdateOperationId.compareAndSet(command.operationId(), null);
    }
    updateResults.put(command.operationId(), result);
    sendUpdateResult(result);
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
