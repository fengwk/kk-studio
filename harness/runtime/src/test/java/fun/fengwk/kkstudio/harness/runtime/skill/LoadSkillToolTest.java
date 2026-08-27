package fun.fengwk.kkstudio.harness.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 覆盖 selected-skill 解析、source Environment 优先级与离线失败场景。 */
class LoadSkillToolTest {

  private static final EnvironmentBinding PLATFORM = EnvironmentBindings.binding("platform");
  private static final EnvironmentBinding LOCAL_DEV = EnvironmentBindings.binding("local-dev");

  /** descriptor 的 name/version/renderer/side-effect/timeout 与单参数 schema 是稳定模型契约。 */
  @Test
  void exposesCanonicalDescriptorContract() {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId) -> List.of(),
            (environment, skillName, timeout) -> new CompletableFuture<>(),
            Duration.ofSeconds(1));

    ToolDescriptor descriptor = tool.descriptor();
    assertEquals(LoadSkillTool.NAME, descriptor.name());
    assertEquals(LoadSkillTool.VERSION, descriptor.version());
    assertEquals(LoadSkillTool.NAME, descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    assertEquals(Duration.ofMinutes(1), descriptor.timeout());
    ToolParamsSchema schema = descriptor.inputSchema();
    assertEquals(List.of("name"), schema.properties().keySet().stream().toList());
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("name"));
    assertEquals(Set.of("name"), schema.required());
    assertFalse(schema.additionalProperties());
  }

  @Test
  void loadsSelectedSkillBodyFromResolvedSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId) ->
            List.of(
                new SkillBinding("dev", "Developer rules", PLATFORM),
                new SkillBinding("project", "Project skill", LOCAL_DEV));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result =
        new SkillBodyLoader.SkillBodyLoadResult.Loaded("dev", "# Skill\n\nDo the thing.\n");
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult result =
        execute(
            tool, UUID.fromString("00000000-0000-0000-0000-00000000002a"), "{\"name\":\"dev\"}");
    assertFalse(result.error());
    assertEquals("# Skill\n\nDo the thing.\n", ((TextToolContent) result.contents().get(0)).text());
    assertEquals(PLATFORM, loader.environment);
    assertEquals("dev", loader.skillName);
    assertEquals(Duration.ofSeconds(2), loader.timeout);
  }

  /** 加载超时在每次 execute 现读 supplier：构造后改值必须传到 SkillBodyLoader。 */
  @Test
  void usesLiveLoadTimeoutSupplierOnEachExecute() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId) -> List.of(new SkillBinding("dev", "Developer rules", PLATFORM));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result = new SkillBodyLoader.SkillBodyLoadResult.Loaded("dev", "# Skill\n");
    AtomicReference<Duration> timeout = new AtomicReference<>(Duration.ofSeconds(2));
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, timeout::get);

    execute(tool, UUID.fromString("00000000-0000-0000-0000-00000000002a"), "{\"name\":\"dev\"}");
    assertEquals(Duration.ofSeconds(2), loader.timeout);

    timeout.set(Duration.ofSeconds(9));
    execute(tool, UUID.fromString("00000000-0000-0000-0000-00000000002a"), "{\"name\":\"dev\"}");
    assertEquals(Duration.ofSeconds(9), loader.timeout);
  }

  @Test
  void rejectsUnselectedSkillAndOfflineSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId) -> List.of(new SkillBinding("dev", "Developer rules", PLATFORM));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result =
        new SkillBodyLoader.SkillBodyLoadResult.Failed(
            "dev", "platform is offline; dev is unavailable");
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult unselected =
        execute(
            tool,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "{\"name\":\"missing\"}");
    assertTrue(unselected.error());
    assertTrue(text(unselected).contains("unknown or unselected skill"));

    ToolResult offline =
        execute(
            tool, UUID.fromString("00000000-0000-0000-0000-000000000001"), "{\"name\":\"dev\"}");
    assertTrue(offline.error());
    assertTrue(text(offline).contains("offline"));
  }

  @Test
  void rejectsSelectedSkillWithoutAnEnvironmentBody() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId) ->
            List.of(new SkillBinding("platform-only", "Already provided by the platform", null));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult result =
        execute(
            tool,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "{\"name\":\"platform-only\"}");

    assertTrue(result.error());
    assertTrue(text(result).contains("has no Environment body"));
    assertNull(loader.environment);
  }

  @Test
  void requiresDurableContextAndStrictName() throws Exception {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId) -> List.of(),
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

    ToolResult blank =
        execute(tool, UUID.fromString("00000000-0000-0000-0000-000000000001"), "{\"name\":\"  \"}");
    assertTrue(blank.error());
  }

  /** cancel 必须中断 pending loader，并且重复 cancel 仍只产生一个 terminal ToolResult。 */
  @Test
  void cancellationCancelsThePendingLoadAndCompletesExactlyOnce() throws Exception {
    CompletableFuture<SkillBodyLoader.SkillBodyLoadResult> pending = new CompletableFuture<>();
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId) ->
                List.of(new SkillBinding("dev", "Developer rules", PLATFORM)),
            (environment, skillName, timeout) -> pending,
            Duration.ofSeconds(2));
    AtomicReference<ToolResult> result = new AtomicReference<>();
    AtomicInteger terminalCount = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(1);
    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall("c1", LoadSkillTool.NAME, "{\"name\":\"dev\"}"),
                Duration.ofSeconds(2),
                new ToolExecutionContext(
                    UUID.fromString("00000000-0000-0000-0000-000000000007"),
                    UUID.fromString("00000000-0000-0000-0000-000000000001"))),
            new ToolExecutionListener() {
              @Override
              public void onPartial(ToolResult partial) {}

              @Override
              public void onComplete(ToolResult complete) {
                terminalCount.incrementAndGet();
                result.set(complete);
                latch.countDown();
              }

              @Override
              public void onError(Throwable error) {
                throw new AssertionError(error);
              }
            });

    handle.cancel();
    handle.cancel();

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertTrue(handle.isCancelled());
    assertTrue(pending.isCancelled());
    assertTrue(result.get().error());
    assertTrue(text(result.get()).contains("Operation cancelled"));
    assertEquals(1, terminalCount.get());
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static ToolResult execute(LoadSkillTool tool, UUID threadId, String args)
      throws InterruptedException {
    AtomicReference<ToolResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "load_skill", args),
            Duration.ofSeconds(2),
            new ToolExecutionContext(
                UUID.fromString("00000000-0000-0000-0000-000000000007"), threadId)),
        completeListener(result, latch));
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    return result.get();
  }

  private static ToolExecutionListener completeListener(
      AtomicReference<ToolResult> result, CountDownLatch latch) {
    return new ToolExecutionListener() {
      @Override
      public void onPartial(ToolResult partial) {}

      @Override
      public void onComplete(ToolResult complete) {
        result.set(complete);
        latch.countDown();
      }

      @Override
      public void onError(Throwable error) {
        throw new AssertionError(error);
      }
    };
  }

  private static final class RecordingBodyLoader implements SkillBodyLoader {
    private EnvironmentBinding environment;
    private String skillName;
    private Duration timeout;
    private SkillBodyLoadResult result;

    @Override
    public CompletableFuture<SkillBodyLoadResult> load(
        EnvironmentBinding environment, String skillName, Duration timeout) {
      this.environment = environment;
      this.skillName = skillName;
      this.timeout = timeout;
      return CompletableFuture.completedFuture(result);
    }
  }
}
