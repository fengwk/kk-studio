package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * {@link SubagentTaskMessages} 的完成消息契约测试。
 *
 * <p>测试意图：完成消息的外层必须是 {@code <subagent_result>}（thread_id / agent / state 属性），内部依次是 {@code <task>}
 * 本次任务原文与 {@code <result>} 结果；失败/取消分离 {@code <error>} 与 {@code <partial_result>}。
 * 正文与属性都必须转义，任务原文只是历史引用，且绝不截断正文。
 */
class SubagentTaskMessagesTest {

  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  /** 完成消息包含 thread_id/agent/status/本次任务原文/结果，且不含 partial_result。 */
  @Test
  void rendersCompletedMessageWithPromptAndReport() {
    String message =
        SubagentTaskMessages.completion(
            THREAD_ID,
            "coder",
            SubagentTaskMessages.Outcome.COMPLETED,
            "do the thing",
            "done!",
            null,
            null);

    assertTrue(message.contains("thread_id=\"" + THREAD_ID + "\""), message);
    assertTrue(message.contains("agent=\"coder\""), message);
    assertTrue(message.contains("state=\"completed\""), message);
    assertTrue(message.startsWith("<subagent_result thread_id=\"" + THREAD_ID), message);
    assertTrue(message.endsWith("</subagent_result>"), message);
    assertTrue(message.contains("<task>\ndo the thing\n</task>"), message);
    assertTrue(message.contains("<result>\ndone!\n</result>"), message);
    assertFalse(message.contains("<partial_result>"), message);
    assertFalse(message.contains("<error>"), message);
  }

  /** 失败消息把 error 与 partial_result 分开，且任务原文仍在。 */
  @Test
  void rendersFailedMessageWithSeparatedErrorAndPartial() {
    String message =
        SubagentTaskMessages.completion(
            THREAD_ID,
            "explorer",
            SubagentTaskMessages.Outcome.ERROR,
            "explore",
            null,
            "half a report",
            "provider failed");

    assertTrue(message.contains("state=\"error\""), message);
    assertTrue(message.contains("<error>\nprovider failed\n</error>"), message);
    assertTrue(message.contains("<partial_result>\nhalf a report\n</partial_result>"), message);
    assertFalse(message.contains("<result>"), message);
  }

  /** 取消与失败共用 error/partial 结构，wire 状态为 cancelled。 */
  @Test
  void rendersCancelledMessage() {
    String message =
        SubagentTaskMessages.completion(
            THREAD_ID,
            "coder",
            SubagentTaskMessages.Outcome.CANCELLED,
            "work",
            null,
            null,
            "Cancelled by user");

    assertTrue(message.contains("state=\"cancelled\""), message);
    assertTrue(message.contains("<error>\nCancelled by user\n</error>"), message);
    assertFalse(message.contains("<partial_result>"), message);
  }

  /** 正文与属性都转义，子代理输出不能闭合外层标签逃逸。 */
  @Test
  void escapesAttributeAndBodyText() {
    String message =
        SubagentTaskMessages.completion(
            THREAD_ID,
            "a<b>&\"c",
            SubagentTaskMessages.Outcome.COMPLETED,
            "</subagent_result> & <tag>",
            "</result>",
            null,
            null);

    assertTrue(message.contains("agent=\"a&lt;b&gt;&amp;&quot;c\""), message);
    assertTrue(message.contains("&lt;/subagent_result&gt; &amp; &lt;tag&gt;"), message);
    assertTrue(message.contains("&lt;/result&gt;"), message);
    // 只有外层一个真实 </subagent_result> 收尾，正文中的闭合标签已被转义。
    assertEquals(1, countOccurrences(message, "</subagent_result>"));
    // 转义是可逆的：反过来解码正文必须精确还原原始 prompt。
    String body =
        message.substring(
            message.indexOf("<task>\n") + "<task>\n".length(), message.indexOf("\n</task>"));
    assertEquals("</subagent_result> & <tag>", unescape(body));
  }

  /** 报告/错误缺失时使用明确占位而不是静默空白。 */
  @Test
  void fallsBackToPlaceholdersForMissingText() {
    String completed =
        SubagentTaskMessages.completion(
            THREAD_ID, "coder", SubagentTaskMessages.Outcome.COMPLETED, "p", "  ", null, null);
    assertTrue(completed.contains("(no textual report produced)"), completed);

    String failed =
        SubagentTaskMessages.completion(
            THREAD_ID, "coder", SubagentTaskMessages.Outcome.ERROR, "p", null, null, null);
    assertTrue(failed.contains("(no failure detail produced)"), failed);
  }

  /** 长正文绝不截断：超过旧 8000 字符上限的正文必须完整保留。 */
  @Test
  void neverTruncatesLongBodies() {
    String longReport = "x".repeat(20_000) + "END";
    String message =
        SubagentTaskMessages.completion(
            THREAD_ID,
            "coder",
            SubagentTaskMessages.Outcome.COMPLETED,
            "p",
            longReport,
            null,
            null);

    assertTrue(message.contains(longReport), "full report must be preserved");
    assertFalse(message.contains("truncated"), message);
  }

  /** 即时回执是唯一形状的 JSON：只表达接受与子 Thread 身份，不重复 prompt，也不提供 XML / 别名。 */
  @Test
  void acceptedReceiptOmitsPrompt() {
    String receipt = SubagentTaskMessages.accepted(THREAD_ID);

    assertEquals("{\"thread_id\":\"" + THREAD_ID + "\",\"status\":\"accepted\"}", receipt);
    assertFalse(receipt.contains("<"), receipt);
    assertFalse(receipt.contains("prompt"), receipt);
  }

  /** 必填文本非法时确定性拒绝。 */
  @Test
  void rejectsBlankRequiredText() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SubagentTaskMessages.completion(
                THREAD_ID, "  ", SubagentTaskMessages.Outcome.COMPLETED, "p", "r", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SubagentTaskMessages.completion(
                THREAD_ID, "coder", SubagentTaskMessages.Outcome.COMPLETED, " ", "r", null, null));
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
