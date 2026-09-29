package fun.fengwk.kkstudio.harness.daemon.coding;

import java.util.List;

/**
 * Windows 命令行拼装：把已经解析好的 {@code argv} 还原成 {@code CreateProcessW} 需要的单个命令行字符串。
 *
 * <p>Windows 没有 {@code argv} 这种原生表示，子进程按 MSVC 运行时与 {@code CommandLineToArgvW} 的规则重新切分命令行 （见
 * MSDN「Everyone quotes command line arguments the wrong way」）。拼装规则因此必须是：
 *
 * <ul>
 *   <li>空参数写成 {@code ""}；
 *   <li>不含空白与双引号的参数原样输出，不做任何加法；
 *   <li>反斜杠只在双引号前成对转义，参数尾部的反斜杠必须加倍，否则会吃掉紧随其后的结束引号。
 * </ul>
 *
 * <p>纯字符串变换，不触碰任何原生 API，因此可以在 Linux 上直接验证。
 */
final class WindowsCommandLine {

  private WindowsCommandLine() {}

  /** 把 argv 拼成一条命令行；参数之间只用单个空格分隔。 */
  static String join(List<String> arguments) {
    StringBuilder command = new StringBuilder();
    for (int index = 0; index < arguments.size(); index++) {
      if (index > 0) {
        command.append(' ');
      }
      appendQuoted(arguments.get(index), command);
    }
    return command.toString();
  }

  /** 按 MSVC 规则追加一个参数；空参数必须写成一对空引号，否则会被当成「没有这个参数」。 */
  static void appendQuoted(String argument, StringBuilder target) {
    if (argument.isEmpty() || requiresQuoting(argument)) {
      target.append('"');
      int backslashes = 0;
      for (int index = 0; index < argument.length(); index++) {
        char character = argument.charAt(index);
        if (character == '\\') {
          backslashes++;
          continue;
        }
        if (character == '"') {
          // 引号前的反斜杠加倍后再补一个，用来转义这个引号本身。
          appendBackslashes(target, backslashes * 2 + 1);
          target.append('"');
          backslashes = 0;
          continue;
        }
        appendBackslashes(target, backslashes);
        backslashes = 0;
        target.append(character);
      }
      // 尾部反斜杠会紧邻结束引号，必须加倍。
      appendBackslashes(target, backslashes * 2);
      target.append('"');
      return;
    }
    target.append(argument);
  }

  private static boolean requiresQuoting(String argument) {
    for (int index = 0; index < argument.length(); index++) {
      char character = argument.charAt(index);
      if (character == ' ' || character == '\t' || character == '"') {
        return true;
      }
    }
    return false;
  }

  private static void appendBackslashes(StringBuilder target, int count) {
    for (int index = 0; index < count; index++) {
      target.append('\\');
    }
  }
}
