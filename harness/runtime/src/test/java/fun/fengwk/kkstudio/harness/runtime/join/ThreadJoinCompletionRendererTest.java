package fun.fengwk.kkstudio.harness.runtime.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.StringReader;
import java.util.UUID;

/**
 * {@link ThreadJoinCompletionRenderer} 的 XML 完成消息渲染契约测试。
 *
 * <p>测试意图：完成消息外层为 {@code <subagent_result>}（含 thread_id / agent / state 属性）， 内部依次为说明文字、{@code
 * <task>} 本次任务原文与 {@code <result>} 结果； 失败/取消分离 {@code <error>} 与 {@code <partial_result>}。
 * 属性与正文均执行实体转义；prompt/report/partial_result 完整不截断，仅 error 详情按 Unicode code point 有界截断并带英文标记，
 * 整封信封仍可被标准 XML 解析器往返解析。
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
    assertFalse(message.contains("<resume>"), message);
  }

  @Test
  void rendersFailedMessageWithSeparatedErrorAndPartial() {
    // 测试意图：验证 ERROR 消息把 error 与 partial_result 分开，且保留 task 原文与具体 task(...) 恢复方式。
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
    assertTrue(message.contains("<resume>\n"), message);
    assertTrue(
        message.contains(
            "task(thread_id=&quot;"
                + CHILD_THREAD_ID
                + "&quot;, subagent_type=&quot;explorer&quot;"),
        message);
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
  void errorsAtOrBelowCodePointLimitAreNotTruncated() {
    // 测试意图：999 与 1000 code point 的 error 详情原样保留，不加截断标记（边界为 <= 1000）。
    String error999 = "e".repeat(999);
    String error1000 = "e".repeat(1000);
    String message999 =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, error999);
    String message1000 =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, error1000);

    assertTrue(message999.contains("<error>\n" + error999 + "\n</error>"), message999);
    assertTrue(message1000.contains("<error>\n" + error1000 + "\n</error>"), message1000);
    assertFalse(message999.contains(ThreadJoinCompletionRenderer.TRUNCATION_MARKER), message999);
    assertFalse(message1000.contains(ThreadJoinCompletionRenderer.TRUNCATION_MARKER), message1000);
  }

  @Test
  void errorOverCodePointLimitIsTruncatedToExactlyLimitWithMarker() {
    // 测试意图：1001 与超大 error 截断为恰好 1000 code point（988 前缀 + 12 code point 标记），标记计入上限。
    String marker = ThreadJoinCompletionRenderer.TRUNCATION_MARKER;
    assertEquals(12, marker.codePointCount(0, marker.length()), "marker must be 12 code points");

    String error1001 = "e".repeat(1001);
    String expected1001 = "e".repeat(988) + marker;
    String huge = "e".repeat(200_000);
    String expectedHuge = "e".repeat(988) + marker;

    String message1001 =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, error1001);
    String messageHuge =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, huge);

    assertTrue(message1001.contains("<error>\n" + expected1001 + "\n</error>"), message1001);
    assertTrue(messageHuge.contains("<error>\n" + expectedHuge + "\n</error>"));
    assertEquals(1000, expected1001.codePointCount(0, expected1001.length()));
  }

  @Test
  void truncatesMultibyteAndEmojiWithoutSplittingSurrogatePairs() throws Exception {
    // 测试意图：多字节中文与 emoji（代理对）按 code point 边界截断，既不拆散代理对，也不破坏 XML 解析。
    String marker = ThreadJoinCompletionRenderer.TRUNCATION_MARKER;
    String chinese = "错".repeat(1001);
    String emoji = "\uD83D\uDE80".repeat(1001); // 🚀，每个为 1 个 code point / 2 个 UTF-16 char
    String expectedChinese = "错".repeat(988) + marker;
    String expectedEmoji = "\uD83D\uDE80".repeat(988) + marker;

    Element chineseRoot =
        parseXml(
                ThreadJoinCompletionRenderer.render(
                    CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, chinese))
            .getDocumentElement();
    Element emojiRoot =
        parseXml(
                ThreadJoinCompletionRenderer.render(
                    CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, emoji))
            .getDocumentElement();

    assertEquals(framed(expectedChinese), directChildText(chineseRoot, "error"));
    assertEquals(framed(expectedEmoji), directChildText(emojiRoot, "error"));
    assertEquals(
        1000, expectedEmoji.codePointCount(0, expectedEmoji.length()), "truncated total is 1000");
    // 解析后的 error 正文必须仍是合法 UTF-16：无落单的 high/low surrogate。
    assertNoLoneSurrogate(directChildText(emojiRoot, "error"));
  }

  @Test
  void truncatedErrorWithXmlSpecialCharactersEscapesAndParsesBack() throws Exception {
    // 测试意图：超长且含 XML 特殊字符的 error 先按 code point 截断、再整段转义，解析后可逐字恢复模型可见 preview。
    String error = "fail <tag> & \"q\" 'a'\r\n".repeat(100);
    String expected = ThreadJoinCompletionRenderer.boundedError(error);
    assertTrue(expected.endsWith(ThreadJoinCompletionRenderer.TRUNCATION_MARKER), expected);

    Element root =
        parseXml(
                ThreadJoinCompletionRenderer.render(
                    CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, null, error))
            .getDocumentElement();

    assertEquals(framed(expected), directChildText(root, "error"));
  }

  @Test
  void boundedErrorPreservesLeadingAndTrailingWhitespaceAsVerbatimPrefix() {
    // 测试意图：截断只保留原文前缀原样（含首尾空白），不 trim；空白 fallback 仍走占位机制。
    String error = "  \n" + "body ".repeat(300) + "\n  ";
    String bounded = ThreadJoinCompletionRenderer.boundedError(error);

    assertTrue(bounded.startsWith("  \n"), bounded.substring(0, 8));
    assertTrue(bounded.endsWith(ThreadJoinCompletionRenderer.TRUNCATION_MARKER), bounded);
    assertEquals(1000, bounded.codePointCount(0, bounded.length()));

    assertEquals("(no failure detail produced)", ThreadJoinCompletionRenderer.boundedError(null));
    assertEquals("(no failure detail produced)", ThreadJoinCompletionRenderer.boundedError("   "));
  }

  @Test
  void cancelledErrorsAreAlsoBounded() {
    // 测试意图：CANCELLED 与 ERROR 一样对 error 详情施加相同的 code point 上限。
    String longError = "stop ".repeat(400);
    String cancelled =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.CANCELLED, "p", null, null, longError);

    assertTrue(cancelled.contains(ThreadJoinCompletionRenderer.TRUNCATION_MARKER), cancelled);
    assertFalse(cancelled.contains(longError), cancelled);
  }

  @Test
  void longPartialResultIsNotTruncated() {
    // 测试意图：失败/取消的 partial_result 与 prompt/report 一样不受 error 上限影响，完整保留。
    String longPartial = "partial ".repeat(4_000) + "_TAIL";
    String message =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.CANCELLED, "p", null, longPartial, "x");

    assertTrue(
        message.contains("<partial_result>\n" + longPartial + "\n</partial_result>"), message);
  }

  private static void assertNoLoneSurrogate(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!Character.isSurrogate(c)) {
        continue;
      }
      boolean paired = i + 1 < text.length() && Character.isSurrogatePair(c, text.charAt(i + 1));
      assertTrue(paired, "lone surrogate at index " + i);
      i++;
    }
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

  @Test
  void roundTripsEverySpecialCharacterThroughRealXmlParser() throws Exception {
    // 测试意图：8 类特殊字符（& < > " ' 换行 回车 制表）在属性与正文中都能被标准 XML 解析器
    // 逐字解析回原值，而不是只做字符串包含断言；说明文字中的裸 <task> 也必须已被转义。
    String special = "amp& lt< gt> quote\" apos' nl\n cr\r tab\t end";
    String message =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, special, ThreadJoinOutcome.COMPLETED, special, special, null, null);

    assertFalse(message.contains("<task> block below"), "note's bare <task> would break XML");
    assertTrue(message.contains("&lt;task&gt; block below"), message);

    Element root = parseXml(message).getDocumentElement();
    assertEquals("subagent_result", root.getTagName());
    assertEquals(CHILD_THREAD_ID.toString(), root.getAttribute("thread_id"));
    assertEquals(special, root.getAttribute("agent"));
    assertEquals("completed", root.getAttribute("state"));
    assertEquals(framed(special), directChildText(root, "task"));
    assertEquals(framed(special), directChildText(root, "result"));
    assertNull(directChildText(root, "error"));
    assertNull(directChildText(root, "partial_result"));
    assertTrue(root.getFirstChild().getTextContent().contains("<task> block below"), "note text");
  }

  @Test
  void roundTripsLongPromptAndReportWithoutTruncation() throws Exception {
    // 测试意图：超长任务原文与报告（>20,000 字符）经真实 XML 解析后仍逐字完整，验证不截断且不被空白规范化。
    String longPrompt = "p line\r\n".repeat(4_000) + "<end>&";
    String longReport = "r <tag> & \"q\" \r\n".repeat(4_000) + "_TAIL";
    String message =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID,
            "coder",
            ThreadJoinOutcome.COMPLETED,
            longPrompt,
            longReport,
            null,
            null);

    Element root = parseXml(message).getDocumentElement();
    assertEquals(framed(longPrompt), directChildText(root, "task"));
    assertEquals(framed(longReport), directChildText(root, "result"));
  }

  @Test
  void roundTripsFailedAndCancelledPartialThroughRealXmlParser() throws Exception {
    // 测试意图：失败/取消回执的 error 与 partial_result（含特殊字符与 CR）分离且可被真实 XML 解析器往返解析。
    String partial = "half & <report>\r\nkept\t";
    String error = "failed <because> \"why\"\n";
    Element failed =
        parseXml(
                ThreadJoinCompletionRenderer.render(
                    CHILD_THREAD_ID,
                    "explorer",
                    ThreadJoinOutcome.ERROR,
                    "explore",
                    null,
                    partial,
                    error))
            .getDocumentElement();
    assertEquals("error", failed.getAttribute("state"));
    assertEquals(framed("explore"), directChildText(failed, "task"));
    assertEquals(framed(error), directChildText(failed, "error"));
    assertEquals(framed(partial), directChildText(failed, "partial_result"));
    assertNull(directChildText(failed, "result"));

    Element cancelledWithoutPartial =
        parseXml(
                ThreadJoinCompletionRenderer.render(
                    CHILD_THREAD_ID,
                    "coder",
                    ThreadJoinOutcome.CANCELLED,
                    "work",
                    null,
                    null,
                    "stopped"))
            .getDocumentElement();
    assertEquals("cancelled", cancelledWithoutPartial.getAttribute("state"));
    assertEquals(framed("stopped"), directChildText(cancelledWithoutPartial, "error"));
    assertNull(directChildText(cancelledWithoutPartial, "partial_result"));
  }

  /** 渲染器把每段正文包裹在标签内的换行之间，正文内容本身需与这些框架换行分开断言。 */
  private static String framed(String body) {
    return "\n" + body + "\n";
  }

  @Test
  void coversPlaceholderAndOptionalBranchBoundaries() {
    // 测试意图：null 与全空白正文分别走占位分支；全空白 partial 被跳过；null agent/prompt 确定性拒绝。
    String nullReport =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.COMPLETED, "p", null, null, null);
    assertTrue(nullReport.contains("(no textual report produced)"), nullReport);

    String blankErrorBlankPartial =
        ThreadJoinCompletionRenderer.render(
            CHILD_THREAD_ID, "coder", ThreadJoinOutcome.ERROR, "p", null, "   ", "  ");
    assertTrue(
        blankErrorBlankPartial.contains("(no failure detail produced)"), blankErrorBlankPartial);
    assertFalse(blankErrorBlankPartial.contains("<partial_result>"), blankErrorBlankPartial);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadJoinCompletionRenderer.render(
                CHILD_THREAD_ID, null, ThreadJoinOutcome.COMPLETED, "p", "r", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadJoinCompletionRenderer.render(
                CHILD_THREAD_ID, "coder", ThreadJoinOutcome.COMPLETED, null, "r", null, null));
  }

  private static Document parseXml(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
  }

  private static String directChildText(Element parent, String tag) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(((Element) node).getTagName())) {
        return node.getTextContent();
      }
    }
    return null;
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
