package fun.fengwk.kkstudio.harness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.samskivert.mustache.MustacheException;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;

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

  private static String text(AgentMessage message) {
    return ((TextMessageContent) message.contents().get(0)).text();
  }

  private static String golden(String name) {
    return PromptRenderer.read(ROOT + name).stripTrailing();
  }
}
