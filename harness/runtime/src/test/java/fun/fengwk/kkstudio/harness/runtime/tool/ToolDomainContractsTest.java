package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolDomainContractsTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** ToolInvocation 快照保存单 bigint id、冻结 binding 和 permission/result 状态。 */
  @Test
  void validatesToolInvocationSnapshot() {
    ToolInvocation invocation = invocation(1L, 2L, 3L, 0, "call", "tool", "1", null);
    assertEquals(ToolInvocationStatus.QUEUED, invocation.status());
    assertEquals(PermissionAction.ALLOW, invocation.permissionAction());

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
        IllegalArgumentException.class, () -> invocation(1L, 2L, 3L, 0, "call", " ", "1", null));
    assertThrows(
        IllegalArgumentException.class, () -> invocation(1L, 2L, 3L, 0, "call", "tool", " ", null));
    assertThrows(
        IllegalArgumentException.class, () -> invocation(1L, 2L, 3L, 0, "call", "tool", "1", 0L));
    assertThrows(
        IllegalArgumentException.class, () -> invocation(1L, 2L, 3L, 0, "call", "tool", "1", 9L));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 2L, 3L, 0, "call", "tool", "1", ToolTargetType.ENVIRONMENT, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 2L, 3L, 0, "x".repeat(257), "tool", "1", null));
  }

  /** Binding 目标必须来自 descriptor execution mode，environmentId 只属于 ENVIRONMENT。 */
  @Test
  void validatesFrozenToolBinding() {
    ToolBinding cloud = ToolBinding.of(descriptor("tool", ToolExecutionMode.CLOUD));
    assertEquals(ToolTargetType.CLOUD, cloud.targetType());
    ToolBinding environment =
        new ToolBinding(
            descriptor("environment", ToolExecutionMode.ENVIRONMENT),
            ToolTargetType.ENVIRONMENT,
            9L);
    assertEquals(9L, environment.environmentId());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                descriptor("tool", ToolExecutionMode.CLOUD), ToolTargetType.CONTROL, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(descriptor("tool", ToolExecutionMode.CLOUD), ToolTargetType.CLOUD, 1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                descriptor("environment", ToolExecutionMode.ENVIRONMENT),
                ToolTargetType.ENVIRONMENT,
                0L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                descriptor("environment", ToolExecutionMode.ENVIRONMENT),
                ToolTargetType.ENVIRONMENT,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolBinding.of(descriptor("environment", ToolExecutionMode.ENVIRONMENT)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolBinding.of(descriptor("tool", ToolExecutionMode.CLOUD, "v".repeat(129))));
  }

  /** Prepared invocation action/status/result 必须一致，并再次验证 call schema。 */
  @Test
  void validatesPreparedInvocationConsistency() {
    ToolBinding binding = ToolBinding.of(descriptor("tool", ToolExecutionMode.CLOUD));
    ToolCall call = new ToolCall("call", "tool", "{}");
    PermissionPromptPreview preview = new PermissionPromptPreview("tool", ".", "{}");
    PreparedToolInvocation pending =
        new PreparedToolInvocation(
            1L,
            0,
            binding,
            call,
            PermissionAction.ASK,
            ToolInvocationStatus.WAITING_APPROVAL,
            NOW,
            preview,
            null,
            null);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, pending.initialStatus());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreparedToolInvocation(
                1L,
                0,
                binding,
                new ToolCall("x".repeat(257), "tool", "{}"),
                PermissionAction.ASK,
                ToolInvocationStatus.WAITING_APPROVAL,
                NOW,
                preview,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreparedToolInvocation(
                0L,
                0,
                binding,
                call,
                PermissionAction.ASK,
                ToolInvocationStatus.WAITING_APPROVAL,
                NOW,
                preview,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreparedToolInvocation(
                1L,
                -1,
                binding,
                call,
                PermissionAction.ASK,
                ToolInvocationStatus.WAITING_APPROVAL,
                NOW,
                preview,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreparedToolInvocation(
                1L,
                0,
                binding,
                call,
                PermissionAction.ALLOW,
                ToolInvocationStatus.WAITING_APPROVAL,
                NOW,
                preview,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreparedToolInvocation(
                1L,
                0,
                binding,
                call,
                PermissionAction.DENY,
                ToolInvocationStatus.FAILED,
                NOW,
                preview,
                null,
                null));
  }

  /** API decision parser 大小写兼容并拒绝空值与未知决定。 */
  @Test
  void parsesPermissionDecision() {
    assertEquals(ToolPermissionDecision.ALLOW, ToolPermissionDecision.fromApiValue(" Allow "));
    assertEquals(ToolPermissionDecision.DENY, ToolPermissionDecision.fromApiValue("deny"));
    assertThrows(IllegalArgumentException.class, () -> ToolPermissionDecision.fromApiValue(null));
    assertThrows(
        IllegalArgumentException.class, () -> ToolPermissionDecision.fromApiValue("approve"));
  }

  private static ToolInvocation invocation(
      long id,
      long runId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String toolName,
      String toolVersion,
      Long environmentId) {
    return invocation(
        id,
        runId,
        assistantEntryId,
        ordinal,
        toolCallId,
        toolName,
        toolVersion,
        ToolTargetType.CLOUD,
        environmentId);
  }

  private static ToolInvocation invocation(
      long id,
      long runId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String toolName,
      String toolVersion,
      ToolTargetType targetType,
      Long environmentId) {
    return new ToolInvocation(
        id,
        runId,
        assistantEntryId,
        ordinal,
        toolCallId,
        toolName,
        toolVersion,
        targetType,
        environmentId,
        "{}",
        ToolInvocationStatus.QUEUED,
        PermissionAction.ALLOW,
        null,
        ToolSideEffect.READ_ONLY,
        NOW,
        null,
        null,
        null,
        null,
        null,
        NOW,
        null,
        null,
        NOW);
  }

  private static ToolDescriptor descriptor(String name, ToolExecutionMode mode) {
    return descriptor(name, mode, "1");
  }

  private static ToolDescriptor descriptor(String name, ToolExecutionMode mode, String version) {
    return new ToolDescriptor(
        name,
        version,
        name,
        null,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        mode,
        ToolSideEffect.IDEMPOTENT,
        Duration.ofSeconds(1));
  }
}
