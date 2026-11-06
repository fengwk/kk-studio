package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;

import java.time.Instant;

/** IssueRun 完整执行事实 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRunDTO {

  private String id;
  private String issueId;
  private Long ordinal;

  /** 工作流自然状态编码，保持开放字符串。 */
  private String state;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String agentName;

  private String sessionId;
  private String threadId;
  private IssueRunStatus status;
  private String startEntryId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String endEntryId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String finalAnswerEntryId;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String nextState;

  private Long observedActivitySequence;
  private Long remainingExecutionMs;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String error;

  private Long version;
  private Instant startedAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Instant endedAt;
}
