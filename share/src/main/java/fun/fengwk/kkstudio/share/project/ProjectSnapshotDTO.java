package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Project Snapshot 权威聚合读取面 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSnapshotDTO {

  private ProjectDTO project;
  private List<ProjectIssueSnapshotDTO> issues;

  /** 所有未归档及归档 Issue 的当前状态与阻塞恢复目标，去重并按字符串自然顺序排序。 */
  private List<String> referencedStateCodes;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
