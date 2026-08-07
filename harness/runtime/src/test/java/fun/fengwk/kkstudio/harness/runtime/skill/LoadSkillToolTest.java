package fun.fengwk.kkstudio.harness.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 覆盖 selected-skill 解析、platform source 优先级与离线失败场景。 */
class LoadSkillToolTest {

  @Test
  void loadsSelectedSkillBodyFromResolvedSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId) ->
            List.of(
                new SkillBinding("dev", "Developer rules", "platform"),
                new SkillBinding("project", "Project skill", "local-dev"));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result =
        new SkillBodyLoader.SkillBodyLoadResult.Loaded("dev", "# Skill\n\nDo the thing.\n");
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult result = execute(tool, 42, "{\"name\":\"dev\"}");
    assertFalse(result.error());
    assertEquals("# Skill\n\nDo the thing.\n", ((TextToolContent) result.contents().get(0)).text());
    assertEquals("platform", loader.environmentName);
    assertEquals("dev", loader.skillName);
  }

  @Test
  void rejectsUnselectedSkillAndOfflineSource() throws Exception {
    ThreadSelectedSkillLookup lookup =
        (invocationId, threadId) -> List.of(new SkillBinding("dev", "Developer rules", "platform"));
    RecordingBodyLoader loader = new RecordingBodyLoader();
    loader.result =
        new SkillBodyLoader.SkillBodyLoadResult.Failed(
            "dev", "platform is offline; dev is unavailable");
    LoadSkillTool tool = new LoadSkillTool(lookup, loader, Duration.ofSeconds(2));

    ToolResult unselected = execute(tool, 1, "{\"name\":\"missing\"}");
    assertTrue(unselected.error());
    assertTrue(text(unselected).contains("unknown or unselected skill"));

    ToolResult offline = execute(tool, 1, "{\"name\":\"dev\"}");
    assertTrue(offline.error());
    assertTrue(text(offline).contains("offline"));
  }

  @Test
  void requiresDurableContextAndStrictName() throws Exception {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId) -> List.of(),
            (env, name, timeout) -> CompletableFuture.completedFuture(null));
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

    ToolResult blank = execute(tool, 1, "{\"name\":\"  \"}");
    assertTrue(blank.error());
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static ToolResult execute(LoadSkillTool tool, long threadId, String args)
      throws InterruptedException {
    AtomicReference<ToolResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    tool.execute(
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("c1", "load_skill", args),
            Duration.ofSeconds(2),
            new ToolExecutionContext(7L, threadId)),
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
    private String environmentName;
    private String skillName;
    private SkillBodyLoadResult result;

    @Override
    public CompletableFuture<SkillBodyLoadResult> load(
        String environmentName, String skillName, Duration timeout) {
      this.environmentName = environmentName;
      this.skillName = skillName;
      return CompletableFuture.completedFuture(result);
    }
  }
}
