package fun.fengwk.kkstudio.platform.catalog.skill.git;

import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;

/**
 * 服务端 Git 访问：检查 branch HEAD，并把一个 exact commit 直接构建成规范化技能归档。
 *
 * <p>没有本地长期缓存：发布时按 commit 浅拉取到一次性的临时仓库，直接遍历 Git 对象树读取内容，不检出工作区；归档构建完成后立即删除临时仓库。 {@link
 * #resolveBranchHead} 只观察远端 branch，是唯一可以发布的新 commit 来源——发布只能使用检查时看到的 commit。
 */
public interface SkillGitRepository {

  /**
   * 解析远端 branch 当前 HEAD（等价 {@code git ls-remote <url> <branch>}）；不下载内容。
   *
   * @param token 私有仓库访问令牌；null 表示匿名访问。令牌只进入 HTTP authorization，绝不拼入 URL、日志或异常文本
   * @return 40 或 64 位小写 hex object id
   * @throws SkillGitException 远端不可达、权限被拒、branch 不存在或解析结果不是完整 object id
   */
  String resolveBranchHead(String repositoryUrl, String branch, String token);

  /**
   * 以 depth 1 浅拉取该 exact commit，遍历对象树并构建规范化归档。
   *
   * <p>读取任何 blob 之前都先按对象元信息检查单文件与累计大小上限；调用方必须在发布前确认该 commit 正是最近一次检查观察到的 HEAD。
   *
   * @param repositoryUrl 仓库 URL
   * @param commit 40 或 64 位小写 hex object id
   * @param token 私有仓库访问令牌；null 表示匿名访问
   * @return 规范化 ZIP、Skill 清单、被忽略的目录与归档条目索引
   * @throws SkillGitException 仓库不可读、权限被拒、commit 不存在或对象树内容非法
   */
  SkillArchiveBuilder.Result buildArchive(String repositoryUrl, String commit, String token);
}
