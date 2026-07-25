package fun.fengwk.kkstudio.harness.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

class ToolDomainContractsTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** ToolInvocation 快照保存单 bigint id、冻结 descriptor 与统一 Invocation 状态。 */
  @Test
  void validatesToolInvocationSnapshot() {
    ToolInvocation invocation =
        invocation(1L, 2L, 3L, 0, "call", "tool", "1", ToolExecutionLocation.PLATFORM, null);
    assertEquals(InvocationStatus.QUEUED, invocation.status());

    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(0L, 2L, 3L, 0, "call", "tool", "1", ToolExecutionLocation.PLATFORM, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 0L, 3L, 0, "call", "tool", "1", ToolExecutionLocation.PLATFORM, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 2L, 0L, 0, "call", "tool", "1", ToolExecutionLocation.PLATFORM, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(1L, 2L, 3L, -1, "call", "tool", "1", ToolExecutionLocation.PLATFORM, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(1L, 2L, 3L, 0, " ", "tool", "1", ToolExecutionLocation.PLATFORM, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                1L, 2L, 3L, 0, "call", "tool", "1", ToolExecutionLocation.PLATFORM, "env-9"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                1L, 2L, 3L, 0, "call", "tool", "1", ToolExecutionLocation.ENVIRONMENT, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                1L,
                2L,
                3L,
                0,
                "call",
                "tool",
                "1",
                ToolExecutionLocation.ENVIRONMENT,
                "e".repeat(129)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                1L, 2L, 3L, 0, "x".repeat(257), "tool", "1", ToolExecutionLocation.PLATFORM, null));
  }

  /** Binding 拥有 location；environmentName 只属于 ENVIRONMENT。 */
  @Test
  void validatesFrozenToolBinding() {
    ToolBinding platform = ToolBinding.of(descriptor("tool"));
    assertEquals(ToolExecutionLocation.PLATFORM, platform.location());
    assertEquals(null, platform.environmentName());
    ToolBinding environment = ToolBinding.of(descriptor("environment"), "env-9");
    assertEquals("env-9", environment.environmentName());
    assertEquals(ToolExecutionLocation.ENVIRONMENT, environment.location());

    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("tool"), ToolExecutionLocation.PLATFORM, "env-1"));
    assertThrows(
        IllegalArgumentException.class, () -> ToolBinding.of(descriptor("environment"), " "));
    assertThrows(
        IllegalArgumentException.class, () -> ToolBinding.of(descriptor("environment"), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolBinding.of(descriptor("environment"), "e".repeat(129)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolBinding(descriptor("environment"), ToolExecutionLocation.ENVIRONMENT, null));
    assertThrows(
        IllegalArgumentException.class, () -> ToolBinding.of(descriptor("tool", "v".repeat(129))));
  }

  private static ToolInvocation invocation(
      long id,
      long threadId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      String toolName,
      String toolVersion,
      ToolExecutionLocation location,
      String environmentName) {
    return new ToolInvocation(
        id,
        threadId,
        assistantEntryId,
        ordinal,
        toolCallId,
        descriptor(toolName, toolVersion),
        "{}",
        location,
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
        null);
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
