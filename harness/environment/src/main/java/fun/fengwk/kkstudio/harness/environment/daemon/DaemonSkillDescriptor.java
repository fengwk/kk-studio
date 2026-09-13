package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个已发现 Skill 的版本化身份。
 *
 * <p>Skill 身份是 {@code (sourceId, name)}，加上 {@code contentRevision} 构成精确可加载的不可变版本。{@code
 * baseDirectory} 是宿主上的 canonical skill 目录，供模型定位 skill 内的相对资源；它绝不被当作任何工具的 workdir 默认值。{@code
 * baseDirectory} 接受 Unix 绝对路径、Windows 盘符绝对路径（{@code C:\dir}）与 Windows UNC 路径（{@code
 * \\server\share}），因为三者都是宿主上的绝对位置。
 */
public record DaemonSkillDescriptor(
    UUID sourceId,
    long sourceVersion,
    String name,
    String description,
    String baseDirectory,
    String contentRevision) {

  /** name 的最大字符数。 */
  public static final int MAX_NAME_CHARS = 128;

  /** description 的最大字符数。 */
  public static final int MAX_DESCRIPTION_CHARS = 1024;

  /** baseDirectory 的最大字符数。 */
  public static final int MAX_BASE_DIRECTORY_CHARS = 4096;

  /** contentRevision 必须是 64 位小写十六进制 SHA-256。 */
  public static final int CONTENT_REVISION_CHARS = 64;

  public DaemonSkillDescriptor {
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("sourceVersion must not be negative");
    }
    name = canonicalName(name);
    description = canonicalDescription(description);
    baseDirectory = canonicalBaseDirectory(baseDirectory);
    contentRevision = contentRevision(contentRevision);
  }

  /** 校验并返回 canonical skill 名：非空、单行、无控制字符或首尾空白，至多 {@link #MAX_NAME_CHARS} 个字符。 */
  public static String canonicalName(String value) {
    String name = canonicalText(value, "name", MAX_NAME_CHARS);
    if (name.codePoints().anyMatch(DaemonSkillDescriptor::isLineOrControl)) {
      throw new IllegalArgumentException("name must not contain line or control characters");
    }
    return name;
  }

  /** 校验并返回 canonical skill 描述：非空、无首尾空白，只允许 LF 换行且至多 {@link #MAX_DESCRIPTION_CHARS} 个字符。 */
  public static String canonicalDescription(String value) {
    String description = canonicalText(value, "description", MAX_DESCRIPTION_CHARS);
    if (description
        .codePoints()
        .anyMatch(codePoint -> codePoint != '\n' && isLineOrControl(codePoint))) {
      throw new IllegalArgumentException(
          "description must not contain control characters other than line feeds");
    }
    return description;
  }

  /** 校验并返回宿主上的绝对 skill 目录；相对路径、首尾空白与控制字符都被拒绝。 */
  public static String canonicalBaseDirectory(String value) {
    return absolutePathText(value);
  }

  /** 校验内容 revision：必须是小写十六进制 SHA-256 文本，拒绝空、长度错误与非十六进制字符。 */
  public static String contentRevision(String value) {
    if (value == null || value.length() != CONTENT_REVISION_CHARS) {
      throw new IllegalArgumentException("contentRevision must be a SHA-256 hex string");
    }
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
      if (!hex) {
        throw new IllegalArgumentException(
            "contentRevision must be a lowercase SHA-256 hex string");
      }
    }
    return value;
  }

  private static String absolutePathText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("baseDirectory must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("baseDirectory must not have surrounding whitespace");
    }
    if (value.length() > MAX_BASE_DIRECTORY_CHARS) {
      throw new IllegalArgumentException(
          "baseDirectory must be at most " + MAX_BASE_DIRECTORY_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("baseDirectory must not contain control characters");
    }
    if (!value.startsWith("/") && !isWindowsUncPath(value) && !hasWindowsDrivePrefix(value)) {
      throw new IllegalArgumentException("baseDirectory must be an absolute path");
    }
    return value;
  }

  /**
   * 判断文本是否是 canonical Windows UNC 基目录：{@code \\server\share...} 或 {@code //server/share...}。
   *
   * <p>UNC 与 Unix 绝对路径、盘符绝对路径一样承载宿主上的绝对位置，因此必须被接受；前两段必须是非空白 server 与 share， 避免把残缺的网络前缀当作目录。
   */
  private static boolean isWindowsUncPath(String value) {
    boolean backslashUnc = value.startsWith("\\\\");
    boolean slashUnc = value.startsWith("//");
    if (!backslashUnc && !slashUnc) {
      return false;
    }
    String[] segments = value.substring(2).split("[/\\\\]", -1);
    return segments.length >= 2 && !segments[0].isBlank() && !segments[1].isBlank();
  }

  private static boolean hasWindowsDrivePrefix(String value) {
    if (value.length() < 3) {
      return false;
    }
    char letter = value.charAt(0);
    boolean asciiLetter = (letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z');
    return asciiLetter
        && value.charAt(1) == ':'
        && (value.charAt(2) == '/' || value.charAt(2) == '\\');
  }

  private static String canonicalText(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not have surrounding whitespace");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
    }
    return value;
  }

  private static boolean isLineOrControl(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }
}
