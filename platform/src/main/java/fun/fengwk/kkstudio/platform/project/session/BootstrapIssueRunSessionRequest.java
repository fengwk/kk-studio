package fun.fengwk.kkstudio.platform.project.session;

import java.util.Objects;
import java.util.UUID;

/**
 * IssueRun Harness Session 引导请求。
 *
 * <p>包含 exact replay 所需的全部持久化调用方身份与初始纯文本用户消息。
 */
public record BootstrapIssueRunSessionRequest(
    UUID runId,
    UUID sessionId,
    UUID threadId,
    UUID initialCommandIdempotencyKey,
    String initialMessage) {

  public BootstrapIssueRunSessionRequest {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(initialCommandIdempotencyKey, "initialCommandIdempotencyKey");
    Objects.requireNonNull(initialMessage, "initialMessage");
  }
}
