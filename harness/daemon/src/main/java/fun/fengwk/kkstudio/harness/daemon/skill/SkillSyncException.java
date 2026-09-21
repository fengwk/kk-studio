package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;

import java.util.Objects;

/** Skill 同步失败异常：携带稳定的错误码与脱敏后的固定错误文本。 */
public class SkillSyncException extends RuntimeException {

  private final String code;

  public SkillSyncException(String code, String message) {
    super(Objects.requireNonNull(message, "message"));
    this.code = EnvironmentCapabilityResultCodes.requireCode(code);
  }

  public SkillSyncException(String code, String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.code = EnvironmentCapabilityResultCodes.requireCode(code);
  }

  public String code() {
    return code;
  }
}
