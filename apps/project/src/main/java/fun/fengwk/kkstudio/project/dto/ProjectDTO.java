package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Project 公开传输对象。
 *
 * <p>UUID 以 canonical lowercase string 传输，版本号与计数以 canonical non-negative decimal string 传输。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDTO {

  private String id;
  private String title;
  private String description;
  private ProjectWorkflowDTO workflow;
  private Boolean yoloEnabled;
  private String nextIssueNumber;
  private String version;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Instant archivedAt;

  private Instant createdAt;
  private Instant updatedAt;
}
