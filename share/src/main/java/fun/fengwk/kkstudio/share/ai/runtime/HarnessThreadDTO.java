package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/**
 * HarnessThread 查询投影；id 均为 strict positive decimal string，{@code revision} 为 durable snapshot
 * cursor。
 *
 * <p>{@code status} 与 {@code processing} 由 Thread 快照确定性派生（processing 仅 IDLE 为 false）， 不属于 durable
 * 列；{@code branchSettings} 是 head Entry 分支的完整设置快照。
 */
@Data
public class HarnessThreadDTO {
  private String threadId;

  /** 当前 Session 主键（由 head Entry 派生）。 */
  private String sessionId;

  /** 当前 head Entry。 */
  private String headEntryId;

  /** 当前 frozen YOLO runtime policy。 */
  private Boolean yoloEnabled;

  /** 已分配的 command sequence 高水位 +1。 */
  private String nextCommandSequence;

  /** PostgreSQL authoritative durable projection cursor (non-negative decimal bigint string)。 */
  private String revision;

  /** 展示状态（派生）：{@code IDLE / CONTINUATION_DUE / MODEL_<status> / TOOL_<status> / APPLYING}。 */
  private String status;

  /** 是否正在被 runtime 处理（派生）。 */
  private Boolean processing;

  /** head Entry 分支的完整设置快照。 */
  private HarnessBranchSettingsDTO branchSettings;

  private Instant createTime;
  private Instant updateTime;
}
