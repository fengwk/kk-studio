package fun.fengwk.kkstudio.harness.runtime.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunContractsTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** 状态机只接受 DESIGN 声明的边，terminal 状态不可再次迁移。 */
  @Test
  void enforcesExplicitRunTransitions() {
    assertTrue(RunStatus.QUEUED.canTransitionTo(RunStatus.RUNNING));
    assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.QUEUED));
    assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.WAITING_TOOLS));
    assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.SUCCEEDED));
    assertTrue(RunStatus.WAITING_TOOLS.canTransitionTo(RunStatus.QUEUED));
    assertTrue(RunStatus.WAITING_TOOLS.canTransitionTo(RunStatus.FAILED));
    assertTrue(RunStatus.WAITING_TOOLS.canTransitionTo(RunStatus.CANCELLED));
    assertFalse(RunStatus.QUEUED.canTransitionTo(RunStatus.SUCCEEDED));
    assertTrue(RunStatus.SUCCEEDED.terminal());
    assertThrows(
        IllegalStateException.class,
        () -> RunStatus.SUCCEEDED.requireTransitionTo(RunStatus.RUNNING));
  }

  /** Run 快照拒绝无效 lease、计数器或 terminal timestamp 组合。 */
  @Test
  void validatesImmutableRunSnapshot() {
    AgentRun running = run(RunStatus.RUNNING, "worker", NOW.plusSeconds(10), null);
    assertTrue(running.isOwnedBy("worker", 1));
    assertFalse(running.isOwnedBy("other", 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> run(RunStatus.QUEUED, "worker", NOW.plusSeconds(10), null));
    assertThrows(IllegalArgumentException.class, () -> run(RunStatus.SUCCEEDED, null, null, null));
  }

  /** Event payload 必须是含正 schemaVersion 的 JSON object。 */
  @Test
  void validatesRunEventPayloadSchemaVersion() {
    RunEvent event =
        new RunEvent(1L, 2L, 1L, RunEventType.RUN_STARTED, "{\"schemaVersion\":1}", NOW);
    assertEquals(RunEventType.RUN_STARTED, RunEventType.fromValue(event.type().value()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RunEvent(1L, 2L, 1L, RunEventType.RUN_STARTED, "{}", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RunEvent(1L, 2L, 1L, RunEventType.RUN_STARTED, "not-json", NOW));
    assertThrows(IllegalArgumentException.class, () -> RunEventType.fromValue("unknown"));
  }

  /** Worker 参数固定在 100-250ms、8-16KiB 范围，并按 attempt 指数退避。 */
  @Test
  void validatesWorkerConfigAndComputesBackoff() {
    RunWorkerConfig config = RunWorkerConfig.DEFAULT;
    assertEquals(Duration.ofSeconds(1), config.backoffForAttempt(1));
    assertEquals(Duration.ofSeconds(4), config.backoffForAttempt(3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunWorkerConfig(
                Duration.ofSeconds(1), Duration.ofMillis(99), 8 * 1024, 1, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunWorkerConfig(
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                17 * 1024,
                1,
                Duration.ofSeconds(1)));
  }

  /** Provider projection 保留文本、多模态、thinking、JSON、工具关联和 Artifact preview。 */
  @Test
  void projectsEverySemanticMessageContent() {
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new TextMessageContent("text"),
                new ImageMessageContent("image/png", "image"),
                new AudioMessageContent("audio/wav", "audio"),
                new ThinkingMessageContent("think"),
                new JsonMessageContent("{\"ok\":true}"),
                new ToolCallMessageContent("call-1", "read", "{}"),
                new ArtifactMessageContent("artifact-1", "text/plain", "preview")));
    AgentMessage tool =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-1", "read", List.of(new TextMessageContent("result")), false, "{}")));

    List<ProviderMessage> projected =
        new ProviderMessageProjector().project(List.of(assistant, tool));

    assertInstanceOf(ProviderTextBlock.class, projected.get(0).contents().get(0));
    assertInstanceOf(ProviderImageBlock.class, projected.get(0).contents().get(1));
    assertInstanceOf(ProviderAudioBlock.class, projected.get(0).contents().get(2));
    assertInstanceOf(ProviderThinkingBlock.class, projected.get(0).contents().get(3));
    assertInstanceOf(ProviderJsonBlock.class, projected.get(0).contents().get(4));
    assertInstanceOf(ProviderToolCallBlock.class, projected.get(0).contents().get(5));
    ProviderTextBlock artifact =
        assertInstanceOf(ProviderTextBlock.class, projected.get(0).contents().get(6));
    assertTrue(artifact.text().contains("artifact-1"));
    ProviderToolResultBlock result =
        assertInstanceOf(ProviderToolResultBlock.class, projected.get(1).contents().get(0));
    assertEquals("read", result.toolName());
  }

  private static AgentRun run(
      RunStatus status, String leaseOwner, Instant leaseUntil, Instant finishedAt) {
    return new AgentRun(
        1L,
        2L,
        3L,
        status,
        0,
        1,
        0,
        leaseOwner,
        leaseUntil,
        NOW,
        null,
        NOW,
        status == RunStatus.QUEUED ? null : NOW,
        finishedAt,
        NOW);
  }
}
