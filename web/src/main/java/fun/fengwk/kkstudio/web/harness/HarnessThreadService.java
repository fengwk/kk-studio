package fun.fengwk.kkstudio.web.harness;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.api.CompactThreadResult;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.thread.query.EnvironmentWaitProjectionService;
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Thread 控制面的应用编排：快照读取时投影费用与环境等待，人工写入只允许执行根并在写后回读权威快照。
 *
 * <p>Controller 只负责 HTTP 形状与信封；这里的每个方法都以 Runtime 的 durable 结果为准，不缓存、不推测状态。
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
    environmentWaitProjectionService.project(dto);
    return dto;
  }
}
