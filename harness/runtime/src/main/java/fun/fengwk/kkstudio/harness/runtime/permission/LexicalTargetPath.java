package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 目标绝对路径的纯词法运算：不读取 Backend 本机工作目录、HOME 或文件系统。
 *
 * <p>文件工具只接受绝对路径，因此 permission path 规则的匹配坐标是 filesystem-root 坐标：Unix 绝对路径去掉 root 前缀（{@code
 * /srv/proj/a.ts} → {@code srv/proj/a.ts}），Windows drive 与 UNC 绝对路径保留 root-qualified 形态（{@code
 * C:/proj/a.ts}、{@code //server/share/proj/a.ts}）。这样 gitignore 语义里无内部分隔符的 pattern 仍按任意层级匹配，带内部分隔符的
 * pattern 锚定 filesystem root。
 *
 * <p>本类只做分隔符归一（统一为 {@code '/'}）、词法 {@code .}/{@code ..} 折叠与 root 识别；所有输出都使用 {@code '/'} 分隔，供
 * gitignore pattern 匹配。非绝对形态（含 {@code kkstudio:}/{@code http:} 等 URI
 * scheme、drive-relative）不被当作路径坐标。
 */
final class LexicalTargetPath {

  private LexicalTargetPath() {}

  /** 该文本是否是受支持的绝对路径形态（Unix root、Windows drive 或 UNC）。 */
  static boolean isAbsolute(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return Root.detect(unify(value)) != null;
  }

  /**
   * 词法计算 filesystem-root 坐标：分隔符统一为 {@code '/'}，折叠 {@code .} 与 {@code ..}（不越出 root），Unix 去掉 root 前缀，
   * Windows drive/UNC 保留 root-qualified 形态。
   *
   * @throws IllegalArgumentException 输入为空白或不是绝对路径
   */
  static String permissionCoordinate(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    String unified = unify(value);
    Root root = Root.detect(unified);
    if (root == null) {
      throw new IllegalArgumentException("path must be an absolute path: " + value);
    }
    String folded = fold(root.rest());
    // Unix 只有一个 root：坐标相对 filesystem root。Windows drive/UNC 有多个 root：保留 root-qualified 前缀以免丢失盘符/主机。
    return "/".equals(root.prefix()) ? folded : root.prefix() + folded;
  }

  private static String unify(String value) {
    return value.indexOf('\\') < 0 ? value : value.replace('\\', '/');
  }

  private static String fold(String rest) {
    Deque<String> folded = new ArrayDeque<>();
    for (String segment : segments(rest)) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (!folded.isEmpty()) {
          folded.removeLast();
        }
        continue;
      }
      folded.addLast(segment);
    }
    return String.join("/", folded);
  }

  private static String[] segments(String value) {
    if (value == null || value.isEmpty()) {
      return new String[0];
    }
    return value.split("/", -1);
  }

  /** 绝对路径的 root 前缀：Unix 为 {@code "/"}，Windows 为 {@code C:/} 或 {@code //server/share/}。 */
  private record Root(String prefix, String rest) {

    private static Root detect(String unified) {
      if (unified.isEmpty()) {
        return null;
      }
      if (unified.startsWith("//")) {
        String remainder = unified.substring(2);
        String[] parts = remainder.split("/", -1);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
          return null;
        }
        String prefix = "//" + parts[0] + "/" + parts[1] + "/";
        StringBuilder rest = new StringBuilder();
        for (int index = 2; index < parts.length; index++) {
          rest.append(parts[index]).append('/');
        }
        if (rest.length() > 0) {
          rest.setLength(rest.length() - 1);
        }
        return new Root(prefix, rest.toString());
      }
      if (unified.length() >= 2
          && Character.isLetter(unified.charAt(0))
          && unified.charAt(1) == ':') {
        if (unified.length() == 2 || unified.charAt(2) != '/') {
          return null;
        }
        return new Root(
            Character.toUpperCase(unified.charAt(0)) + ":/",
            unified.length() > 3 ? unified.substring(3) : "");
      }
      if (unified.startsWith("/")) {
        return new Root("/", unified.length() > 1 ? unified.substring(1) : "");
      }
      return null;
    }
  }
}
