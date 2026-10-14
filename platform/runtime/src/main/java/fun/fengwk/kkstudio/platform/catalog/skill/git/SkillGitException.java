package fun.fengwk.kkstudio.platform.catalog.skill.git;

/** Git cache 操作失败的确定性异常：远端不可达、commit 不存在、manifest 不合法或仓库内容违反扫描契约。 */
public class SkillGitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 仓库拒绝匿名访问（HTTP 401/403）时使用的稳定错误码。 */
  public static final String CODE_AUTHENTICATION_FAILED = "GIT_AUTHENTICATION_FAILED";

  private final String code;

  public SkillGitException(String message) {
    super(message);
    this.code = null;
  }

  public SkillGitException(String message, Throwable cause) {
    super(message, cause);
    this.code = null;
  }

  public SkillGitException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** 稳定错误码；普通失败为 null。调用方据此产生确定性指导，而不解析自由文本。 */
  public String code() {
    return code;
  }

  public boolean authenticationFailed() {
    return CODE_AUTHENTICATION_FAILED.equals(code);
  }
}
