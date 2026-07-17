package fun.fengwk.kkstudio.harness.runtime.extension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.AssistantCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.CompactionCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.RunTerminated;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.TurnStarted;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class HarnessLifecycleContractsTest {

  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

  /** 所有 observation record 接受合法事实并拒绝非法 id、counter、time 与 status。 */
  @Test
  void validatesObservationInvariants() {
    assertDoesNotThrow(() -> new TurnStarted(1, 2, 1, 0, NOW));
    assertDoesNotThrow(() -> new AssistantCompleted(1, 2, 0, ProviderStopReason.COMPLETED, NOW));
    assertDoesNotThrow(() -> new RunTerminated(1, 2, RunStatus.SUCCEEDED, NOW));
    assertDoesNotThrow(() -> new CompactionCompleted(1, 2, 3, NOW));
    assertDoesNotThrow(() -> new ToolCompleted(4, 1, ToolInvocationStatus.FAILED, "boom", NOW));

    assertThrows(IllegalArgumentException.class, () -> new TurnStarted(0, 2, 1, 0, NOW));
    assertThrows(IllegalArgumentException.class, () -> new TurnStarted(1, 0, 1, 0, NOW));
    assertThrows(IllegalArgumentException.class, () -> new TurnStarted(1, 2, 0, 0, NOW));
    assertThrows(IllegalArgumentException.class, () -> new TurnStarted(1, 2, 1, -1, NOW));
    assertThrows(NullPointerException.class, () -> new TurnStarted(1, 2, 1, 0, null));

    assertThrows(
        IllegalArgumentException.class,
        () -> new AssistantCompleted(1, 2, -1, ProviderStopReason.COMPLETED, NOW));
    assertThrows(NullPointerException.class, () -> new AssistantCompleted(1, 2, 0, null, NOW));
    assertThrows(
        NullPointerException.class,
        () -> new AssistantCompleted(1, 2, 0, ProviderStopReason.COMPLETED, null));

    assertThrows(NullPointerException.class, () -> new RunTerminated(1, 2, null, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new RunTerminated(1, 2, RunStatus.RUNNING, NOW));
    assertThrows(NullPointerException.class, () -> new RunTerminated(1, 2, RunStatus.FAILED, null));

    assertThrows(IllegalArgumentException.class, () -> new CompactionCompleted(1, 2, 0, NOW));
    assertThrows(NullPointerException.class, () -> new CompactionCompleted(1, 2, 3, null));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCompleted(0, 1, ToolInvocationStatus.FAILED, null, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCompleted(1, 0, ToolInvocationStatus.FAILED, null, NOW));
    assertThrows(NullPointerException.class, () -> new ToolCompleted(1, 2, null, null, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCompleted(1, 2, ToolInvocationStatus.RUNNING, null, NOW));
    assertThrows(
        NullPointerException.class,
        () -> new ToolCompleted(1, 2, ToolInvocationStatus.SUCCEEDED, null, null));
  }

  /** publisher 保持 Host 顺序，单个 observer 失败只记录 WARNING 并继续。 */
  @Test
  void publishesInOrderAndIsolatesObserverFailures() {
    List<String> calls = new ArrayList<>();
    HarnessLifecycleObservers observers =
        new HarnessLifecycleObservers(
            List.of(
                observation -> calls.add("first"),
                observation -> {
                  calls.add("broken");
                  throw new IllegalStateException("boom");
                },
                observation -> calls.add("last")));

    observers.publish(new TurnStarted(1, 2, 1, 0, NOW));

    assertEquals(List.of("first", "broken", "last"), calls);
    assertThrows(NullPointerException.class, () -> observers.publish(null));
    assertThrows(NullPointerException.class, () -> new HarnessLifecycleObservers(null));
    assertThrows(
        NullPointerException.class,
        () -> new HarnessLifecycleObservers(Arrays.asList(observation -> {}, null)));
  }

  /** Compaction seam 固定正 session id 与非 null SessionContext。 */
  @Test
  void validatesBeforeCompactionContext() {
    SessionContext context =
        new SessionContext(
            new AgentRuntimeConfig(
                "system", "model", "variant", List.of(), List.of(), List.of(), "{}"),
            List.of());

    assertEquals(context, new BeforeCompactionContext(1, context).context());
    assertThrows(IllegalArgumentException.class, () -> new BeforeCompactionContext(0, context));
    assertThrows(NullPointerException.class, () -> new BeforeCompactionContext(1, null));
  }
}
