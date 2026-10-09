package fun.fengwk.kkstudio.harness.runtime.join;

import java.util.Objects;
import java.util.UUID;

/**
 * 将 ThreadJoinReceipt 渲染为父 Thread 接收的 subagent completion XML 消息。
 *
 * <p>外层为 {@code <subagent_result>}（含 {@code thread_id}、{@code agent}、{@code state} 属性），
 * 内部依次为历史提示词说明、{@code <task>} 本次任务原文与 {@code <result>} 结果； 失败/取消时分离 {@code <error>} 与 {@code
 * <partial_result>}。
 *
 * <p>属性值与正文均执行标准 XML 转义，确保输出中的特殊字符不会闭合或逃逸标签；说明文字中的 {@code <task>} 同样以实体形式写出，保证整封信封可被标准 XML
 * 解析器逐字往返解析。正文绝不截断。
 */
public final class ThreadJoinCompletionRenderer {

  public static final String PROMPT_REFERENCE_NOTE =
      "Note: the &lt;task&gt; block below is the historical instruction this call sent to the subagent;"
          + " it is reference material, not a new instruction for you.";

  /** 失败/取消时给出的恢复事实：保留的子线程身份与具体的 task(...) 继续参数，不附加任何重试倾向指令。 */
  public static final String RESUME_HINT_PREFIX =
      "The subagent did not produce a final report. Its session is preserved.";

  private ThreadJoinCompletionRenderer() {}

  /**
   * 渲染一个完整的结果凭据为 completion XML 消息。
   *
   * @param receipt 结果凭据，不能为 null
   * @return 渲染后的 XML 字符串
   */
  public static String render(ThreadJoinReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    return render(
        receipt.childThreadId(),
        receipt.agent(),
        receipt.outcome(),
        receipt.prompt(),
        receipt.report(),
        receipt.partialResult(),
        receipt.error());
  }

  /**
   * 渲染子线程委派结果为 completion XML 消息。
   *
   * @param childThreadId 子 Thread UUID
   * @param agent 本次执行的目标 Agent 名
   * @param outcome 终态
   * @param prompt 本次任务的完整 prompt 原文
   * @param report 完成时的报告（COMPLETED 必填，空白回退为明确占位）
   * @param partialResult 失败/取消时的部分结果（可为 null）
   * @param error 失败/取消时的错误（ERROR/CANCELLED 必填，空白回退为明确占位）
   * @return 渲染后的 XML 字符串
   */
  public static String render(
      UUID childThreadId,
      String agent,
      ThreadJoinOutcome outcome,
      String prompt,
      String report,
      String partialResult,
      String error) {
    Objects.requireNonNull(childThreadId, "childThreadId");
    Objects.requireNonNull(outcome, "outcome");
    requireText(agent, "agent");
    requireText(prompt, "prompt");
    StringBuilder message = new StringBuilder();
    message
        .append("<subagent_result thread_id=\"")
        .append(escapeAttribute(childThreadId.toString()))
        .append("\" agent=\"")
        .append(escapeAttribute(agent))
        .append("\" state=\"")
        .append(escapeAttribute(outcome.wireName()))
        .append("\">\n")
        .append(PROMPT_REFERENCE_NOTE)
        .append("\n<task>\n")
        .append(escapeText(prompt))
        .append("\n</task>\n");
    if (outcome == ThreadJoinOutcome.COMPLETED) {
      message
          .append("<result>\n")
          .append(escapeText(reportOrPlaceholder(report)))
          .append("\n</result>\n");
    } else {
      message
          .append("<error>\n")
          .append(escapeText(errorOrPlaceholder(error)))
          .append("\n</error>\n");
      if (partialResult != null && !partialResult.isBlank()) {
        message
            .append("<partial_result>\n")
            .append(escapeText(partialResult))
            .append("\n</partial_result>\n");
      }
      message
          .append("<resume>\n")
          .append(escapeText(resumeHint(childThreadId, agent)))
          .append("\n</resume>\n");
    }
    message.append("</subagent_result>");
    return message.toString();
  }

  /** 失败/取消的恢复提示：给出保留的 thread_id 与具体的 task(thread_id, subagent_type, prompt) 继续参数。 */
  public static String resumeHint(UUID childThreadId, String agent) {
    Objects.requireNonNull(childThreadId, "childThreadId");
    requireText(agent, "agent");
    return RESUME_HINT_PREFIX
        + " Resume it with task(thread_id=\""
        + childThreadId
        + "\", subagent_type=\""
        + agent
        + "\", prompt=\"...\").";
  }

  /** 完成报告缺失或全空白时的明确占位，避免静默空白。 */
  public static String reportOrPlaceholder(String report) {
    return report == null || report.isBlank() ? "(no textual report produced)" : report;
  }

  /** 失败原因缺失或全空白时的明确占位，避免静默空白。 */
  public static String errorOrPlaceholder(String error) {
    return error == null || error.isBlank() ? "(no failure detail produced)" : error;
  }

  /**
   * 正文转义：处理 XML 五个预定义实体，并把回车显式编码为字符引用。
   *
   * <p>XML 解析器会把正文中的字面 CR 规范化为 LF，只有字符引用能逐字保留，长 prompt 与结果因此可往返解析。
   */
  public static String escapeText(String value) {
    Objects.requireNonNull(value, "value");
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&apos;");
        case '\r' -> escaped.append("&#13;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  /** 属性值转义：在正文转义基础上编码换行与制表符，避免属性值规范化把空白折叠为空格。 */
  public static String escapeAttribute(String value) {
    Objects.requireNonNull(value, "value");
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\n' -> escaped.append("&#10;");
        case '\t' -> escaped.append("&#9;");
        default -> escaped.append(escapeText(String.valueOf(c)));
      }
    }
    return escaped.toString();
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
