package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

class ToolDomainContractsTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void validatesToolInvocationSnapshotAndBoundTarget() {
    ToolInvocation platform = invocation(1L, 2L, 3L, 0, "call", "tool", "1", null);
    ToolInvocation environment = invocation(1L, 2L, 3L, 0, "call", "tool", "1", "env-9");
    assertEquals(InvocationStatus.QUEUED, platform.status());
    assertEquals(null, platform.environmentName());
    assertEquals("env-9", environment.environmentName());

    assertThrows(
        IllegalArgumentException.class, () -> invocation(0L, 2L, 3L, 0, "call", "tool", "1", null));
    assertThrows(
        IllegalArgumentException.class, () -> invocation(1L, 0L, 3L, 0, "call", "tool", "1", null));
    assertThrows(
        IllegalArgumentException.class, () -> invocation(1L, 2L, 0L, 0, "call", "tool", "1", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 2L, 3L, -1, "call", "tool", "1", null));
    assertThrows(
        IllegalArgumentException.class, () -> invocation(1L, 2L, 3L, 0, " ", "tool", "1", null));
    assertThrows(
        IllegalArgumentException.class, () -> invocation(1L, 2L, 3L, 0, "call", "tool", "1", " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 2L, 3L, 0, "call", "tool", "1", "e".repeat(129)));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 2L, 3L, 0, "x".repeat(257), "tool", "1", null));
  }

  @Test
  void failedAcceptsPendingAllowedAndDenied() {
    assertEquals(InvocationStatus.FAILED, failed(ToolPermissionState.PENDING).status());
    assertEquals(
        ToolPermissionState.PENDING, failed(ToolPermissionState.PENDING).permissionState());
    assertEquals(InvocationStatus.FAILED, failed(ToolPermissionState.ALLOWED).status());
    assertEquals(
        ToolPermissionState.ALLOWED, failed(ToolPermissionState.ALLOWED).permissionState());
    assertEquals(InvocationStatus.FAILED, failed(ToolPermissionState.DENIED).status());
    assertEquals(ToolPermissionState.DENIED, failed(ToolPermissionState.DENIED).permissionState());
  }

  @Test
  void failedRejectsAsked() {
    assertThrows(IllegalArgumentException.class, () -> failed(ToolPermissionState.ASKED));
  }

  @Test
  void validatesFrozenToolBinding() {
    ToolBinding platform = ToolBinding.of(descriptor("tool"));
    assertEquals(null, platform.environmentName());
    ToolBinding environment = ToolBinding.of(descriptor("environment"), "env-9");
    assertEquals("env-9", environment.environmentName());

    assertThrows(
        IllegalArgumentException.class, () -> ToolBinding.of(descriptor("environment"), " "));
    assertThrows(
        IllegalArgumentException.class, () -> ToolBinding.of(descriptor("environment"), "\tenv"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolBinding.of(descriptor("environment"), "e".repeat(129)));
    assertThrows(
        IllegalArgumentException.class, () -> ToolBinding.of(descriptor("tool", "v".repeat(129))));
  }

  private static ToolInvocation failed(ToolPermissionState permissionState) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor("tool"),
        "{}",
        null,
        0L,
        InvocationStatus.FAILED,
        1,
        null,
        null,
        NOW.plusSeconds(30),
        NOW,
        null,
        new ToolInvocationError("EXECUTION_FAILED", "boom"),
        null,
        NOW,
        NOW,
        NOW,
        permissionState,
        false);
  }

  private static ToolInvocation invocation(
      long id,
      long threadId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String toolName,
      String toolVersion,
      String environmentName) {
    return new ToolInvocation(
        id,
        threadId,
        assistantEntryId,
        assistantEntryId + 1,
        ordinal,
        toolCallId,
        descriptor(toolName, toolVersion),
        "{}",
        environmentName,
        0L,
        InvocationStatus.QUEUED,
        1,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        null,
        null,
        ToolPermissionState.PENDING,
        false);
  }

  private static ToolDescriptor descriptor(String name) {
    return descriptor(name, "1");
  }

  private static ToolDescriptor descriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
        name,
        null,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.IDEMPOTENT,
        Duration.ofSeconds(1));
  }
}
