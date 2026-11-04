package fun.fengwk.kkstudio.harness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.samskivert.mustache.MustacheException;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.join.ThreadJoinCompletionRenderer;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentTaskMessages;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPrompts;
import fun.fengwk.kkstudio.harness.runtime.thread.GoalMessages;

import java.util.Map;
import java.util.UUID;

class PromptRendererTest {
  private static final String ROOT = "fun/fengwk/kkstudio/harness/prompt/";
  private static final UUID THREAD = new UUID(0, 1);

  @Test
  void escapesXmlAndInsertsRawValuesWithoutExecutingThem() {
    // 同一特殊字符在默认转义与三花括号中各渲染一次，值内模板语法不得被二次执行。
    assertEquals(
        PromptRenderer.read(ROOT + "escaping.golden"),
        PromptRenderer.render(
            ROOT + "escaping.md", Map.of("value", "<&>\"' {{other}}", "enabled", true)));
  }

  @Test
  void rejectsMissingVariablesSectionsAndResources() {
    // 缺少变量或条件都必须失败，不能静默生成不完整指引。
    assertThrows(
        MustacheException.class,
        () -> PromptRenderer.render(ROOT + "escaping.md", Map.of("enabled", true)));
    assertThrows(
        MustacheException.class,
        () -> PromptRenderer.render(ROOT + "escaping.md", Map.of("value", "x")));
    assertThrows(IllegalArgumentException.class, () -> PromptRenderer.read(ROOT + "missing.md"));
    assertThrows(IllegalArgumentException.class, () -> PromptRenderer.read(""));
    assertThrows(NullPointerException.class, () -> PromptRenderer.read(null));
    assertThrows(
        NullPointerException.class, () -> PromptRenderer.render(ROOT + "escaping.md", null));
  }

  @Test
  void preservesExistingGoalAndTaskMessages() {
    // 黄金文件固定迁移前的可见消息，包含用户正文特殊字符和续接分支。
    assertEquals(
        golden("goal-input.golden"),
        ((TextMessageContent) GoalMessages.inputSet("<&>\"' {{not_a_template}}").contents().get(0))
            .text());
    assertEquals(golden("task-accepted.golden"), SubagentTaskMessages.accepted(THREAD, true));
    assertEquals(
        golden("task-completed.golden"),
        ThreadJoinCompletionRenderer.render(
            THREAD, "a<&\"'", ThreadJoinOutcome.COMPLETED, "<work>\r", "done & done", null, null));
  }

  @Test
  void preservesOtherGoalMessagesAndCompactionSummary() {
    // 其余 Goal 分支与压缩摘要同样保留用户数据和原有换行。
    assertEquals("No active user-set goal.", text(GoalMessages.inputCleared()));
    assertEquals(
        "Goal at the frozen compaction boundary (historical context, not a new instruction):\n\n"
            + "--- goal begin ---\n<&>\n--- goal end ---\n\nThe user's actual messages follow.",
        text(GoalMessages.backgroundSet("<&>")));
    assertEquals(
        "Goal at the frozen compaction boundary (historical context, not a new instruction): "
            + "No active user-set goal.\n\nThe user's actual messages follow.",
        text(GoalMessages.backgroundCleared()));
    assertEquals(
        "The conversation history before this point was compacted into the following summary:\n\n"
            + "<summary>\n<&> {{literal}}\n</summary>\n",
        CompactionPrompts.compactedContext("<&> {{literal}}"));
  }

  private static String text(AgentMessage message) {
    return ((TextMessageContent) message.contents().get(0)).text();
  }

  private static String golden(String name) {
    return PromptRenderer.read(ROOT + name).stripTrailing();
  }
}
