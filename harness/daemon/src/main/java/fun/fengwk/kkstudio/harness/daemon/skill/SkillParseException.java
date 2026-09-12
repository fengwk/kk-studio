package fun.fengwk.kkstudio.harness.daemon.skill;

/** SKILL.md 不可用时的类型化失败：{@code reason} 是可进入有界诊断的短说明，绝不复述正文内容。 */
final class SkillParseException extends IllegalArgumentException {

  private final String reason;

  SkillParseException(String reason) {
    super(reason);
    this.reason = reason;
  }

  /** 供有界诊断使用的短原因。 */
  String reason() {
    return reason;
  }
}
