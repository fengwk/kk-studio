package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Coherent Thread 快照投影。
 *
 * <p>所有字段来自同一数据库快照；{@code revision} 是 durable invalidation cursor。{@code modelInvocation} 为当前 Turn
 * 的活跃模型调用（无则 null），{@code toolInvocations} 为其工具兄弟； 列表默认为不可变空列表。
 */
@Data
public class HarnessThreadSnapshotDTO {
  /**
   * durable invalidation cursor：strict non-negative decimal string，等于 {@link #thread} 的 revision。
   */
  private String revision;

  /** 同一数据库快照下的完整 Thread 投影。 */
  private HarnessThreadDTO thread;

  /** root-to-head 的完整 Session Entry 链（不可变列表，默认空）。 */
  private List<HarnessSessionEntryDTO> entries = List.of();

  /** 当前仍处于 QUEUED 状态的命令列表（不可变列表，默认空）。 */
  private List<HarnessThreadCommandDTO> queuedCommands = List.of();

  /** 当前 Turn 的活跃 ModelInvocation 投影；无则 null（{@code @JsonInclude(ALWAYS)} 保证 null 也输出）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private ModelInvocationDTO modelInvocation;

  /** 活跃 ModelInvocation 的工具兄弟列表（ordinal 为 0..N-1 连续前缀；不可变列表，默认空）。 */
  private List<ToolInvocationDTO> toolInvocations = List.of();

  /** 当前 branch 可见的 Model failed attempt 历史（不可变列表，默认空）。 */
  private List<ModelAttemptFailureDTO> modelAttemptFailures = List.of();
}
