package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Harness 名称（Session / Thread 显示名）的集中规范化与派生工具，Spring-free 的纯字符串工具。
 *
 * <p>所有手工名称与构造器入参统一经 {@link #normalize}：先把任意 Unicode 空白折叠为单个空格、再去掉首尾空格；结果必须非空且至多 {@value
 * #MAX_CODE_POINTS} 个 Unicode 码点，超长直接抛 {@link IllegalArgumentException}（不截断，长度非法是调用方错误）。 {@link
 * #sessionNameFromUserText} 是自动默认名路径，允许任意长文本并截前 {@value #SESSION_NAME_TEXT_CODE_POINTS} 个码点（无省略号）。
 * {@link #defaultSessionName} / {@link #defaultThreadName} 派生「无文本时的回退名」。
 */
public final class Names {

  /**
   * Session / Thread 手工名称的码点上限（与 {@code harness_session.name} / {@code harness_thread.name} 的
   * varchar(256) 一致）。
   */
  public static final int MAX_CODE_POINTS = 256;

  /** Session 默认名派生时使用的文本码点上限（前 40 个码点，无省略号）。 */
  public static final int SESSION_NAME_TEXT_CODE_POINTS = 40;

  /** 规范 UUID 文本（含连字符）的前缀截取长度，用于「session-/branch- + UUID 前 8 位」回退名。 */
  private static final int UUID_PREFIX_LENGTH = 8;

  private static final String ROOT_THREAD_NAME = "main";

  /** 任意 Unicode whitespace 的连续段（与 trim 的空白定义一致的折叠单位）。 */
  private static final Pattern UNICODE_WHITESPACE_RUNS =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private Names() {}

  /**
   * 规范化并校验名称：把任意 Unicode 空白折叠为单空格（先折叠再去首尾，避免 NBSP 等残留），结果必须非空且至多 {@value #MAX_CODE_POINTS} 个
   * Unicode 码点；超过上限抛 {@link IllegalArgumentException}，绝不截断。
   *
   * @param name 原始名称，不能为 null
   * @return 规范化后的单行名称（非空、无首尾空白）
   */
  public static String normalize(String name) {
    Objects.requireNonNull(name, "name");
    String collapsed = collapseWhitespace(name);
    if (collapsed.isEmpty()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (collapsed.codePointCount(0, collapsed.length()) > MAX_CODE_POINTS) {
      throw new IllegalArgumentException(
          "name must not exceed " + MAX_CODE_POINTS + " Unicode code points");
    }
    return collapsed;
  }

  /** 派生新 Session 的默认回退名：{@code session-} + canonical session UUID 前 8 位（无文本内容时使用）。 */
  public static String defaultSessionName(UUID sessionId) {
    return "session-" + uuidPrefix(sessionId);
  }

  /** 派生新 Thread 的默认回退名：{@code branch-} + canonical thread UUID 前 8 位（无文本内容时使用）。 */
  public static String defaultThreadName(UUID threadId) {
    return "branch-" + uuidPrefix(threadId);
  }

  /** 新创建 Thread 的 ROOT Thread 固定名称。 */
  public static String rootThreadName() {
    return ROOT_THREAD_NAME;
  }

  /**
   * 从初始 terminal user-like message 的首个文本内容派生 Session 默认名（自动命名路径，不设 256 上限）：折叠为单行后取前 {@value
   * #SESSION_NAME_TEXT_CODE_POINTS} 个 Unicode 码点（无省略号）；空白文本返回 null（由调用方回退）。
   */
  public static String sessionNameFromUserText(String firstText) {
    if (firstText == null) {
      return null;
    }
    String collapsed = collapseWhitespace(firstText);
    if (collapsed.isEmpty()) {
      return null;
    }
    return truncateCodePoints(collapsed, SESSION_NAME_TEXT_CODE_POINTS);
  }

  /** 折叠任意 Unicode 空白为单空格后去掉首尾空格；结果可能为空串（调用方决定空串语义）。 */
  private static String collapseWhitespace(String value) {
    String collapsed = UNICODE_WHITESPACE_RUNS.matcher(value).replaceAll(" ");
    int begin = 0;
    int end = collapsed.length();
    while (begin < end && collapsed.charAt(begin) == ' ') {
      begin++;
    }
    while (end > begin && collapsed.charAt(end - 1) == ' ') {
      end--;
    }
    return collapsed.substring(begin, end);
  }

  private static String truncateCodePoints(String value, int maxCodePoints) {
    return value
        .codePoints()
        .limit(maxCodePoints)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static String uuidPrefix(UUID id) {
    Objects.requireNonNull(id, "id");
    return id.toString().substring(0, UUID_PREFIX_LENGTH);
  }
}
