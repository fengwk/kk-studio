package fun.fengwk.kkstudio.platform.catalog.skill.git;

/** Git cache 操作失败的确定性异常：远端不可达、commit 不存在、manifest 不合法或仓库内容违反扫描契约。 */
public class SkillGitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SkillGitException(String message) {
    super(message);
  }

  public SkillGitException(String message, Throwable cause) {
    super(message, cause);
  }
}
