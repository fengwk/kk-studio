package fun.fengwk.kkstudio.harness.daemon.skill;

import java.util.Objects;

/**
 * 已安装技能包的元数据描述。
 *
 * @param packageName 技能包唯一标识
 * @param installedCommit 已安装的 Git commit SHA
 * @param localPath 已安装技能包在本地磁盘上的绝对规范化路径
 */
public record InstalledSkillPackage(String packageName, String installedCommit, String localPath) {

  public InstalledSkillPackage {
    Objects.requireNonNull(packageName, "packageName");
    Objects.requireNonNull(installedCommit, "installedCommit");
    Objects.requireNonNull(localPath, "localPath");
  }
}
