package fun.fengwk.kkstudio.harness.builtin.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
    assertFalse(descriptor.inputSchema().properties().containsKey("workdir"));
    assertFalse(descriptor.inputSchema().required().contains("workdir"));
    assertTrue(descriptor.description().contains("kkstudio:/resources/<blobId>"));
    assertTrue(
        descriptor.description().contains("kkstudio:/skills/<package>/<skill>/<relativePath>"));
    assertFalse(descriptor.description().contains("workdir"));
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

  /** 验证 execute 委托给注入的 executor，并把包装后的 listener 事件原样转发给原始 listener。 */
  @Test
  void executeDelegatesToInjectedExecutorAndForwardsEvents() {
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
            new ToolCall("c1", "read", "{\"path\":\"/srv/repo/file.txt\"}"),
            Duration.ZERO);
    ToolExecutionListener listener = mock(ToolExecutionListener.class);

    ToolExecutionHandle handle = tool.execute(request, listener);

    assertSame(mockHandle, handle);
    assertSame(request, capturedRequest.get());
    assertNotNull(capturedListener.get());
    IllegalStateException failure = new IllegalStateException("boom");
    capturedListener.get().onError(failure);
    verify(listener).onError(failure);
  }

  /** 验证 execute 校验 request 与 listener 非空。 */
  @Test
  void executeRequiresNonNullArguments() {
    ReadTool tool = new ReadTool((request, listener) -> null);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "read", "{\"path\":\"/srv/repo/file.txt\"}"),
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
            new ToolCall("c1", "read", "{\"path\":\"/srv/repo/file.txt\"}"),
            Duration.ZERO);
    ToolExecutionListener listener = mock(ToolExecutionListener.class);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> tool.execute(request, listener));
    assertSame(failure, thrown);
  }

  /** 验证 read 工具参数中携带已废弃的 workdir 时，在请求构造期抛出 IllegalArgumentException。 */
  @Test
  void executeRejectsWorkdirInArguments() {
    ReadTool tool = new ReadTool((request, listener) -> null);
    ToolCall callWithWorkdir =
        new ToolCall("c1", "read", "{\"path\":\"/srv/repo/file.txt\",\"workdir\":\"/srv/repo\"}");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ToolExecutionRequest(tool.descriptor(), callWithWorkdir, Duration.ZERO));
    assertTrue(error.getMessage().contains("workdir is not allowed"), error.getMessage());
  }

  /** 验证 read 工具参数接受 kkstudio: resources 与 skills 两种稳定 URI 格式并成功构造委托请求。 */
  @Test
  void executeAcceptsKkstudioResourceAndSkillUris() {
    AtomicReference<ToolExecutionRequest> capturedRequest = new AtomicReference<>();
    ToolExecutionHandle mockHandle = mock(ToolExecutionHandle.class);
    ReadTool tool =
        new ReadTool(
            (request, listener) -> {
              capturedRequest.set(request);
              return mockHandle;
            });

    String resourceUri = "kkstudio:/resources/1f2e3d4c-5b6a-4c8d-9e0f-1a2b3c4d5e6f";
    ToolExecutionRequest resourceRequest =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "read", "{\"path\":\"" + resourceUri + "\"}"),
            Duration.ZERO);
    assertSame(mockHandle, tool.execute(resourceRequest, mock(ToolExecutionListener.class)));
    assertEquals(
        resourceUri,
        capturedRequest.get().call().argumentsJson().contains(resourceUri) ? resourceUri : null);

    String skillUri = "kkstudio:/skills/review/checklist/SKILL.md";
    ToolExecutionRequest skillRequest =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c2", "read", "{\"path\":\"" + skillUri + "\"}"),
            Duration.ZERO);
    assertSame(mockHandle, tool.execute(skillRequest, mock(ToolExecutionListener.class)));
  }

  /**
   * read 的模型可见错误由 builtin wrapper 补足纠正指引：无绑定、空/非法 path、{@code kkstudio:} URI 声明未执行；仅本地路径且绑定
   * Environment 时才可能进入 capability 执行，不得用 NOT_EXECUTED 掩盖潜在执行。
   */
  @Test
  void executeWrapsErrorResultsWithCorrectiveGuidance() {
    AtomicReference<ToolExecutionListener> wrapped = new AtomicReference<>();
    ReadTool tool =
        new ReadTool(
            (request, listener) -> {
              wrapped.set(listener);
              return mock(ToolExecutionHandle.class);
            });

    String localPath = "{\"path\":\"/srv/repo/file.txt\"}";
    assertReadGuidance(tool, wrapped, null, localPath, "The tool was not executed.");
    assertReadGuidance(
        tool, wrapped, mock(BoundEnvironment.class), localPath, "cannot be confirmed");
    assertReadGuidance(
        tool,
        wrapped,
        mock(BoundEnvironment.class),
        "{\"path\":\"kkstudio:/resources/1f2e3d4c-5b6a-4c8d-9e0f-1a2b3c4d5e6f\"}",
        "The tool was not executed.");
    assertReadGuidance(tool, wrapped, null, "{\"path\":\" \"}", "The tool was not executed.");
  }

  /** 已带执行事实的 daemon 文案不得被 builtin wrapper 二次包装。 */
  @Test
  void executeDoesNotDoubleWrapAlreadyGuidedErrors() {
    AtomicReference<ToolExecutionListener> wrapped = new AtomicReference<>();
    ReadTool tool =
        new ReadTool(
            (request, listener) -> {
              wrapped.set(listener);
              return mock(ToolExecutionHandle.class);
            });
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), Instant.now(), mock(BranchView.class));
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "read", "{\"path\":\"/srv/repo/file.txt\"}"),
            Duration.ZERO,
            context);
    AtomicReference<ToolOutcome> outcome = new AtomicReference<>();

    tool.execute(request, capturingListener(outcome));
    String already =
        ToolErrorGuidance.message(
            "path does not exist: /srv/repo/file.txt", ExecutionFact.NOT_EXECUTED, "Review");
    wrapped.get().onComplete(ToolResult.error("c1", already));

    assertEquals(already, ((TextResultContent) outcome.get().result().contents().get(0)).text());
  }

  private static void assertReadGuidance(
      ReadTool tool,
      AtomicReference<ToolExecutionListener> wrapped,
      BoundEnvironment environment,
      String argumentsJson,
      String expectedExecutionFact) {
    BranchView branch = mock(BranchView.class);
    ToolExecutionContext context =
        environment == null
            ? new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), branch)
            : new ToolExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(), branch, environment);
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(), new ToolCall("c1", "read", argumentsJson), Duration.ZERO, context);
    AtomicReference<ToolOutcome> outcome = new AtomicReference<>();

    tool.execute(request, capturingListener(outcome));
    wrapped.get().onComplete(ToolResult.error("c1", "path must not be blank"));

    String text = ((TextResultContent) outcome.get().result().contents().get(0)).text();
    assertTrue(outcome.get().result().error(), text);
    assertTrue(text.contains("path must not be blank"), text);
    assertTrue(text.contains(expectedExecutionFact), text);
    assertTrue(text.contains("absolute local path"), text);
  }

  private static ToolExecutionListener capturingListener(AtomicReference<ToolOutcome> outcome) {
    return new ToolExecutionListener() {
      @Override
      public void onPartial(ToolResult partial) {}

      @Override
      public void onComplete(ToolOutcome value) {
        outcome.set(value);
      }

      @Override
      public void onError(Throwable error) {}
    };
  }
}
