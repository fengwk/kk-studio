package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.List;

/**
 * {@link ToolResultModelContents} 的纯运行时派生契约测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>成功且 rendererKey 为 edit 时，严格从 editReview 派生包含路径与 diff 的 TextMessageContent；
 *   <li>缺失 editReview 可选字段时，保持原 contents 不变；
 *   <li>editReview 存在但非法（缺少字段、非对象、空 diff）或 detailsJson 非对象时，抛出明确 IllegalArgumentException，绝不静默吞掉；
 *   <li>执行失败或其它 rendererKey 时，保持原 contents，不解析 detailsJson；
 *   <li>派生内容不泄露 sha256，且以换行开头保证直接拼接换行。
 * </ul>
 */
class ToolResultModelContentsTest {

  private static final String SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private static final String VALID_DETAILS =
      "{\"editReview\":{\"path\":\"src/Main.java\",\"diff\":\"@@ -1 +1 @@\\n-old\\n+new\",\"sha256\":\""
          + SHA256
          + "\"}}";

  @Test
  void nullResultThrowsNpe() {
    assertThrows(NullPointerException.class, () -> ToolResultModelContents.contentsOf(null));
  }

  @Test
  void nonSuccessPreservesOriginalContents() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Command failed"));
    ToolResultMessageContent result =
        new ToolResultMessageContent("call-1", "edit", "edit", original, true, VALID_DETAILS);

    List<AgentMessageContent> contents = ToolResultModelContents.contentsOf(result);

    assertSame(original, contents);
  }

  @Test
  void otherRendererKeyPreservesOriginalContents() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("output"));
    ToolResultMessageContent result =
        new ToolResultMessageContent("call-1", "bash", "bash", original, false, VALID_DETAILS);

    List<AgentMessageContent> contents = ToolResultModelContents.contentsOf(result);

    assertSame(original, contents);
  }

  @Test
  void missingEditReviewFieldPreservesOriginalContents() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent("call-1", "edit", "edit", original, false, "{}");

    List<AgentMessageContent> contents = ToolResultModelContents.contentsOf(result);

    assertSame(original, contents);
  }

  @Test
  void nullEditReviewFieldPreservesOriginalContents() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1", "edit", "edit", original, false, "{\"editReview\":null}");

    List<AgentMessageContent> contents = ToolResultModelContents.contentsOf(result);

    assertSame(original, contents);
  }

  @Test
  void validEditReviewAppendsDerivedDiffWithoutSha256() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent("call-1", "edit", "edit", original, false, VALID_DETAILS);

    List<AgentMessageContent> contents = ToolResultModelContents.contentsOf(result);

    assertEquals(2, contents.size());
    assertEquals(original.get(0), contents.get(0));
    assertTrue(contents.get(1) instanceof TextMessageContent);
    String derivedText = ((TextMessageContent) contents.get(1)).text();
    assertEquals("\n\nFile: src/Main.java\nDiff:\n@@ -1 +1 @@\n-old\n+new", derivedText);
    assertTrue(derivedText.startsWith("\n\nFile: src/Main.java"));
    assertTrue(!derivedText.contains("abc123"));
    assertTrue(!derivedText.contains("sha256"));
  }

  @Test
  void malformedDetailsJsonThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent("call-1", "edit", "edit", original, false, "not-json");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }

  @Test
  void nonObjectDetailsJsonThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent("call-1", "edit", "edit", original, false, "[1,2,3]");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }

  @Test
  void nonObjectEditReviewThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1", "edit", "edit", original, false, "{\"editReview\":\"string\"}");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }

  @Test
  void missingPathInEditReviewThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1",
            "edit",
            "edit",
            original,
            false,
            "{\"editReview\":{\"diff\":\"+foo\",\"sha256\":\"" + SHA256 + "\"}}");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }

  @Test
  void missingDiffInEditReviewThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1",
            "edit",
            "edit",
            original,
            false,
            "{\"editReview\":{\"path\":\"a.txt\",\"sha256\":\"" + SHA256 + "\"}}");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }

  @Test
  void missingSha256InEditReviewThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1",
            "edit",
            "edit",
            original,
            false,
            "{\"editReview\":{\"path\":\"a.txt\",\"diff\":\"+foo\"}}");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }

  @Test
  void emptyDiffInEditReviewThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1",
            "edit",
            "edit",
            original,
            false,
            "{\"editReview\":{\"path\":\"a.txt\",\"diff\":\"\",\"sha256\":\"" + SHA256 + "\"}}");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }

  @Test
  void extraUnknownFieldInEditReviewThrowsIae() {
    List<AgentMessageContent> original = List.of(new TextMessageContent("Edited successfully."));
    ToolResultMessageContent result =
        new ToolResultMessageContent(
            "call-1",
            "edit",
            "edit",
            original,
            false,
            "{\"editReview\":{\"path\":\"a.txt\",\"diff\":\"+foo\",\"sha256\":\""
                + SHA256
                + "\",\"extra\":true}}");

    assertThrows(IllegalArgumentException.class, () -> ToolResultModelContents.contentsOf(result));
  }
}
