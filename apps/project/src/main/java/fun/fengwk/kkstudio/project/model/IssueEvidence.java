package fun.fengwk.kkstudio.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code project_issue_evidence} 表模型：Issue 显式公开的 Blob 证据引用。
 *
 * <p>同 Issue 同 Blob 至多一条（复合主键幂等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueEvidence {

  private UUID issueId;
  private UUID blobId;
  private String actorAgentName;
  private UUID runId;
  private String name;
  private Instant createdAt;
}
