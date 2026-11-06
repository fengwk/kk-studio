package fun.fengwk.kkstudio.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Project 看板快照：项目资料、Issue 投影与工作流引用集合。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSnapshotDTO {

  private ProjectDTO project;
  private List<ProjectIssueSnapshotDTO> issues;

  /** 所有 Issue 的当前状态与阻塞恢复目标，去重并按字符串自然顺序排序。 */
  private List<String> referencedStateCodes;
}
