package fun.fengwk.kkstudio.platform.harness.http;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.api.CompactThreadResult;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.platform.harness.thread.query.EnvironmentWaitProjectionService;
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thread 控制面的应用编排：快照读取时投影费用与环境等待，人工写入只允许执行根并在写后回读权威快照。
 *
 * <p>Controller 只负责 HTTP 形状；这里的每个方法都以 Runtime 的 durable 结果为准，不缓存、不推测状态。
 */
@Service
@AllArgsConstructor
public class HarnessThreadService {

  private final HarnessRuntime runtime;
  private final UsageCostProjectionService usageCostProjectionService;
  private final EnvironmentWaitProjectionService environmentWaitProjectionService;

  /** 读取一致快照；{@code afterEntryId} 为空时返回完整历史，否则返回该游标之后的增量。 */
  public HarnessThreadSnapshotDTO getSnapshot(UUID threadId, UUID afterEntryId) {
    ThreadSnapshot snapshot =
        afterEntryId == null
            ? runtime.getThreadSnapshot(threadId)
            : runtime.getThreadSnapshot(threadId, afterEntryId);
    return snapshotResponse(snapshot);
  }

  /**
   * 同一读取事务内按输入顺序投影多份快照；结果与游标列表等长，每份仅 Entry 切片不同。
   *
   * <p>游标列表必须非 null，但允许其中元素为 null：null 返回完整历史，祖先游标返回其后增量，head 游标返回空增量，未知或非祖先游标回退为完整历史。空列表返回空列表。
   * 费用与环境等待投影在读取事务外执行，不构成该事务快照一致性的一部分。
   */
  public List<HarnessThreadSnapshotDTO> getSnapshots(UUID threadId, List<UUID> afterEntryIds) {
    List<ThreadSnapshot> snapshots = runtime.getThreadSnapshots(threadId, afterEntryIds);
    if (snapshots.isEmpty()) {
      return List.of();
    }
    Map<UUID, Entry> entries = new LinkedHashMap<>();
    for (ThreadSnapshot snapshot : snapshots) {
      for (var entry : snapshot.entrySlice().entries()) {
        entries.putIfAbsent(entry.id(), entry);
      }
    }
    Map<UUID, HarnessUsageCostDTO> usageCosts =
        usageCostProjectionService.project(new ArrayList<>(entries.values()));
    List<HarnessThreadSnapshotDTO> responses = new ArrayList<>(snapshots.size());
    for (ThreadSnapshot snapshot : snapshots) {
      responses.add(HarnessRuntimeResponseMapper.toSnapshotDto(snapshot, usageCosts));
    }
    environmentWaitProjectionService.project(responses);
    return List.copyOf(responses);
  }

  /** 显式放弃一个执行代次；与读取快照使用相同的投影语义。 */
  public HarnessThreadSnapshotDTO abandonExecution(
      UUID threadId, UUID executionId, long expectedVersion) {
    return snapshotResponse(runtime.abandonExecution(threadId, executionId, expectedVersion));
  }

  /** 重命名执行根并回读权威当前 Thread；名称规范化与冲突判定由 Runtime 负责。 */
  public HarnessThreadDTO rename(RenameThreadCommand command) {
    ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
    ThreadState updated = runtime.renameThread(command);
    return HarnessRuntimeResponseMapper.toThreadDto(runtime.getThreadSnapshot(updated.id()));
  }

  /** 提交手动压缩并回读提交后的当前 Thread。 */
  public HarnessThreadCompactResultDTO compact(CompactThreadCommand command) {
    ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
    CompactThreadResult result = runtime.compactThread(command);
    return HarnessRuntimeResponseMapper.toCompactResultDto(
        result, runtime.getThreadSnapshot(result.thread().id()));
  }

  /** 更新 YOLO policy 并回读权威当前 Thread。 */
  public HarnessThreadDTO updateYolo(SetThreadYoloCommand command) {
    ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
    ThreadState updated = runtime.setThreadYolo(command);
    return HarnessRuntimeResponseMapper.toThreadDto(runtime.getThreadSnapshot(updated.id()));
  }

  /** 停止目标 Thread 与完整后代，并回读 Stop 后的权威快照。 */
  public HarnessThreadStopResultDTO stop(StopCommand command) {
    ManualThreadControlGuard.requireExecutionRoot(runtime, command.threadId());
    StopResult result = runtime.stop(command);
    return HarnessRuntimeResponseMapper.toStopResultDto(
        result, runtime.getThreadSnapshot(result.thread().id()));
  }

  private HarnessThreadSnapshotDTO snapshotResponse(ThreadSnapshot snapshot) {
    Map<UUID, HarnessUsageCostDTO> usageCosts =
        usageCostProjectionService.project(snapshot.entrySlice().entries());
    HarnessThreadSnapshotDTO dto = HarnessRuntimeResponseMapper.toSnapshotDto(snapshot, usageCosts);
    environmentWaitProjectionService.project(List.of(dto));
    return dto;
  }
}
