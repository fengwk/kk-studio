package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Coherent Thread 快照投影。
 *
 * <p>Thread、Entry、Command 与当前 Assistant 输出来自同一数据库快照；{@code version} 是 Thread 结构与控制状态的 durable
 * invalidation cursor，不是整个响应的 ETag。{@code activeAssistantOutput} 的 contents/sequence 可在同一 {@code
 * version} 内推进，恢复时必须读取完整快照。{@code manualCompaction} 是随后计算的瞬时 advisory sidecar，实际提交始终由同一 {@code
 * version} 做最终 fence。工具的 requiredEnvironmentId / waitingForEnvironment 是随后从 Work 与环境租约读取的瞬时
 * sidecar，不属于 version 所保证的 durable 快照。列表默认为不可变空列表。
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

  /** Runtime 三态执行状态：RUNNING / WAITING / IDLE。 */
  private String executionStatus;

  /** 当前 open Turn 的公共 Assistant 输出；无输出或输出已物化时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable HarnessActiveAssistantOutputDTO activeAssistantOutput;

  /** 当前 generation 尚未物化的 failed attempt 审计（不可变列表，默认空）。 */
  private List<HarnessGenerationFailureDTO> generationFailures = List.of();

  /** 活跃 Assistant 结果对应的工具兄弟列表（callIndex 为 0..N-1 连续前缀；不可变列表，默认空）。 */
  private List<ToolInvocationDTO> toolInvocations = List.of();

  /** 当前瞬时手动压缩可用性；实际提交仍由 compact 请求的 expectedVersion 守护。 */
  private HarnessManualCompactionDTO manualCompaction;

  /** 该 Thread 自己的、包含可恢复人工输入的持久 Stop 回执（不可变列表，默认空）。 */
  private List<HarnessStoppedThreadReceiptDTO> stopReceipts = List.of();

  /** 当前仍为 UNCONFIRMED 的执行代次（不含已放弃或已确认历史；不可变列表，默认空）。 */
  private List<HarnessExecutionExitDTO> unconfirmedExecutions = List.of();
}
