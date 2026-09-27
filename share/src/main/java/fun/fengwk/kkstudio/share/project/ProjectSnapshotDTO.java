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

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
