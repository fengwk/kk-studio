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
 * 属性与正文均执行实体转义，长文本不截断，整封信封可被标准 XML 解析器往返解析。
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
