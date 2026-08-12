package fun.fengwk.kkstudio.core.studio.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.studio.canvas.CanvasChanges;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas realtime 编排：patch 的 {@code afterCommit} 发布与 {@code /changes} 读取。
 *
 * <p>PostgreSQL 行与 {@code canvas_document.version} 是事实源；Redis Stream 只是严格在事务提交后写入的 bounded
 * best-effort patch 缓存（写入失败只告警，客户端通过 changes gap/snapshot 自愈）。{@code /changes} 从缓存回放连续
 * patches，任何缺失/gap/损坏/初始加载都返回权威 snapshot。
 */
@Slf4j
@Service
public class CanvasRealtimeService {

  private final CanvasPatchStore patchStore;
  private final CanvasQueryService queryService;

  public CanvasRealtimeService(CanvasPatchStore patchStore, CanvasQueryService queryService) {
    this.patchStore = Objects.requireNonNull(patchStore, "patchStore");
    this.queryService = Objects.requireNonNull(queryService, "queryService");
  }

  /** 事务内调用：注册 {@code afterCommit} 后写入缓存（回滚时绝不发布）。 当前没有活动事务时（如测试直连）直接 best-effort 写入。 */
  public void publish(UUID canvasId, CanvasPatch patch) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(patch, "patch");
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              appendBestEffort(canvasId, patch);
            }
          });
    } else {
      appendBestEffort(canvasId, patch);
    }
  }

  /**
   * {@code GET /canvases/{id}/changes?afterVersion=N}：从缓存读取从 afterVersion 起连续的 patches； 初始加载
   * （afterVersion = 0）、缓存缺失/gap/损坏或 Redis 不可用都返回权威 snapshot。
   */
  public CanvasChanges readChanges(UUID canvasId, long afterVersion) {
    Objects.requireNonNull(canvasId, "canvasId");
    if (afterVersion < 0L) {
      throw new IllegalArgumentException("afterVersion must be >= 0");
    }
    CanvasSnapshot snapshot =
        queryService
            .findSnapshot(canvasId)
            .orElseThrow(() -> new IllegalArgumentException("Canvas not found: " + canvasId));
    if (afterVersion == 0L) {
      return new CanvasChanges(List.of(), snapshot);
    }
    long currentVersion = snapshot.document().version();
    if (afterVersion >= currentVersion) {
      return new CanvasChanges(List.of(), null);
    }
    List<CanvasPatch> patches;
    try {
      patches = patchStore.readAll(canvasId);
    } catch (RuntimeException error) {
      log.warn(
          "canvas changes cache read failed canvasId={} type={}",
          canvasId,
          error.getClass().getSimpleName());
      return new CanvasChanges(List.of(), snapshot);
    }
    Map<Long, CanvasPatch> byVersion = new HashMap<>();
    for (CanvasPatch patch : patches) {
      if (patch.version() != patch.baseVersion() + 1L || patch.version() <= 0L) {
        return new CanvasChanges(List.of(), snapshot);
      }
      if (byVersion.putIfAbsent(patch.version(), patch) != null) {
        return new CanvasChanges(List.of(), snapshot);
      }
    }
    List<CanvasPatch> contiguous = new ArrayList<>();
    for (long version = afterVersion + 1L; version <= currentVersion; version++) {
      CanvasPatch patch = byVersion.get(version);
      if (patch == null) {
        // 中间任何缺失都视为 gap：必须整体替换当前状态，客户端才能一次性收敛到当前版本。
        return new CanvasChanges(List.of(), snapshot);
      }
      contiguous.add(patch);
    }
    return new CanvasChanges(List.copyOf(contiguous), null);
  }

  private void appendBestEffort(UUID canvasId, CanvasPatch patch) {
    try {
      patchStore.append(canvasId, patch);
    } catch (RuntimeException error) {
      log.warn(
          "canvas changes cache publish failed canvasId={} version={} type={}",
          canvasId,
          patch.version(),
          error.getClass().getSimpleName());
    }
  }
}
