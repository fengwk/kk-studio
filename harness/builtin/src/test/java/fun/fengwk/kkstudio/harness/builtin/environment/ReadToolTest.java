package fun.fengwk.kkstudio.harness.builtin.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一 {@link ReadTool} 的契约与行为测试。
 *
 * <p>验证 descriptor 复用 fs.read capability、requirements 声明 OPTIONAL 环境支持、超时解析三条分支、 历史动作渲染能力，以及
 * execute 对注入 ReadToolExecutor 的委托语义。
 */
class ReadToolTest {

  private static final EnvironmentCapabilityDescriptor FS_READ =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);

  /** 构造器要求 executor 必须非空。 */
  @Test
  void constructorRequiresNonNullExecutor() {
    assertThrows(NullPointerException.class, () -> new ReadTool(null));
  }

  /** 验证 descriptor 完全复用 fs.read capability 的 schema 与默认超时，且 name/rendererKey 固定为 read。 */
  @Test
  void descriptorMatchesFsReadCapabilitySpecification() {
    ReadTool tool = new ReadTool((request, listener) -> null);
    ToolDescriptor descriptor = tool.descriptor();

    assertEquals(ReadTool.NAME, descriptor.name());
    assertEquals("read", descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    assertFalse(descriptor.description().isBlank());
    assertEquals(FS_READ.inputSchema(), descriptor.inputSchema());
    assertEquals(FS_READ.defaultTimeout(), descriptor.defaultTimeout());
  }

  /** 验证 requirements 声明为 optionalEnvironment（无绑定时由实现方处理，有绑定时可使用 BoundEnvironment）。 */
  @Test
  void requirementsDeclareOptionalEnvironment() {
    ReadTool tool = new ReadTool((request, listener) -> null);
    assertEquals(ToolRequirements.optionalEnvironment(), tool.requirements());
    assertEquals(EnvironmentSupport.OPTIONAL, tool.requirements().environmentSupport());
  }

  /** 验证超时解析：缺省使用 capability 默认超时、显式正数严格使用覆盖值、非正数抛出 IllegalArgumentException。 */
  @Test
  void resolveTimeoutFollowsCapabilityTimeoutContract() {
    ReadTool tool = new ReadTool((request, listener) -> null);

    // 缺省 timeout_seconds
    ToolCall defaultCall = new ToolCall("c1", "read", "{\"path\":\"file.txt\"}");
    assertEquals(FS_READ.defaultTimeout(), tool.resolveTimeout(defaultCall));

    // 显式正数 timeout_seconds
    ToolCall explicitCall =
        new ToolCall("c2", "read", "{\"path\":\"file.txt\",\"timeout_seconds\":30}");
    assertEquals(Duration.ofSeconds(30), tool.resolveTimeout(explicitCall));

    // 非正数 timeout_seconds
    ToolCall zeroCall = new ToolCall("c3", "read", "{\"path\":\"file.txt\",\"timeout_seconds\":0}");
    assertThrows(IllegalArgumentException.class, () -> tool.resolveTimeout(zeroCall));

    ToolCall negativeCall =
        new ToolCall("c4", "read", "{\"path\":\"file.txt\",\"timeout_seconds\":-10}");
    assertThrows(IllegalArgumentException.class, () -> tool.resolveTimeout(negativeCall));
  }

  /** 验证 historyRenderer 存在且提供 fs.read 动作渲染语义。 */
  @Test
  void historyRendererMatchesFsReadAction() {
    ReadTool tool = new ReadTool((request, listener) -> null);
    Optional<ToolHistoryRenderer> renderer = tool.historyRenderer();

    assertTrue(renderer.isPresent());
    ToolHistoryRenderRequest renderRequest =
        new ToolHistoryRenderRequest(
            new ToolCall("c1", "read", "{\"path\":\"src/App.java\"}"), null);
    assertEquals(Optional.of("read src/App.java"), renderer.get().render(renderRequest));
  }

  /** 验证 execute 纯粹委托给注入的 executor，原样透传 request 与 listener，并原样返回 handle。 */
  @Test
  void executeDelegatesDirectlyToInjectedExecutor() {
    AtomicReference<ToolExecutionRequest> capturedRequest = new AtomicReference<>();
    AtomicReference<ToolExecutionListener> capturedListener = new AtomicReference<>();
    ToolExecutionHandle mockHandle = mock(ToolExecutionHandle.class);

    ReadToolExecutor fakeExecutor =
        (request, listener) -> {
          capturedRequest.set(request);
          capturedListener.set(listener);
          return mockHandle;
        };

    ReadTool tool = new ReadTool(fakeExecutor);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "read", "{\"workdir\":\"/srv/repo\",\"path\":\"file.txt\"}"),
            Duration.ZERO);
    ToolExecutionListener listener = mock(ToolExecutionListener.class);

    ToolExecutionHandle handle = tool.execute(request, listener);

    assertSame(mockHandle, handle);
    assertSame(request, capturedRequest.get());
    assertSame(listener, capturedListener.get());
  }

  /** 验证 execute 校验 request 与 listener 非空。 */
  @Test
  void executeRequiresNonNullArguments() {
    ReadTool tool = new ReadTool((request, listener) -> null);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "read", "{\"workdir\":\"/srv/repo\",\"path\":\"file.txt\"}"),
            Duration.ZERO);
    ToolExecutionListener listener = mock(ToolExecutionListener.class);

    assertThrows(NullPointerException.class, () -> tool.execute(null, listener));
    assertThrows(NullPointerException.class, () -> tool.execute(request, null));
  }

  /** 验证 executor 抛出异常时保持委托语义，原样向外抛出而不是自行吞掉。 */
  @Test
  void executePropagatesExceptionsFromExecutor() {
    IllegalStateException failure = new IllegalStateException("executor failed");
    ReadToolExecutor failingExecutor =
        (request, listener) -> {
          throw failure;
        };

    ReadTool tool = new ReadTool(failingExecutor);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "read", "{\"workdir\":\"/srv/repo\",\"path\":\"file.txt\"}"),
            Duration.ZERO);
    ToolExecutionListener listener = mock(ToolExecutionListener.class);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> tool.execute(request, listener));
    assertSame(failure, thrown);
  }
}
