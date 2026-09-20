package fun.fengwk.kkstudio.platform.catalog.skill.git;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.util.List;

/**
 * Platform 拥有的 Git Skill cache：{@code <skill-cache-root>/<package>.git} 下的 bare repository。
 *
 * <p>cache 只物化人工确认或待校验的 exact commit，绝不解析 branch HEAD 作为发布内容：{@link #resolveBranchHead} 只观察远端
 * branch，{@link #ensureCommit} 只按调用方给定的 commit 补齐对象。仓库根目录一层子目录对应同名 Skill，每个 {@code <name>/SKILL.md}
 * 的 frontmatter 必须与目录名一致且 description 合法。
 */
public interface SkillGitCache {

  /**
   * 解析远端 branch 当前 HEAD（等价 {@code git ls-remote <url> <branch>}）；不写本地 cache。
   *
   * @return 40 或 64 位小写 hex object id
   * @throws SkillGitException 远端不可达、branch 不存在或解析结果不是完整 object id
   */
  String resolveBranchHead(String repositoryUrl, String branch);

  /**
   * 确保 {@code <cache-root>/<packageName>.git} 已含该 exact commit 的对象；缺失时只按该 commit fetch。
   *
   * @throws SkillGitException 仓库不可读、commit 不存在或 fetch 失败
   */
  void ensureCommit(String packageName, String repositoryUrl, String commit);

  /**
   * 扫描 commit 根目录一层的 {@code <name>/SKILL.md}，严格校验并按 {@code name} 升序返回 manifest。
   *
   * @throws SkillGitException commit 不存在、目录/符号链接越界、frontmatter 不可解析、name 与目录名不一致、description 非法或
   *     name 重复
   */
  List<SkillManifestEntry> scanManifest(String packageName, String commit);

  /**
   * 读取 commit 内某个仓库相对路径的文件字节。
   *
   * @throws SkillGitException 路径非法（绝对路径、{@code ..}、反斜杠、目录）、条目不是普通文件或文件不存在
   */
  byte[] readFile(String packageName, String commit, String path);
}
