package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;

import java.util.List;

/**
 * Project Snapshot 权威聚合读取面。
 *
 * <p>包含 project、未归档 issues（各含 blocked 与当前/最近 run）、依赖关系、Coordinator session 与 thread 投影。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSnapshotDTO {

  private ProjectDTO project;
  private List<ProjectIssueSnapshotDTO> issues;
  private List<IssueDependencyDTO> dependencies;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String coordinatorSessionId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private HarnessSessionSummaryDTO coordinatorSession;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private HarnessThreadSummaryDTO coordinatorThread;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
