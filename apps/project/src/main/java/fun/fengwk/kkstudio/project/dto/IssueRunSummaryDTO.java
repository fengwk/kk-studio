package fun.fengwk.kkstudio.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;

import java.time.Instant;

/** IssueRun 概要信息 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueRunSummaryDTO {

  private String id;
  private String issueId;
  private Long ordinal;

  /** 工作流自然状态编码，保持开放字符串。 */
  private String state;

  private IssueRunStatus status;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String agentName;

  private Instant startedAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Instant endedAt;
}
