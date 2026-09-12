package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 目标 workdir 的纯词法路径运算：不读取 Backend 本机工作目录、HOME 或文件系统。
 *
 * <p>workdir 属于目标 Daemon 文件系统，可能是 Unix 或 Windows 形态；本类只做分隔符归一（统一为 {@code '/'}）、词法 {@code .}/{@code
 * ..} 折叠与词法 relativize；跨 Windows drive/UNC root 时保留 target 的 root-qualified 绝对坐标。
 *
 * <p>所有输出都使用 {@code '/'} 分隔，供 gitignore pattern 匹配。
 */
final class LexicalTargetPath {

  private LexicalTargetPath() {}

  /**
   * 词法归一化一个绝对路径文本：分隔符统一为 {@code '/'}，折叠 {@code .} 与 {@code ..}（不越出 root），去掉多余尾部分隔符。
   *
   * @throws IllegalArgumentException 输入为空白或不是绝对路径
   */
  static String normalizeAbsolute(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    String unified = unify(value);
    Root root = Root.detect(unified);
    if (root == null) {
      throw new IllegalArgumentException("workdir must be an absolute path: " + value);
    }
    return root.prefix() + fold(root.rest());
  }

  /**
   * 把 target 词法投影为相对 base 的路径；两者都必须已通过 {@link #normalizeAbsolute}。
   *
   * <p>不在 base 之下时返回带 {@code ..} 的相对路径（用于越界事实表达）；跨 Windows root 时返回 target 的规范绝对文本，使默认 basename
   * 规则与显式 root-qualified 规则都能继续匹配。
   */
  static String relativize(String base, String target) {
    Root baseRoot = Root.detect(base);
    Root targetRoot = Root.detect(target);
    // 只比较 root 前缀：base 与 target 各自的余下路径段不同是可预期的，跨 root 才是不可达。
    if (baseRoot == null || targetRoot == null) {
      throw new IllegalArgumentException("path and effective workdir must be absolute");
    }
    if (!baseRoot.prefix().equals(targetRoot.prefix())) {
      return target;
    }
    String[] baseSegments = segments(baseRoot.rest());
    String[] targetSegments = segments(targetRoot.rest());
    int common = 0;
    while (common < baseSegments.length
        && common < targetSegments.length
        && baseSegments[common].equals(targetSegments[common])) {
      common++;
    }
    StringBuilder relative = new StringBuilder();
    for (int index = common; index < baseSegments.length; index++) {
      relative.append("../");
    }
    for (int index = common; index < targetSegments.length; index++) {
      relative.append(targetSegments[index]).append('/');
    }
    if (relative.length() == 0) {
      return ".";
    }
    if (relative.charAt(relative.length() - 1) == '/') {
      relative.setLength(relative.length() - 1);
    }
    return relative.toString();
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
