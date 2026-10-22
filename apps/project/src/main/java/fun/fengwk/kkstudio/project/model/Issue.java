package fun.fengwk.kkstudio.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;

import java.time.Instant;
import java.util.UUID;

/**
 * Issue 核心实体：workflow state、阻塞恢复点、控制暂停门禁与编号/序号分配器。
 *
 * <p>{@code state} 是项目内自然状态编码；BLOCKED 与 {@code blockedFromState/blockReason} 同存同缺，人工或执行失败/不确定的暂停由
 * {@code pauseReason/pauseDetail} 成对表达。{@code nextRunOrdinal} 与 {@code nextActivitySequence}
 * 是接受事务内分配 的单调游标， {@code version} 是 Issue 行的乐观锁 CAS。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Issue {

  private UUID id;
  private UUID projectId;
  private long number;
  private String title;
  private String description;
  private String state;

  /** BLOCKED 的恢复目标；非 BLOCKED 状态必须为空。 */
  private String blockedFromState;

  /** BLOCKED 的业务原因；非 BLOCKED 状态必须为空。 */
  private String blockReason;

  /** 控制暂停原因：USER / ERROR / UNKNOWN；与 {@code pauseDetail} 成对。 */
  private String pauseReason;

  /** 控制暂停详情（非空白）；与 {@code pauseReason} 成对。 */
  private String pauseDetail;

  private long nextRunOrdinal;
  private long nextActivitySequence;
  private long version;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;

  public boolean isArchived() {
    return archivedAt != null;
  }

  /** 是否存在阻止新派发的控制暂停门禁。 */
  public boolean isPaused() {
    return pauseReason != null;
  }

  /** 是否处于业务阻塞状态。 */
  public boolean isBlocked() {
    return ProjectWorkflowReservedState.BLOCKED.code().value().equals(state)
        || blockedFromState != null;
  }

  /** 是否存在阻止新派发的控制暂停或业务阻塞门禁。 */
  public boolean isGateClosed() {
    return isPaused() || isBlocked();
  }
}
