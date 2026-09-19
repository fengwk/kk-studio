package fun.fengwk.kkstudio.harness.common.skill;

import java.util.Objects;

/**
 * Platform 全局 Skill 目录的 canonical 文本契约。
 *
 * <p>Skill 身份是全局唯一的 {@code name}；{@code packageName}/{@code packageVersion} 唯一确定不可变 package 版本，
 * {@code contentRevision} 精确确定该 Skill 的内容版本。这些规则同时是持久化列约束、HTTP 边界与冻结 binding 的唯一事实，
 * 因此集中在这里，避免各处各写一套。
 */
public final class SkillNames {

  /** Skill / package name 的最大字符数。 */
  public static final int MAX_NAME_CHARS = 128;

  /** package version 的最大字符数。 */
  public static final int MAX_PACKAGE_VERSION_CHARS = 128;

  /** Skill description 的最大字符数。 */
  public static final int MAX_DESCRIPTION_CHARS = 1024;

  /** contentRevision / packageRevision 的 SHA-256 十六进制字符数。 */
  public static final int CONTENT_REVISION_CHARS = 64;

  /** 与既有模型可见短名契约一致的保留字符。 */
  private static final char[] FORBIDDEN_NAME_CHARS = {':', '/', '@', '\\'};

  private SkillNames() {}

  /** 校验并返回 canonical Skill 名：非空、无环绕空白、单行、无控制字符、≤{@value #MAX_NAME_CHARS} 且不含保留字符。 */
  public static String canonicalSkillName(String value) {
    String name = canonicalText(value, "name", MAX_NAME_CHARS);
    if (name.codePoints().anyMatch(SkillNames::isLineOrControl)) {
      throw new IllegalArgumentException("name must not contain line or control characters");
    }
    requireNoReservedChar(name, "name");
    return name;
  }

  /** 校验并返回 canonical package 名：与 {@link #canonicalSkillName} 同一规则（Skill 与 package 共享短名契约）。 */
  public static String canonicalPackageName(String value) {
    return canonicalSkillName(value);
  }

  /** 校验并返回 canonical package version：非空、无环绕空白、无控制字符、≤{@value #MAX_PACKAGE_VERSION_CHARS}。 */
  public static String canonicalPackageVersion(String value) {
    String version = canonicalText(value, "packageVersion", MAX_PACKAGE_VERSION_CHARS);
    if (version.codePoints().anyMatch(SkillNames::isLineOrControl)) {
      throw new IllegalArgumentException(
          "packageVersion must not contain line or control characters");
    }
    return version;
  }

  /** 校验并返回 canonical Skill 描述：非空、无环绕空白、只允许 LF 换行、≤{@value #MAX_DESCRIPTION_CHARS}。 */
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

  /** 校验并返回 Skill 正文：非空，且不得包含字符 NUL（正文长度不设人为上限）。 */
  public static String canonicalContent(String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("content must not be empty");
    }
    if (value.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("content must not contain NUL characters");
    }
    return value;
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

  private static void requireNoReservedChar(String value, String field) {
    for (char reserved : FORBIDDEN_NAME_CHARS) {
      if (value.indexOf(reserved) >= 0) {
        throw new IllegalArgumentException(
            field + " must not contain reserved character: " + reserved);
      }
    }
  }

  private static String canonicalText(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
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
