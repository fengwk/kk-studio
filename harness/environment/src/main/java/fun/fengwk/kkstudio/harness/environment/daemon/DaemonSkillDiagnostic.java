package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * 一次 Skill 来源扫描中的单个有界诊断。
 *
 * <p>诊断只描述“哪个位置发生了什么”，用于展示与排障；坏 Skill 形成诊断而不终止 Daemon。诊断文本绝不复述 SKILL.md 内容或任何凭据、 URL 等配置原值，且字符数有界。
 */
public record DaemonSkillDiagnostic(String location, String message) {

  /** location 的最大字符数。 */
  public static final int MAX_LOCATION_CHARS = 4096;

  /** message 的最大字符数。 */
  public static final int MAX_MESSAGE_CHARS = 512;

  public DaemonSkillDiagnostic {
    location = boundedText(location, "location", MAX_LOCATION_CHARS);
    message = boundedText(message, "message", MAX_MESSAGE_CHARS);
  }

  private static String boundedText(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (value.length() > maxLength) {
      return value.substring(0, maxLength);
    }
    return value;
  }
}
