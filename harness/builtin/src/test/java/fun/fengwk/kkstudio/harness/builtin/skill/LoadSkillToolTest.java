package fun.fengwk.kkstudio.harness.builtin.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 覆盖 selected-skill 解析、source Environment 匹配/不匹配、离线失败与取消场景。 */
class LoadSkillToolTest {

  private static final EnvironmentBinding PLATFORM =
      new EnvironmentBinding(new EnvironmentName("platform"), ".");
  private static final EnvironmentBinding LOCAL_DEV =
      new EnvironmentBinding(new EnvironmentName("local-dev"), ".");

  /** descriptor 的 name/version/renderer/side-effect/timeout 与单参数 schema 声明及 environment 要求。 */
  @Test
  void exposesCanonicalDescriptorContract() {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.empty(),
            (environment, skillName, timeout) -> new CompletableFuture<>(),
            Duration.ofSeconds(1));

    ToolDescriptor descriptor = tool.descriptor();
    assertEquals(LoadSkillTool.NAME, descriptor.name());
    assertEquals(LoadSkillTool.VERSION, descriptor.version());
    assertEquals(LoadSkillTool.NAME, descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    assertEquals(Duration.ofMinutes(1), descriptor.timeout());
    assertEquals(ToolRequirements.environment(), tool.requirements());

    ToolParamsSchema schema = descriptor.inputSchema();
    assertEquals(List.of("name"), schema.properties().keySet().stream().toList());
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("name"));
    assertEquals(Set.of("name"), schema.required());
    assertFalse(schema.additionalProperties());
  }

  /** 成功加载：当选中的 Skill 来源环境与当前上下文绑定的环境一致时，正常调用 loader 返回正文。 */
  @Test
  void loadsSelectedSkillBodyFromResolvedSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) -> {
          if ("dev".equals(skillName)) {
            return Optional.of(new SelectedSkill("dev", "Developer rules", PLATFORM));
          }
          return Optional.empty();
        };
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result =
        new SkillBodyLoader.SkillBodyLoadResult.Loaded("dev", "# Skill\n\nDo the thing.\n");
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult result = execute(tool, PLATFORM, "{\"name\":\"dev\"}");
    assertFalse(result.error());
    assertEquals("# Skill\n\nDo the thing.\n", ((TextToolContent) result.contents().get(0)).text());
    assertEquals(PLATFORM, loader.environment);
    assertEquals("dev", loader.skillName);
    assertEquals(Duration.ofSeconds(2), loader.timeout);
  }

  /** 环境不匹配校验：当 Skill 所属环境与上下文当前环境不同，工具安全拒绝执行。 */
  @Test
  void rejectsSkillSourceEnvironmentMismatch() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) ->
            Optional.of(new SelectedSkill("project", "Project skill", LOCAL_DEV));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult result = execute(tool, PLATFORM, "{\"name\":\"project\"}");
    assertTrue(result.error());
    assertTrue(text(result).contains("mismatch"), text(result));
    assertNull(loader.environment);
  }

  /** 加载超时在每次 execute 现读 supplier：构造后改值必须传到 SkillBodyLoader。 */
  @Test
  void usesLiveLoadTimeoutSupplierOnEachExecute() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) ->
            Optional.of(new SelectedSkill("dev", "Developer rules", PLATFORM));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result = new SkillBodyLoader.SkillBodyLoadResult.Loaded("dev", "# Skill\n");
    AtomicReference<Duration> timeout = new AtomicReference<>(Duration.ofSeconds(2));
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, timeout::get);

    execute(tool, PLATFORM, "{\"name\":\"dev\"}");
    assertEquals(Duration.ofSeconds(2), loader.timeout);

    timeout.set(Duration.ofSeconds(9));
    execute(tool, PLATFORM, "{\"name\":\"dev\"}");
    assertEquals(Duration.ofSeconds(9), loader.timeout);
  }

  /** 未选中技能或环境离线时返回明确的错误结果。 */
  @Test
  void rejectsUnselectedSkillAndOfflineSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) -> {
          if ("dev".equals(skillName)) {
            return Optional.of(new SelectedSkill("dev", "Developer rules", PLATFORM));
          }
          return Optional.empty();
        };
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result =
        new SkillBodyLoader.SkillBodyLoadResult.Failed(
            "dev", "platform is offline; dev is unavailable");
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult unselected = execute(tool, PLATFORM, "{\"name\":\"missing\"}");
    assertTrue(unselected.error());
    assertTrue(text(unselected).contains("unknown or unselected skill"));

    ToolResult offline = execute(tool, PLATFORM, "{\"name\":\"dev\"}");
    assertTrue(offline.error());
    assertTrue(text(offline).contains("offline"));
  }

  /** Skill 声明没有 Environment 正文来源时拒绝加载。 */
  @Test
  void rejectsSelectedSkillWithoutAnEnvironmentBody() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId, skillName) ->
            Optional.of(new SelectedSkill("platform-only", "Already provided", null));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult result = execute(tool, PLATFORM, "{\"name\":\"platform-only\"}");
    assertTrue(result.error());
    assertTrue(text(result).contains("has no Environment body"));
    assertNull(loader.environment);
  }

  /** 缺少执行上下文或环境绑定时安全处理。 */
  @Test
  void requiresDurableContextAndStrictName() throws Exception {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.empty(),
            (env, name, timeout) -> CompletableFuture.completedFuture(null),
            Duration.ofSeconds(1));
    AtomicReference<ToolResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "load_skill", "{\"name\":\"dev\"}"),
            Duration.ofSeconds(1),
            null),
        completeListener(result, latch));
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertTrue(result.get().error());
    assertTrue(text(result.get()).contains("durable execution context"));

    ToolResult blank = execute(tool, PLATFORM, "{\"name\":\"  \"}");
    assertTrue(blank.error());
  }

  /** cancel 必须中断 pending loader，并且重复 cancel 仍只产生一个 terminal ToolResult。 */
  @Test
  void cancellationCancelsThePendingLoadAndCompletesExactlyOnce() throws Exception {
    CompletableFuture<SkillBodyLoader.SkillBodyLoadResult> pending = new CompletableFuture<>();
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) ->
                Optional.of(new SelectedSkill("dev", "Developer rules", PLATFORM)),
            (environment, skillName, timeout) -> pending,
            Duration.ofSeconds(2));
    AtomicReference<ToolResult> result = new AtomicReference<>();
    AtomicInteger terminalCount = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(1);

    BoundEnvironment boundEnv = mock(BoundEnvironment.class);
    when(boundEnv.binding()).thenReturn(PLATFORM);
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            mock(BranchView.class),
            Optional.of(boundEnv));

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
    assertTrue(pending.isCancelled());
    assertEquals(1, terminalCount.get());
    assertTrue(result.get().error());
    assertTrue(
        text(result.get()).contains("cancelled") || text(result.get()).contains("Cancelled"));
  }

  private static ToolResult execute(
      LoadSkillTool tool, EnvironmentBinding environment, String argumentsJson) throws Exception {
    AtomicReference<ToolResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    BoundEnvironment boundEnv = mock(BoundEnvironment.class);
    when(boundEnv.binding()).thenReturn(environment);
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            mock(BranchView.class),
            Optional.of(boundEnv));

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
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static final class RecordingBodyLoader implements SkillBodyLoader {
    private EnvironmentBinding environment;
    private String skillName;
    private Duration timeout;
    private SkillBodyLoadResult result;

    @Override
    public CompletableFuture<SkillBodyLoadResult> load(
        EnvironmentBinding binding, String skillName, Duration timeout) {
      this.environment = binding;
      this.skillName = skillName;
      this.timeout = timeout;
      return CompletableFuture.completedFuture(result);
    }
  }
}
