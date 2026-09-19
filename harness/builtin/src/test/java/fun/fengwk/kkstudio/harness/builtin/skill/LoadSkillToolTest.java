package fun.fengwk.kkstudio.harness.builtin.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 覆盖平台 Skill 解析、冻结身份加载与失败语义。 */
class LoadSkillToolTest {

  private static SelectedSkill selectedSkill() {
    return new SelectedSkill("dev", "developer-tools", "1.0.0", "Developer rules");
  }

  /** 工具无需 Environment，且仅声明一个必填名称参数。 */
  @Test
  void exposesCanonicalDescriptorContract() {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.empty(),
            (packageName, packageVersion, name) -> {
              throw new AssertionError("unexpected load");
            });

    ToolDescriptor descriptor = tool.descriptor();
    assertEquals(LoadSkillTool.NAME, descriptor.name());
    assertEquals(LoadSkillTool.NAME, descriptor.rendererKey());
    assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
    assertEquals(Duration.ofMinutes(1), descriptor.timeout());
    assertEquals(ToolRequirements.none(), tool.requirements());

    InputSchema schema = descriptor.inputSchema();
    assertEquals(List.of("name"), schema.properties().keySet().stream().toList());
    assertInstanceOf(StringSchema.class, schema.properties().get("name"));
    assertEquals(Set.of("name"), schema.required());
    assertFalse(schema.additionalProperties());
  }

  /** 加载器接收冻结的包版本身份并原样返回正文。 */
  @Test
  void loadsSelectedFrozenIdentityWithoutEnvironment() throws Exception {
    AtomicReference<SelectedSkill> loaded = new AtomicReference<>();
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) ->
                "dev".equals(skillName) ? Optional.of(selectedSkill()) : Optional.empty(),
            (packageName, packageVersion, name) -> {
              loaded.set(new SelectedSkill(name, packageName, packageVersion, "Developer rules"));
              return "# Skill\n\nDo the thing.\n";
            });

    ToolResult result = execute(tool, "{\"name\":\"dev\"}");

    assertFalse(result.error());
    assertEquals(
        "# Skill\n\nDo the thing.\n", ((TextResultContent) result.contents().getFirst()).text());
    assertEquals(selectedSkill(), loaded.get());
  }

  /** 未选中、正文读取失败和非法参数均返回明确的错误结果。 */
  @Test
  void reportsLookupLoadingAndArgumentFailures() throws Exception {
    LoadSkillTool missing =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.empty(),
            (packageName, packageVersion, name) -> "unused");
    ToolResult missingResult = execute(missing, "{\"name\":\"missing\"}");
    assertTrue(missingResult.error());
    assertTrue(text(missingResult).contains("unknown or unselected skill"));

    LoadSkillTool failed =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.of(selectedSkill()),
            (packageName, packageVersion, name) -> {
              throw new IllegalStateException("content missing");
            });
    ToolResult failedResult = execute(failed, "{\"name\":\"dev\"}");
    assertTrue(failedResult.error());
    assertTrue(text(failedResult).contains("content missing"));

    ToolResult blankResult = execute(missing, "{\"name\":\"  \"}");
    assertTrue(blankResult.error());
    assertTrue(text(blankResult).contains("must not be blank"));
  }

  /** Durable invocation/thread 身份是查找冻结 Skill 的必要条件。 */
  @Test
  void requiresDurableExecutionContext() throws Exception {
    LoadSkillTool tool =
        new LoadSkillTool(
            (invocationId, threadId, skillName) -> Optional.empty(),
            (packageName, packageVersion, name) -> "unused");
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
  }

  /** SelectedSkill 必须完整冻结包身份和展示描述。 */
  @Test
  void selectedSkillRequiresCompleteFrozenIdentity() {
    assertThrows(
        NullPointerException.class,
        () -> new SelectedSkill(null, "package", "1.0.0", "description"));
    assertThrows(
        NullPointerException.class, () -> new SelectedSkill("dev", null, "1.0.0", "description"));
    assertThrows(
        NullPointerException.class, () -> new SelectedSkill("dev", "package", null, "description"));
    assertThrows(
        NullPointerException.class, () -> new SelectedSkill("dev", "package", "1.0.0", null));
  }

  private static ToolResult execute(LoadSkillTool tool, String argumentsJson) throws Exception {
    AtomicReference<ToolResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            mock(BranchView.class),
            Optional.empty());

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
      public void onError(Throwable error) {
        throw new AssertionError(error);
      }
    };
  }

  private static String text(ToolResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }
}
