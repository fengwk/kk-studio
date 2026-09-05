package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Coherent Thread 快照投影。
 *
 * <p>Thread、Entry、Command 与 Invocation 字段来自同一数据库快照；{@code version} 是 Thread 结构与控制状态的 durable
 * invalidation cursor，不是整个响应的 ETag。ModelInvocation 的 {@code streamCheckpointJson} 可在同一 {@code
 * version} 内推进，恢复时必须读取完整快照。{@code manualCompaction} 是随后计算的瞬时 advisory sidecar，实际提交始终由同一 {@code
 * version} 做最终 fence。列表默认为不可变空列表。
 */
@Data
public class HarnessThreadSnapshotDTO {
  /**
   * Thread 结构与控制状态的 durable invalidation cursor：strict non-negative decimal string，等于 {@link
   * #thread} 的 version；不是完整快照 ETag。
   */
  private String version;

  /** 同一数据库快照下的完整 Thread 投影。 */
  private HarnessThreadDTO thread;

  /** root-to-head 的完整 Session Entry 链（不可变列表，默认空）。 */
  private List<HarnessSessionEntryDTO> entries = List.of();

  /** 当前仍处于 QUEUED 状态的命令列表（不可变列表，默认空）。 */
  private List<HarnessThreadCommandDTO> queuedCommands = List.of();

  /** 当前 Turn 的活跃 ModelInvocation 投影；无则 null（{@code @JsonInclude(ALWAYS)} 保证 null 也输出）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private ModelInvocationDTO modelInvocation;

  /** 活跃 ModelInvocation 的工具兄弟列表（callIndex 为 0..N-1 连续前缀；不可变列表，默认空）。 */
  private List<ToolInvocationDTO> toolInvocations = List.of();

  /** 当前 Model context 尚未物化的 failed attempt 历史（不可变列表，默认空）。 */
  private List<ModelAttemptFailureDTO> modelAttemptFailures = List.of();

  /** 当前瞬时手动压缩可用性；实际提交仍由 compact 请求的 expectedVersion 守护。 */
  private HarnessManualCompactionDTO manualCompaction;
}
