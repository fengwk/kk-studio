package fun.fengwk.kkstudio.platform.harness.thread.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.interaction.EnvironmentToolWait;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class EnvironmentWaitProjectionServiceTest {

  /** 同环境只查询一次，在线/离线、非 READY 与 Work 截止点分别影响各调用。 */
  @Test
  void combinesFrozenWorkFactsWithEnvironmentLeases() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    EnvironmentService environments = mock(EnvironmentService.class);
    EnvironmentWaitProjectionService service =
        new EnvironmentWaitProjectionService(runtime, environments);
    EnvironmentId online = EnvironmentId.of(new UUID(0, 1));
    EnvironmentId offline = EnvironmentId.of(new UUID(0, 2));
    Instant lease = Instant.parse("2026-01-01T00:02:00Z");
    Instant due = lease.minusSeconds(30);
    ToolInvocationDTO available = tool(11);
    ToolInvocationDTO future = tool(12);
    ToolInvocationDTO blocked = tool(13);
    ToolInvocationDTO running = tool(14);
    ToolInvocationDTO server = tool(15);
    HarnessThreadSnapshotDTO snapshot = new HarnessThreadSnapshotDTO();
    snapshot.setToolInvocations(List.of(available, future, blocked, running, server));
    when(runtime.listEnvironmentToolWaits(Set.of(id(11), id(12), id(13), id(14), id(15))))
        .thenReturn(
            List.of(
                new EnvironmentToolWait(id(11), online, true, true, null),
                new EnvironmentToolWait(id(12), online, true, false, due),
                new EnvironmentToolWait(id(13), offline, true, true, null),
                new EnvironmentToolWait(id(14), offline, false, false, null),
                new EnvironmentToolWait(id(15), null, true, false, null)));
    when(environments.requireName(online)).thenReturn("online");
    when(environments.requireName(offline)).thenReturn("offline");
    when(environments.findReadyLeaseUntil(online)).thenReturn(Optional.of(lease));
    when(environments.findReadyLeaseUntil(offline)).thenReturn(Optional.empty());

    service.project(snapshot);

    assertEquals(online.toString(), available.getRequiredEnvironmentId());
    assertEquals("online", available.getRequiredEnvironmentName());
    assertFalse(available.isWaitingForEnvironment());
    assertEquals(lease, available.getEnvironmentWaitFreshnessAt());
    assertFalse(future.isWaitingForEnvironment());
    assertEquals(due, future.getEnvironmentWaitFreshnessAt());
    assertTrue(blocked.isWaitingForEnvironment());
    assertNull(blocked.getEnvironmentWaitFreshnessAt());
    assertFalse(running.isWaitingForEnvironment());
    assertNull(running.getEnvironmentWaitFreshnessAt());
    assertNull(server.getRequiredEnvironmentId());
    verify(environments, times(1)).requireName(online);
    verify(environments, times(1)).findReadyLeaseUntil(online);
    verify(environments, times(1)).findReadyLeaseUntil(offline);
  }

  private static UUID id(long value) {
    return new UUID(0, value);
  }

  private static ToolInvocationDTO tool(long value) {
    ToolInvocationDTO tool = new ToolInvocationDTO();
    tool.setId(id(value).toString());
    return tool;
  }
}
