package fun.fengwk.kkstudio.platform.project.session;

import java.util.Objects;
import java.util.UUID;

public record BootstrapIssueAgentSessionRequest(
    UUID issueId,
    String agentName,
    UUID sessionId,
    UUID threadId,
    UUID initialCommandIdempotencyKey,
    String initialMessage) {

  public BootstrapIssueAgentSessionRequest {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(agentName, "agentName");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(initialCommandIdempotencyKey, "initialCommandIdempotencyKey");
    Objects.requireNonNull(initialMessage, "initialMessage");
  }
}
