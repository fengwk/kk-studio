package fun.fengwk.kkstudio.platform.harness.tool.permission;

/** 命令文本的归一化与位置判定：三个扫描器共用同一套预处理，避免各自复制转义/续行规则。 */
final class BashCommandText {

  private BashCommandText() {}

  /** 统一换行并把续行连接成空格，使后续扫描只需处理 {@code \n}。 */
  static String normalize(String command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }
    return command.replace("\r\n", "\n").replace('\r', '\n').replace("\\\n", " ");
  }

  /** {@code $(} 才是命令替换；{@code $((} 是算术展开，不属于替换起点。 */
  static boolean isCommandSubstitutionStart(String value, int index) {
    return index + 1 < value.length()
        && value.charAt(index) == '$'
        && value.charAt(index + 1) == '('
        && (index + 2 >= value.length() || value.charAt(index + 2) != '(');
  }

  /** heredoc 正文是否含会被 Shell 展开的命令替换（含反引号）。 */
  static boolean hasExpandableCommandSubstitution(String value) {
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '\\' && i + 1 < value.length()) {
        i++;
      } else if (isCommandSubstitutionStart(value, i) || ch == '`') {
        return true;
      }
    }
    return false;
  }

  /** {@code #} 只有在词首或空白之后才开始注释。 */
  static boolean isCommentStart(CharSequence current) {
    return current.isEmpty() || Character.isWhitespace(current.charAt(current.length() - 1));
  }
}
