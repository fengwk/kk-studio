package fun.fengwk.kkstudio.platform.catalog.skill;

import java.time.Instant;

/**
 * {@code skill_package_git_source} 行：Git 渠道 Skill Package 的仓库事实。
 *
 * <p>{@code repositoryUrl} 一经创建不可修改，{@code branch} 只用于检查候选更新；{@code headCommit} 是最近一次检查到的 branch
 * HEAD， 发布该 commit 后与当前版本一致。{@code token} 是私有仓库访问令牌，null 表示匿名访问；它绝不回显、绝不进入日志、模型上下文或 Daemon，因此
 * {@link #toString()} 不输出它。
 */
public record SkillPackageGitSource(
    String repositoryUrl,
    String branch,
    String token,
    String headCommit,
    Instant checkedAt,
    String checkError) {

  @Override
  public String toString() {
    return "SkillPackageGitSource[repositoryUrl="
        + repositoryUrl
        + ", branch="
        + branch
        + ", headCommit="
        + headCommit
        + ", checkedAt="
        + checkedAt
        + ", checkError="
        + checkError
        + "]";
  }
}
