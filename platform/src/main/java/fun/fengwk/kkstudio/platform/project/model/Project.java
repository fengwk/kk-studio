package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Project 核心实体：项目资料、YOLO 策略、严格 workflow JSON 与 Issue 编号分配器。
 *
 * <p>工作流整体保存为一份严格 JSON（唯一事实源），不是关系化的 states/transitions/dependencies；数据库只保证它是 object 且 {@code
 * states} 是 array，成员关系与边合法性由服务在 Project UPDATE 锁与版本检查下校验。Project 只是容器与项目级设置， 不持有 Agent、Session
 * 与执行状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

  private UUID id;
  private String title;
  private String description;

  /** 严格 workflow JSON 文本；解码使用领域 {@code ProjectWorkflowJsonCodec}，编码输出确定性规范文本。 */
  private String workflowJson;

  /** Project 下 Issue 运行的 YOLO 策略，随配置编辑用版本 CAS 修改。 */
  private boolean yoloEnabled;

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
