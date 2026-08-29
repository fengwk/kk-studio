package fun.fengwk.kkstudio.harness.environment;

/**
 * Environment workspace 的 canonical 相对 wire 路径校验器。
 *
 * <p>workspace path 是跨平台纯字符串契约，不依赖本地 {@code Path} 解析：{@code '.'} 单独出现表示 Environment Root；段一律以
 * {@code '/'} 分隔（任何位置的反斜杠都拒绝），拒绝 Windows drive 前缀（{@code C:/x}、{@code C:x}）与 absolute（不得以 {@code
 * '/'} 开头）、无空段、无 {@code '.'}/{@code '..'} 段、无 ISO 控制字符。越界判定由 daemon 在 canonicalize 时完成。
 *
 * <p>{@link EnvironmentBinding} 与 {@code DaemonDirectoryCodec} 共用同一套规则，避免两套路径语义。
 */
public final class EnvironmentWorkspacePath {

  /** workspace path 的 UTF-16 字符数上限（持久化列与 wire 契约共用）。 */
  public static final int MAX_LENGTH = 2048;

  private EnvironmentWorkspacePath() {}

  /**
   * 校验 canonical 相对 wire 路径并原样返回；{@code '.'} 单独出现表示 root，其余位置拒绝 {@code '.'}/{@code '..'} 段。
   *
   * @return 校验通过的原始路径文本
   * @throws IllegalArgumentException 路径形状非法时抛出
   */
  public static String requireCanonicalRelativePath(String path) {
    String value = requireNonBlank(path, "path");
    if (".".equals(value)) {
      return value;
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("path must be at most " + MAX_LENGTH + " characters");
    }
    if (value.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("path must use '/' separators, not '\\': " + value);
    }
    if (value.length() >= 2 && isAsciiLetter(value.charAt(0)) && value.charAt(1) == ':') {
      throw new IllegalArgumentException("path must not use a Windows drive prefix: " + value);
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("path must not contain control characters");
    }
    if (value.charAt(0) == '/') {
      throw new IllegalArgumentException("path must be relative to the environment root: " + value);
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isEmpty()) {
        throw new IllegalArgumentException("path must not contain empty segments: " + value);
      }
      if (".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException(
            "path must not contain '" + segment + "' segments: " + value);
      }
    }
    return value;
  }

  private static boolean isAsciiLetter(char character) {
    return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
