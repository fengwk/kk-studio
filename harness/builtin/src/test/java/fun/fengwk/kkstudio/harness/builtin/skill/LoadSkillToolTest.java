package fun.fengwk.kkstudio.harness.builtin.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 覆盖 selected-skill 解析、source Environment 匹配/不匹配、离线失败、超时与取消场景。 */
class LoadSkillToolTest {

  private static final EnvironmentId PLATFORM =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId LOCAL_DEV =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final UUID PLATFORM_SOURCE_ID =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID LOCAL_SOURCE_ID =
      UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String PLATFORM_REVISION =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String LOCAL_REVISION =
      "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

  /** 冻结的 dev skill：平台来源 + 平台正文目录 + 固定 revision。 */
  private static SelectedSkill platformDevSkill() {
    return new SelectedSkill(
        PLATFORM,
        PLATFORM_SOURCE_ID,
        "dev",
        "Developer rules",
        "/host/skills/dev",
        PLATFORM_REVISION);
  }

  /** 冻结的 project skill：本地来源，用于验证来源环境必须匹配。 */
  private static SelectedSkill localProjectSkill() {
    return new SelectedSkill(
        LOCAL_DEV,
        LOCAL_SOURCE_ID,
        "project",
        "Project skill",
        "/host/skills/project",
        LOCAL_REVISION);
  }

  /** descriptor 的 name/renderer/side-effect/timeout 与单参数 schema 声明及 environment 要求。 */
  @Test
  void exposesCanonicalDescriptorContract() {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.empty(), Duration.ofSeconds(1));

    ToolDescriptor descriptor = tool.descriptor();
    assertEquals(LoadSkillTool.NAME, descriptor.name());
    assertEquals(LoadSkillTool.NAME, descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    assertEquals(Duration.ofMinutes(1), descriptor.timeout());
    assertEquals(ToolRequirements.environment(), tool.requirements());

    InputSchema schema = descriptor.inputSchema();
    assertEquals(List.of("name"), schema.properties().keySet().stream().toList());
    assertInstanceOf(StringSchema.class, schema.properties().get("name"));
    assertEquals(Set.of("name"), schema.required());
    assertFalse(schema.additionalProperties());
  }

