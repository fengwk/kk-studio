package fun.fengwk.kkstudio.harness.runtime.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * {@link ThreadJoinCompletionRenderer} 的 XML 完成消息渲染契约测试。
 *
 * <p>测试意图：完成消息外层为 {@code <subagent_result>}（含 thread_id / agent / state 属性）， 内部依次为说明文字、{@code
 * <task>} 本次任务原文与 {@code <result>} 结果； 失败/取消分离 {@code <error>} 与 {@code <partial_result>}。
 * 属性与正文均执行实体转义，长文本不截断。
 */
class ThreadJoinCompletionRendererTest {

  private static final UUID CHILD_THREAD_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  @Test
  void rendersCompletedMessageWithPromptAndReport() {
    // 测试意图：验证 COMPLETED 消息包含 thread_id/agent/state/task/result 结构，且不含 error/partial_result。
    String message =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID,
            "coder",
            ThreadJoinOutcome.COMPLETED,
            "do the thing",
            "done!",
            null,
            null);

    assertTrue(message.contains("thread_id=\"" + CHILD_THREAD_ID + "\""), message);
    assertTrue(message.contains("agent=\"coder\""), message);
    assertTrue(message.contains("state=\"completed\""), message);
    assertTrue(message.startsWith("<subagent_result thread_id=\"" + CHILD_THREAD_ID), message);
    assertTrue(message.endsWith("</subagent_result>"), message);
    assertTrue(message.contains(ThreadJoinCompletionRenderer.PROMPT_REFERENCE_NOTE), message);
    assertTrue(message.contains("<task>\ndo the thing\n</task>"), message);
    assertTrue(message.contains("<result>\ndone!\n</result>"), message);
    assertFalse(message.contains("<partial_result>"), message);
    assertFalse(message.contains("<error>"), message);
  }

  @Test
  void rendersFailedMessageWithSeparatedErrorAndPartial() {
    // 测试意图：验证 ERROR 消息把 error 与 partial_result 分开，且保留 task 原文。
    String message =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID,
            "explorer",
            ThreadJoinOutcome.ERROR,
            "explore",
            null,
            "half a report",
            "provider failed");

    assertTrue(message.contains("state=\"error\""), message);
    assertTrue(message.contains("<error>\nprovider failed\n</error>"), message);
    assertTrue(message.contains("<partial_result>\nhalf a report\n</partial_result>"), message);
    assertFalse(message.contains("<result>"), message);
  }

  @Test
  void rendersCancelledMessageWithOrWithoutPartialResult() {
    // 测试意图：验证 CANCELLED 消息正确渲染 state=cancelled，支持带或不带 partial_result。
    String withoutPartial =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID,
            "coder",
            ThreadJoinOutcome.CANCELLED,
            "work",
            null,
            null,
            "Cancelled by user");

    assertTrue(withoutPartial.contains("state=\"cancelled\""), withoutPartial);
    assertTrue(withoutPartial.contains("<error>\nCancelled by user\n</error>"), withoutPartial);
    assertFalse(withoutPartial.contains("<partial_result>"), withoutPartial);

    String withPartial =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID,
            "coder",
            ThreadJoinOutcome.CANCELLED,
            "work",
            null,
            "partially written",
            "Cancelled by user");

    assertTrue(withPartial.contains("state=\"cancelled\""), withPartial);
    assertTrue(
        withPartial.contains("<partial_result>\npartially written\n</partial_result>"),
        withPartial);
    assertTrue(withPartial.contains("<error>\nCancelled by user\n</error>"), withPartial);
  }

  @Test
  void escapesAttributeAndBodySpecialCharacters() {
    // 测试意图：验证 XML 五大特殊字符（& < > " '）在属性和正文中均被转义，防止标签逃逸。
    String message =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID,
            "a<b>&\"c'd",
            ThreadJoinOutcome.COMPLETED,
            "</subagent_result> & <tag> 'quote'",
            "</result> & \"done\"",
            null,
            null);

    assertTrue(message.contains("agent=\"a&lt;b&gt;&amp;&quot;c&apos;d\""), message);
    assertTrue(
        message.contains("&lt;/subagent_result&gt; &amp; &lt;tag&gt; &apos;quote&apos;"), message);
    assertTrue(message.contains("&lt;/result&gt; &amp; &quot;done&quot;"), message);
    assertEquals(1, countOccurrences(message, "</subagent_result>"));

    String body =
        message.substring(
            message.indexOf("<task>\n") + "<task>\n".length(), message.indexOf("\n</task>"));
    assertEquals("</subagent_result> & <tag> 'quote'", unescape(body));
  }

  @Test
  void fallsBackToPlaceholdersForMissingText() {
    // 测试意图：验证报告与错误详情缺失或全空白时使用明确占位符，避免静默空白。
    String completed =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.COMPLETED, "p", "  ", null, null);
    assertTrue(completed.contains("(no textual report produced)"), completed);

    String failed =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, null);
    assertTrue(failed.contains("(no failure detail produced)"), failed);
  }

  @Test
  void neverTruncatesLongBodies() {
    // 测试意图：验证超长文本（>20,000 字符）不被截断，完整保留。
    String longReport = "x".repeat(25_000) + "_TAIL";
    String message =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID,
            "coder",
            ThreadJoinOutcome.COMPLETED,
            "task prompt",
            longReport,
            null,
            null);

    assertTrue(message.contains(longReport), "full report must be preserved");
    assertFalse(message.contains("truncated"), message);
  }

  @Test
  void rejectsBlankRequiredText() {
    // 测试意图：验证 agent 与 prompt 为 null 或空白时确定性拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadJoinCompletionRenderer.render(
                CHILD_THREAD_ID, "  ", ThreadJoinOutcome.COMPLETED, "p", "r", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadJoinCompletionRenderer.render(
                CHILD_THREAD_ID, "coder", ThreadJoinOutcome.COMPLETED, " ", "r", null, null));
    assertThrows(
        NullPointerException.class,
        () ->
            ThreadJoinCompletionRenderer.render(
                null, "coder", ThreadJoinOutcome.COMPLETED, "p", "r", null, null));
    assertThrows(
        NullPointerException.class,
        () ->
            ThreadJoinCompletionRenderer.render(
                CHILD_THREAD_ID, "coder", null, "p", "r", null, null));
  }

  @Test
  void rendersReceiptDirectly() {
    // 测试意图：验证直接传入 ThreadJoinReceipt 调用 render 方法等价于逐参数调用。
    ThreadJoinReceipt receipt =
        new ThreadJoinReceipt(
            UUID.randomUUID(),
            CHILD_THREAD_ID,
            "coder",
            ThreadJoinOutcome.COMPLETED,
            "fix tests",
            "fixed!",
            null,
            null);

    String rendered = ThreadJoinCompletionRenderer.render(receipt);
    assertEquals(receipt.renderCompletionXml(), rendered);
    assertTrue(rendered.contains("<task>\nfix tests\n</task>"), rendered);
    assertTrue(rendered.contains("<result>\nfixed!\n</result>"), rendered);
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }

  private static String unescape(String escaped) {
    return escaped
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
  }
}
