package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeRequest;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 诊断不信任对端的自由文本，避免错误消息和关闭原因把凭据带进日志。 */
class IdeStreamDiagnosticsTest {

  @Test
  void closeSummaryContainsOnlyTheCode() {
    assertEquals("-", IdeStreamDiagnostics.describe(null));
    assertEquals(
        "1008",
        IdeStreamDiagnostics.describe(
            CloseStatus.POLICY_VIOLATION.withReason("synthetic-sensitive-close-reason")));
  }

  @Test
  void failedReplyIsClassifiedWithoutItsMessage() {
    DaemonConnectionIdentity identity =
        new DaemonConnectionIdentity(
            EnvironmentId.parse("11111111-1111-1111-1111-111111111111"),
            "33333333-3333-3333-3333-333333333333");
    CompletableFuture<IdeReply> reply = new CompletableFuture<>();
    IdeRequest request = new IdeRequest(identity, UUID.randomUUID(), reply);
    assertEquals("-", IdeStreamDiagnostics.replyFailure(request, Duration.ZERO));
    reply.complete(IdeReply.failure("synthetic-sensitive-reply"));
    assertEquals("REJECTED", IdeStreamDiagnostics.replyFailure(request, Duration.ZERO));
    assertEquals("-", IdeStreamDiagnostics.replyFailure(null, Duration.ZERO));
  }
}
