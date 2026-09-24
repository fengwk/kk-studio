package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Project 核心实体：项目资料、YOLO 与审查打回阈值及 Issue 编号分配器。
 *
 * <p>Project 只是容器和项目级设置，不持有 Agent、Session 与执行状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

  private UUID id;
  private String title;
  private String description;

  /** Project 下 Issue 工作 Branch 的 YOLO 策略，随现有编辑操作用版本 CAS 修改。 */
  private boolean yoloEnabled;

  /** 本 Issue 连续被正式审查打回后转 {@code BLOCKED} 的阈值，正整数。 */
  private int maxReviewRejections;

  /** 项目内单调递增 Issue 编号分配器。 */
  private long nextIssueNumber;

  private long version;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;

  public boolean isArchived() {
    return archivedAt != null;
  }
}
