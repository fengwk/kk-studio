package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueInput {

  private UUID issueId;
  private long sequence;
  private IssueInputKind kind;
  private String body;
  private String idempotencyKey;
  private Instant createdAt;
}
