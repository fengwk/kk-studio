package fun.fengwk.kkstudio.harness.contributor.builtin.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.spi.VfsGateway;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link FileTool} 的逐调用路由契约。
 *
 * <p>验证三类执行位置：{@code vfs:} 路径只走 {@link VfsGateway}、绝对路径只走绑定的 {@link BoundEnvironment}、无环境且非 {@code
 * vfs:} 路径在派发前返回「未执行」并提示可写的会话目录。
 */
class FileToolTest {

  private static final EnvironmentCapabilityDescriptor FS_READ =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);

  private static final UUID ROOT_SESSION_ID =
      UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  void descriptorReusesCapabilitySchemaAndDeclaresRoutedEnvironment() {
    FileTool tool = fileTool(FileTool.Operation.READ, EnvironmentCapabilityIds.FS_READ);
    ToolDescriptor descriptor = tool.descriptor();

    assertEquals("read", descriptor.name());
    assertEquals("read", descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    assertEquals(FS_READ.inputSchema(), descriptor.inputSchema());
    assertEquals(FS_READ.defaultTimeout(), descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());

    ToolRequirements requirements = tool.requirements();
    assertEquals(EnvironmentSupport.ROUTED, requirements.environmentSupport());
    assertTrue(requirements.stateAccesses().isEmpty());
  }

  @Test
  void constructorRequiresGateway() {
    assertThrows(
        NullPointerException.class,
        () ->
            new FileTool(
                "read",
                FileTool.Operation.READ,
                "read.md",
                EnvironmentCapabilityIds.FS_READ,
                ToolSideEffect.READ_ONLY,
                null));
  }

  /** 五个工具各自把同样的请求转发到对应的 VFS 操作，并原样返回结果。 */
  @Test
  void vfsPathsDispatchToTheMatchingGatewayOperation() throws Exception {
    assertDispatch(FileTool.Operation.READ, EnvironmentCapabilityIds.FS_READ, "read");
    assertDispatch(FileTool.Operation.WRITE, EnvironmentCapabilityIds.FS_WRITE, "write");
    assertDispatch(FileTool.Operation.EDIT, EnvironmentCapabilityIds.FS_EDIT, "edit");
    assertDispatch(FileTool.Operation.GREP, EnvironmentCapabilityIds.FS_GREP, "grep");
    assertDispatch(FileTool.Operation.FIND, EnvironmentCapabilityIds.FS_FIND, "find");
  }

  private static void assertDispatch(
      FileTool.Operation operation, EnvironmentCapabilityId capabilityId, String toolName)
      throws Exception {
    VfsGateway gateway = mock(VfsGateway.class);
    ToolResult expected =
        new ToolResult("c1", List.of(new TextResultContent("vfs result")), false, "{}");
    FileTool tool =
        new FileTool(
            toolName, operation, toolName + ".md", capabilityId, ToolSideEffect.READ_ONLY, gateway);
    ToolExecutionRequest request = request(tool, argumentsFor(operation), null);
    switch (operation) {
      case READ -> when(gateway.read(same(request), any())).thenReturn(expected);
      case WRITE -> when(gateway.write(same(request), any())).thenReturn(expected);
      case EDIT -> when(gateway.edit(same(request), any())).thenReturn(expected);
      case FIND -> when(gateway.find(same(request), any())).thenReturn(expected);
      case GREP -> when(gateway.grep(same(request), any())).thenReturn(expected);
    }

    assertSame(expected, tool.execute(request, partial -> {}).result());
  }

  /** 绝对路径在绑定环境上执行同名 capability，不触碰 VFS。 */
  @Test
  void absolutePathsExecuteOnTheBoundEnvironmentOnly() throws Exception {
    VfsGateway gateway = mock(VfsGateway.class);
    BoundEnvironment environment = mock(BoundEnvironment.class);
    ToolResult expected =
        new ToolResult("c1", List.of(new TextResultContent("env result")), false, "{}");
    FileTool tool =
        new FileTool(
            "read",
            FileTool.Operation.READ,
            "read.md",
            EnvironmentCapabilityIds.FS_READ,
            ToolSideEffect.READ_ONLY,
            gateway);
    ToolExecutionRequest request = request(tool, "{\"path\":\"/srv/repo/a.txt\"}", environment);
    when(environment.execute(same(FS_READ), same(request), any())).thenReturn(expected);

    assertSame(expected, tool.execute(request, partial -> {}).result());
    verify(environment).execute(same(FS_READ), same(request), any());
    verify(gateway, never()).read(any(), any());
  }

  /** 无环境时非 vfs: 路径不派发：声明未执行，并给出 vfs:/sessions/<rootSessionId>/ 与选择环境两条路径。 */
  @Test
  void absolutePathsWithoutEnvironmentAreNotExecuted() throws Exception {
    VfsGateway gateway = mock(VfsGateway.class);
    BoundEnvironment environment = mock(BoundEnvironment.class);
    FileTool tool =
        new FileTool(
            "write",
            FileTool.Operation.WRITE,
            "write.md",
            EnvironmentCapabilityIds.FS_WRITE,
            ToolSideEffect.IDEMPOTENT,
            gateway);
    ToolExecutionRequest request =
        request(tool, "{\"path\":\"/srv/repo/a.txt\",\"content\":\"x\"}", null);

    ToolResult result = tool.execute(request, partial -> {}).result();
    assertTrue(result.error());
    String message = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(message.contains("No Environment is bound"), message);
    assertTrue(message.contains("The tool was not executed."), message);
    assertTrue(message.contains("vfs:/sessions/" + ROOT_SESSION_ID + "/"), message);
    verify(gateway, never()).write(any(), any());
    verify(environment, never()).execute(any(), any(), any());
  }

  /** 环境执行失败的文案若未带执行事实，则补足「结果无法确认」；已带事实的文案不重复包装。 */
  @Test
  void environmentErrorsWithoutGuidanceAreWrappedAsUncertain() throws Exception {
    VfsGateway gateway = mock(VfsGateway.class);
    BoundEnvironment environment = mock(BoundEnvironment.class);
    FileTool tool =
        new FileTool(
            "read",
            FileTool.Operation.READ,
            "read.md",
            EnvironmentCapabilityIds.FS_READ,
            ToolSideEffect.READ_ONLY,
            gateway);
    ToolExecutionRequest request = request(tool, "{\"path\":\"/srv/repo/a.txt\"}", environment);
    when(environment.execute(any(), any(), any()))
        .thenReturn(ToolResult.error("c1", "path missing"));

    String message =
        ((TextResultContent) tool.execute(request, partial -> {}).result().contents().get(0))
            .text();
    assertTrue(message.contains("path missing"), message);
    assertTrue(message.contains("cannot be confirmed"), message);
  }

  @Test
  void executeRequiresNonNullArguments() {
    FileTool tool = fileTool(FileTool.Operation.READ, EnvironmentCapabilityIds.FS_READ);
    ToolExecutionRequest request = request(tool, "{\"path\":\"/srv/a.txt\"}", null);
    assertThrows(NullPointerException.class, () -> tool.execute(null, partial -> {}));
    assertThrows(NullPointerException.class, () -> tool.execute(request, null));
  }

  /** 与 capability schema 一致的最小合法参数。 */
  private static String argumentsFor(FileTool.Operation operation) {
    String path = "vfs:/sessions/" + ROOT_SESSION_ID + "/notes.txt";
    return switch (operation) {
      case READ -> "{\"path\":\"" + path + "\"}";
      case WRITE -> "{\"path\":\"" + path + "\",\"content\":\"x\"}";
      case EDIT -> "{\"path\":\"" + path + "\",\"old_string\":\"a\",\"new_string\":\"b\"}";
      case FIND -> "{\"path\":\"vfs:/sessions/" + ROOT_SESSION_ID + "\",\"pattern\":\"**/*.txt\"}";
      case GREP -> "{\"path\":\"vfs:/sessions/" + ROOT_SESSION_ID + "\",\"pattern\":\"needle\"}";
    };
  }

  private static FileTool fileTool(
      FileTool.Operation operation, EnvironmentCapabilityId capabilityId) {
    return new FileTool(
        operation.name().toLowerCase(),
        operation,
        operation.name().toLowerCase() + ".md",
        capabilityId,
        ToolSideEffect.READ_ONLY,
        mock(VfsGateway.class));
  }

  private static ToolExecutionRequest request(
      FileTool tool, String argumentsJson, BoundEnvironment environment) {
    BranchView branch = mock(BranchView.class);
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            ROOT_SESSION_ID,
            UUID.randomUUID(),
            Instant.now(),
            TurnMediaCapabilities.NONE,
            branch,
            Optional.ofNullable(environment));
    return new ToolExecutionRequest(
        tool.descriptor(),
        new ToolCall("c1", tool.descriptor().name(), argumentsJson),
        Duration.ZERO,
        context);
  }
}