  /** 成功加载：当选中的 Skill 来源环境与当前上下文绑定的环境一致时，向 BoundEnvironment 委托并透传 skill 名称参数与结果。 */
  @Test
  void loadsSelectedSkillBodyFromResolvedSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) -> {
          if ("dev".equals(skillName)) {
            return Optional.of(platformDevSkill());
          }
          return Optional.empty();
        };
    RecordingBoundEnvironment env = new RecordingBoundEnvironment(PLATFORM);
    env.result =
        new ToolResult(
            "c1", List.of(new TextResultContent("# Skill\n\nDo the thing.\n")), false, "{}");
    LoadSkillTool tool = new LoadSkillTool(lookup, Duration.ofSeconds(2));

    ToolResult result = execute(tool, env, "{\"name\":\"dev\"}");
    assertFalse(result.error());
    assertEquals(
        "# Skill\n\nDo the thing.\n", ((TextResultContent) result.contents().get(0)).text());
    assertEquals(
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_LOAD), env.capability);
    assertNotNull(env.request);
    // 转发给 skill.load 的必须是冻结的全部身份字段：按名称回退到同名新版本是被禁止的。
    assertEquals(
        "{\"sourceId\":\""
            + PLATFORM_SOURCE_ID
            + "\",\"name\":\"dev\",\"revision\":\""
            + PLATFORM_REVISION
            + "\"}",
        env.request.call().argumentsJson());
    assertEquals(Duration.ofSeconds(2), env.timeout);
  }

  /** 环境不匹配校验：当 Skill 所属环境与上下文当前环境不同，工具安全拒绝执行且不调用 BoundEnvironment。 */
  @Test
  void rejectsSkillSourceEnvironmentMismatch() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) -> Optional.of(localProjectSkill());
    RecordingBoundEnvironment env = new RecordingBoundEnvironment(PLATFORM);
    LoadSkillTool tool = new LoadSkillTool(lookup, Duration.ofSeconds(2));

    ToolResult result = execute(tool, env, "{\"name\":\"project\"}");
    assertTrue(result.error());
    assertTrue(text(result).contains("mismatch"), text(result));
    assertNull(env.request);
  }

  /** 加载超时在每次 execute 现读 supplier：构造后改值必须传到 BoundEnvironment 请求。 */
  @Test
  void usesLiveLoadTimeoutSupplierOnEachExecute() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) -> Optional.of(platformDevSkill());
    RecordingBoundEnvironment env = new RecordingBoundEnvironment(PLATFORM);
    env.result = new ToolResult("c1", List.of(new TextResultContent("# Skill\n")), false, "{}");
    AtomicReference<Duration> timeout = new AtomicReference<>(Duration.ofSeconds(2));
    LoadSkillTool tool = new LoadSkillTool(lookup, timeout::get);

    execute(tool, env, "{\"name\":\"dev\"}");
    assertEquals(Duration.ofSeconds(2), env.timeout);

    timeout.set(Duration.ofSeconds(9));
    execute(tool, env, "{\"name\":\"dev\"}");
    assertEquals(Duration.ofSeconds(9), env.timeout);
  }

  /** 未选中技能或环境离线时返回明确的错误结果。 */
  @Test
  void rejectsUnselectedSkillAndOfflineSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) -> {
          if ("dev".equals(skillName)) {
            return Optional.of(platformDevSkill());
          }
          return Optional.empty();
        };
    RecordingBoundEnvironment env = new RecordingBoundEnvironment(PLATFORM);
    env.result =
        new ToolResult(
            "c1",
            List.of(new TextResultContent("platform is offline; dev is unavailable")),
            true,
            "{}");
    LoadSkillTool tool = new LoadSkillTool(lookup, Duration.ofSeconds(2));

    ToolResult unselected = execute(tool, env, "{\"name\":\"missing\"}");
    assertTrue(unselected.error());
    assertTrue(text(unselected).contains("unknown or unselected skill"));
    assertNull(env.request);

    ToolResult offline = execute(tool, env, "{\"name\":\"dev\"}");
    assertTrue(offline.error());
    assertTrue(text(offline).contains("offline"));
    assertNotNull(env.request);
  }

  /** SelectedSkill 的冻结身份是必填事实：缺少来源环境、来源 ID 或 revision 在冻结时即失败。 */
  @Test
  void selectedSkillRequiresCompleteFrozenIdentity() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SelectedSkill(
                null, PLATFORM_SOURCE_ID, "dev", "Developer rules", "/s", PLATFORM_REVISION));
    assertThrows(
        NullPointerException.class,
        () -> new SelectedSkill(PLATFORM, null, "dev", "Developer rules", "/s", PLATFORM_REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectedSkill(PLATFORM, PLATFORM_SOURCE_ID, "dev", "Developer rules", "/s", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectedSkill(
                PLATFORM, PLATFORM_SOURCE_ID, "dev", "Developer rules", null, PLATFORM_REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectedSkill(PLATFORM, PLATFORM_SOURCE_ID, "dev", null, "/s", PLATFORM_REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectedSkill(
                PLATFORM,
                PLATFORM_SOURCE_ID,
                "bad\nname",
                "Developer rules",
                "/s",
                PLATFORM_REVISION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectedSkill(
                PLATFORM,
                PLATFORM_SOURCE_ID,
                "dev",
                "Developer rules",
                "relative",
                PLATFORM_REVISION));
  }

  /** 缺少执行上下文或环境绑定时安全处理，且要求合法的 skill 名称。 */
  @Test
  void requiresDurableContextAndStrictName() throws Exception {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.empty(), Duration.ofSeconds(1));
    AtomicReference<ToolResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", LoadSkillTool.NAME, "{\"name\":\"dev\"}"),
            Duration.ofSeconds(1),
            null),
        completeListener(result, latch));
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertTrue(result.get().error());
    assertTrue(text(result.get()).contains("durable execution context"));

    AtomicReference<ToolResult> noEnvResult = new AtomicReference<>();
    CountDownLatch noEnvLatch = new CountDownLatch(1);
    ToolExecutionContext contextWithoutEnv =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            mock(BranchView.class),
            Optional.empty());
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c2", LoadSkillTool.NAME, "{\"name\":\"dev\"}"),
            Duration.ofSeconds(1),
            contextWithoutEnv),
        completeListener(noEnvResult, noEnvLatch));
    assertTrue(noEnvLatch.await(2, TimeUnit.SECONDS));
    assertTrue(noEnvResult.get().error());
    assertTrue(text(noEnvResult.get()).contains("No environment bound in execution context"));

    ToolResult blank = execute(tool, PLATFORM, "{\"name\":\"  \"}");
    assertTrue(blank.error());
    assertTrue(text(blank).contains("must not be blank"));
  }

  /** 取消挂起的加载执行时，委托句柄标记已取消且监听器仅收到一次终态结果。 */
  @Test
  void cancellationCancelsThePendingLoadAndCompletesExactlyOnce() throws Exception {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.of(platformDevSkill()),
            Duration.ofSeconds(2));
    AtomicReference<ToolResult> result = new AtomicReference<>();
    AtomicInteger terminalCount = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(1);

    RecordingBoundEnvironment env = new RecordingBoundEnvironment(PLATFORM);
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            mock(BranchView.class),
            Optional.of(env));

    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall("c1", LoadSkillTool.NAME, "{\"name\":\"dev\"}"),
                Duration.ofSeconds(2),
                context),
            new ToolExecutionListener() {
              @Override
              public void onPartial(ToolResult partial) {}

              @Override
              public void onComplete(ToolOutcome outcome) {
                terminalCount.incrementAndGet();
                result.set(outcome.result());
                latch.countDown();
              }

              @Override
              public void onError(Throwable error) {}
            });

    handle.cancel();
    handle.cancel();
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertTrue(handle.isCancelled());
    assertEquals(1, terminalCount.get());
    assertTrue(result.get().error());
    assertTrue(
        text(result.get()).contains("cancelled") || text(result.get()).contains("Cancelled"));
  }

  private static ToolResult execute(
      LoadSkillTool tool, BoundEnvironment boundEnvironment, String argumentsJson)
      throws Exception {
    AtomicReference<ToolResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            mock(BranchView.class),
            Optional.of(boundEnvironment));

    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", LoadSkillTool.NAME, argumentsJson),
            Duration.ofSeconds(2),
            context),
        completeListener(result, latch));
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    return result.get();
  }

  private static ToolResult execute(
      LoadSkillTool tool, EnvironmentId environment, String argumentsJson) throws Exception {
    return execute(tool, new RecordingBoundEnvironment(environment), argumentsJson);
  }

  private static ToolExecutionListener completeListener(
      AtomicReference<ToolResult> target, CountDownLatch latch) {
    return new ToolExecutionListener() {
      @Override
      public void onPartial(ToolResult partial) {}

      @Override
      public void onComplete(ToolOutcome outcome) {
        target.set(outcome.result());
        latch.countDown();
      }

      @Override
      public void onError(Throwable error) {}
    };
  }

  private static String text(ToolResult result) {
    return ((TextResultContent) result.contents().get(0)).text();
  }

  private static final class RecordingBoundEnvironment implements BoundEnvironment {
    private final EnvironmentId environmentId;
    private EnvironmentCapabilityDescriptor capability;
    private ToolExecutionRequest request;
    private Duration timeout;
    private ToolResult result;
    private ControllableHandle handle;

    RecordingBoundEnvironment(EnvironmentId environmentId) {
      this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    }

    @Override
    public EnvironmentId environmentId() {
      return environmentId;
    }

    @Override
    public ToolExecutionHandle execute(
        EnvironmentCapabilityDescriptor capability,
        ToolExecutionRequest request,
        ToolExecutionListener listener) {
      this.capability = capability;
      this.request = request;
      this.timeout = request.timeout();
      this.handle = new ControllableHandle(request.call().id(), listener);
      if (result != null) {
        ToolResult callResult =
            new ToolResult(
                request.call().id(), result.contents(), result.error(), result.detailsJson());
        this.handle.complete(callResult);
      }
      return handle;
    }
  }

  private static final class ControllableHandle implements ToolExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final String callId;
    private final ToolExecutionListener listener;

    ControllableHandle(String callId, ToolExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    void complete(ToolResult result) {
      if (completed.compareAndSet(false, true) && listener != null) {
        listener.onComplete(result);
      }
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        complete(
            new ToolResult(
                callId != null ? callId : "c1",
                List.of(new TextResultContent("Execution cancelled")),
                true,
                "{}"));
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }
}
