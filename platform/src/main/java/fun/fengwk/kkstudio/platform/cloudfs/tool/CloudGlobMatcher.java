package fun.fengwk.kkstudio.platform.cloudfs.tool;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.util.Objects;

/**
 * Cloud File System Segment-aware Glob 匹配器。
 *
 * <p>规范：
 *
 * <ul>
 *   <li>{@code *} 不跨越目录分隔符 {@code /}；
 *   <li>{@code **} 允许跨越目录；
 *   <li>{@code ?} 匹配单个非 {@code /} 字符；
 *   <li>若 pattern 中不含 {@code /}，匹配 basename；
 *   <li>若 pattern 中含有 {@code /}，匹配相对于搜索起点的相对路径。
 * </ul>
 */
public final class CloudGlobMatcher {

  private final String rawPattern;
  private final boolean pathPattern;
  private final Pattern compiledPattern;

  private CloudGlobMatcher(String rawPattern, boolean pathPattern, Pattern compiledPattern) {
    this.rawPattern = rawPattern;
    this.pathPattern = pathPattern;
    this.compiledPattern = compiledPattern;
  }

  public static CloudGlobMatcher compile(String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    if (pattern.isEmpty()) {
      throw new IllegalArgumentException("Glob pattern must not be empty");
    }
    boolean pathPattern = pattern.indexOf('/') >= 0;
    String regex = globToRegex(pattern);
    try {
      Pattern re2Pattern = Pattern.compile("^" + regex + "$");
      return new CloudGlobMatcher(pattern, pathPattern, re2Pattern);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid glob pattern");
    }
  }

  public boolean isPathPattern() {
    return pathPattern;
  }

  public String rawPattern() {
    return rawPattern;
  }

  /**
   * 匹配候选路径。
   *
   * @param relativePath 候选节点相对搜索起点的相对路径（无前导或尾随 /）
   * @param basename 候选节点的名称
   * @return 是否匹配
   */
  public boolean matches(String relativePath, String basename) {
    String target = pathPattern ? relativePath : basename;
    if (target == null) {
      return false;
    }
    return compiledPattern.matcher(target).matches();
  }

  static String globToRegex(String glob) {
    StringBuilder sb = new StringBuilder();
    int len = glob.length();
    for (int i = 0; i < len; i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < len && glob.charAt(i + 1) == '*') {
          boolean hasPrevSlash = i > 0 && glob.charAt(i - 1) == '/';
          boolean hasNextSlash = i + 2 < len && glob.charAt(i + 2) == '/';
          if (hasPrevSlash && hasNextSlash) {
            sb.append("(?:.+/)?");
            i += 2;
          } else if (i == 0 && hasNextSlash) {
            sb.append("(?:.*/)?");
            i += 2;
          } else {
            sb.append(".*");
            i++;
          }
        } else {
          sb.append("[^/]*");
        }
      } else if (c == '?') {
        sb.append("[^/]");
      } else if (c == '\\') {
        if (i + 1 < len) {
          char next = glob.charAt(++i);
          sb.append("\\").append(next);
        } else {
          sb.append("\\\\");
        }
      } else if (".+()[]{}^$|".indexOf(c) >= 0) {
        sb.append('\\').append(c);
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
