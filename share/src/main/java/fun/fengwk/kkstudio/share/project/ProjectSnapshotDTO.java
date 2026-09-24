package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Project Snapshot 权威聚合读取面。
 *
 * <p>包含 project、未归档 issues（各含 blocked 与当前/最近 Run）与同项目依赖边；Issue Agent Session 归属通过 Issue 详情按 {@code
 * (issueId, agentName)} 读取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSnapshotDTO {

  private ProjectDTO project;
  private List<ProjectIssueSnapshotDTO> issues;
  private List<IssueDependencyDTO> dependencies;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
