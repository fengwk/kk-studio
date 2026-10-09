package fun.fengwk.kkstudio.platform.catalog.skill.git;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.util.List;

/**
 * Platform 拥有的 Git Skill cache：{@code <skill-cache-root>/<package>} 下的物化目录缓存。
 *
 * <p>单一路径：{@link #ensureCommit} 以临时 clone 检出并校验调用方给定的 exact commit，原子发布为物化目录；绝不解析 branch HEAD
 * 作为发布内容——{@link #resolveBranchHead} 只观察远端 branch。发布目录根一层子目录对应同名 Skill，每个 {@code <name>/SKILL.md} 的
 * frontmatter 必须与目录名一致且 description 合法。
 */
public interface SkillGitCache {

  /**
   * 解析远端 branch 当前 HEAD（等价 {@code git ls-remote <url> <branch>}）；不写本地 cache。
   *
   * @param token 私有仓库访问令牌；null 表示匿名访问。令牌只进入 HTTP authorization，绝不拼入 URL、日志或异常文本
   * @return 40 或 64 位小写 hex object id
   * @throws SkillGitException 远端不可达、权限被拒、branch 不存在或解析结果不是完整 object id
   */
  String resolveBranchHead(String repositoryUrl, String branch, String token);

  /**
   * 确保 {@code <skill-cache-root>/<packageName>} 已按该 exact commit 物化发布；缺失或指向其他 commit 时临时
   * clone、检出并校验后原子替换。
   *
   * @param token 私有仓库访问令牌；null 表示匿名访问。令牌只进入 HTTP authorization，绝不拼入 URL、日志或异常文本
   * @throws SkillGitException 仓库不可读、权限被拒、commit 不存在、条目非法或发布失败
   */
  void ensureCommit(String packageName, String repositoryUrl, String commit, String token);

  /**
   * 扫描已发布 commit 根目录一层的 {@code <name>/SKILL.md}，严格校验并按 {@code name} 升序返回 manifest。
   *
   * @throws SkillGitException commit 未发布、目录/符号链接越界、frontmatter 不可解析、name 与目录名不一致、description 非法或
   *     name 重复
   */
  List<SkillManifestEntry> scanManifest(String packageName, String commit);

  /**
   * 读取已发布 commit 内某个仓库相对路径的文件字节。
   *
   * @throws SkillGitException 路径非法（绝对路径、{@code ..}、反斜杠、目录）、条目不是普通文件或文件不存在
   */
  byte[] readFile(String packageName, String commit, String path);
}
