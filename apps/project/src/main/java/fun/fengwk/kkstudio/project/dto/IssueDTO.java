package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.project.model.PauseReason;

import java.time.Instant;

/**
 * Issue 公开传输对象。
 *
 * <p>UUID 以 canonical lowercase string 传输，编号与版本以 canonical non-negative decimal string 传输；{@code
 * state} 是项目工作流定义的自然状态编码，保持开放字符串。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueDTO {

  private String id;
  private String projectId;
  private Long number;
  private String title;
  private String description;
  private String state;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String blockedFromState;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String blockReason;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable PauseReason pauseReason;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String pauseDetail;

  private Long version;
  private Instant createdAt;
  private Instant updatedAt;
}
