package fun.fengwk.kkstudio.platform.catalog.skill;

import java.util.regex.Pattern;

/**
 * Platform 全局 Skill 目录的 canonical 文本契约。
 *
 * <p>Skill 身份是全局唯一的 {@code name}；{@code packageName}/{@code packageVersion} 与 {@code name} 共同构成
 * 内容的三元组身份。这些规则同时是持久化列约束、HTTP 边界与冻结 binding 的唯一事实，因此集中在这里，避免各处各写一套。
 */
public final class SkillNames {

  /** Skill / package name 的最大字符数。 */
  public static final int MAX_NAME_CHARS = 64;

  /** Skill description 的最大字符数。 */
  public static final int MAX_DESCRIPTION_CHARS = 1024;

  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  private SkillNames() {}

  /** 校验 Agent Skills 短名：小写字母和数字以单个连字符分隔，长度 1–64。 */
  public static String canonicalSkillName(String value) {
    if (value == null || value.length() > MAX_NAME_CHARS || !NAME.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "name must be 1-64 lowercase letters or digits separated by single hyphens");
    }
    return value;
  }

  /** 校验并返回 canonical package 名：与 {@link #canonicalSkillName} 同一规则（Skill 与 package 共享短名契约）。 */
  public static String canonicalPackageName(String value) {
    return canonicalSkillName(value);
  }

  /** 去掉环绕空白并校验 Skill 描述：非空、≤{@value #MAX_DESCRIPTION_CHARS} 字符。 */
  public static String canonicalDescription(String value) {
    if (value == null) {
      throw new IllegalArgumentException("description must not be null");
    }
    String description = value.strip();
    if (description.isEmpty()
        || description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CHARS) {
      throw new IllegalArgumentException(
          "description must be between 1 and " + MAX_DESCRIPTION_CHARS + " characters");
    }
    if (description.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("description must not contain NUL");
    }
    return description;
  }
}
