package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/**
 * HarnessThread 查询投影；实体 id 均为 canonical UUID string，{@code version} 为 durable snapshot
 * cursor（非负十进制字符串）。
 *
 * <p>{@code status} 与 {@code processing} 由 Thread 快照确定性派生，不属于 durable 列：{@code status} 只描述该 Thread
 * 自身的执行，{@code processing} 则聚合"自身执行中"与"委派子树仍有未交付结果"，因此 {@code status} 为 {@code IDLE} 时 {@code
 * processing} 仍可能为 true；{@code branchSettings} 是 head Entry 分支的完整设置快照。
 */
@Data
public class HarnessThreadDTO {
  /** Thread 主键：canonical UUID string。 */
  private String threadId;

  /** Thread 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  private String name;

  /** 当前 Session 主键（由 head Entry 派生）：canonical UUID string。 */
  private String sessionId;

  /** 当前 head Entry：canonical UUID string。 */
  private String headEntryId;

  /** 当前 frozen YOLO runtime policy。 */
  private Boolean yoloEnabled;

  /** 已分配的 command sequence 高水位 +1（strict positive decimal string）。 */
  private String nextCommandSequence;

  /** PostgreSQL authoritative durable projection cursor (non-negative decimal bigint string)。 */
  private String version;

  /** 展示状态（派生）：{@code IDLE / CONTINUATION_DUE / MODEL_<status> / TOOL_<status> / APPLYING}。 */
  private String status;

  /** 该 Thread 及其委派子树当前是否仍在处理（派生）：自身执行中，或子树仍有未交付的异步 task 结果。 */
  private Boolean processing;

  /** head Entry 分支的完整设置快照。 */
  private HarnessBranchSettingsDTO branchSettings;

  /** Thread 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** Thread 最后更新时间（UTC Instant）。 */
  private Instant updateTime;
}
