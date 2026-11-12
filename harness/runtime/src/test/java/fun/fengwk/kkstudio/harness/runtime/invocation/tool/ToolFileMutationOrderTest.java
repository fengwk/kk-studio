package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolFileMutationOrder;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class ToolFileMutationOrderTest {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void normalizesPosixSeparatorsAndDotSegmentsButKeepsEnvironmentIdentity() {
    ToolInvocation dot = invocation("edit", ENVIRONMENT, "/a/./b");
    ToolInvocation parent = invocation("write", ENVIRONMENT, "/a/x/../b");
    ToolInvocation normalized = invocation("edit", ENVIRONMENT, "/a//b");
    EnvironmentId another = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

    assertEquals(ToolFileMutationOrder.key(dot), ToolFileMutationOrder.key(parent));
    assertEquals(ToolFileMutationOrder.key(dot), ToolFileMutationOrder.key(normalized));
    assertNotEquals(
        ToolFileMutationOrder.key(dot),
        ToolFileMutationOrder.key(invocation("edit", another, "/a/b")));
  }

  @Test
  void windowsDrivePathsIgnoreCaseAndUnifySeparators() {
    ToolInvocation left = invocation("edit", ENVIRONMENT, "C:\\Repo\\A\\..\\FILE.txt");
    ToolInvocation right = invocation("write", ENVIRONMENT, "c:/repo/file.TXT");

    assertEquals(ToolFileMutationOrder.key(left), ToolFileMutationOrder.key(right));
  }

  @Test
  void windowsUncPathsIgnoreCaseAndPosixPathsRemainCaseSensitive() {
    ToolInvocation uncBackslash =
        invocation("edit", ENVIRONMENT, "\\\\Server\\Share\\Dir\\..\\file");
    ToolInvocation uncSlash = invocation("write", ENVIRONMENT, "//server/share/file");
    ToolInvocation upperPosix = invocation("edit", ENVIRONMENT, "/a/File");
    ToolInvocation lowerPosix = invocation("edit", ENVIRONMENT, "/a/file");

    assertEquals(ToolFileMutationOrder.key(uncBackslash), ToolFileMutationOrder.key(uncSlash));
    assertNotEquals(ToolFileMutationOrder.key(upperPosix), ToolFileMutationOrder.key(lowerPosix));
  }

  @Test
  void malformedOrNonEnvironmentFileCallsHaveNoKey() {
    assertTrue(
        ToolFileMutationOrder.key(invocation("edit", ENVIRONMENT, "relative/file")).isEmpty());
    assertTrue(ToolFileMutationOrder.key(invocation("bash", ENVIRONMENT, "/a/b")).isEmpty());
    assertTrue(ToolFileMutationOrder.key(invocation("edit", null, "/a/b")).isEmpty());
    assertFalse(ToolFileMutationOrder.key(invocation("edit", ENVIRONMENT, "/a/b")).isEmpty());
  }

  private static ToolInvocation invocation(
      String toolName, EnvironmentId environmentId, String path) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            toolName,
            "test tool",
            toolName,
            new InputSchema("args", Map.of(), Set.of(), true),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolBinding binding =
        new ToolBinding(
            new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
            new ContributorBinding("builtin", toolName),
            environmentId == null ? EnvironmentSupport.NONE : EnvironmentSupport.ROUTED,
            environmentId,
            environmentId == null ? null : "test");
    return new ToolInvocation(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        new ToolCall("call", toolName, "{\"path\":\"" + path.replace("\\", "\\\\") + "\"}"),
        binding,
        ToolInvocationStatus.READY,
        0,
        null,
        null,
        null,
        NOW,
        NOW);
  }
}
