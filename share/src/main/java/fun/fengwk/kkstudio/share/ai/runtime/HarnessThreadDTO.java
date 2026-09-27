package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/**
 * HarnessThread 查询投影；实体 id 均为 canonical UUID string，{@code version} 为 durable snapshot
 * cursor（非负十进制字符串）。
 *
 * <p>{@code status} 与 {@code processing} 都直接来自 Thread 快照的 runtime 状态投影，不属于 durable 列，也不由调用方拼装：
 * {@code status} 覆盖自身执行阶段（{@code IDLE / QUEUED / CONTINUATION_DUE / MODEL_* / TOOL_* /
 * APPLYING}）以及递归生命周期 （{@code WAITING_CHILDREN} 表示本地已静止但仍有活跃直接孩子），{@code processing} 等价于 {@code
 * status != IDLE}，因此 {@code WAITING_CHILDREN} 与 {@code QUEUED} 同样是处理中；{@code branchSettings} 是 head
 * Entry 分支的完整设置快照。
 *
 * <p>{@code parentThreadId} 是该 Thread 不可变执行父关系的展示投影（根 Thread 为 null）；它只表达执行关系，Session 历史仍然按 Entry
 * 路径读取，不因父关系而混入其他 Thread 的对话。
 */
@Data
public class HarnessThreadDTO {
  /** Thread 主键：canonical UUID string。 */
  private String threadId;

  /** 执行父 Thread（根 Thread 为 null）：canonical UUID string。 */
  private String parentThreadId;

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

  /**
   * 展示状态（派生）：{@code IDLE / QUEUED / CONTINUATION_DUE / MODEL_<status> / TOOL_<status> / APPLYING /
   * WAITING_CHILDREN}。
   */
  private String status;

  /** 该 Thread 当前是否仍在处理（派生）：{@code status != IDLE}。 */
  private Boolean processing;

  /** head Entry 分支的完整设置快照。 */
  private HarnessBranchSettingsDTO branchSettings;

  /** Thread 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** Thread 最后更新时间（UTC Instant）。 */
  private Instant updateTime;
}
