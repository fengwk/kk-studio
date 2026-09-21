package fun.fengwk.kkstudio.platform.environment.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;

import java.util.Objects;

/**
 * 模型可见 Skill 文件路径的选择器。
 *
 * <p>只有选定 Environment 的 {@code skill_state} 明确记录「该 Package 的 currentCommit 已精确安装」时才注入 Daemon
 * 上的本地稳定路径；其余一切情况（未选环境、从未同步、同步失败、提交不一致、本地路径缺失）都必须回退 platform URI。
 * 本地路径只是可重建的缓存事实，除「精确安装」之外不表达任何其它语义。
 */
@Component
public class SkillPromptPathResolver {

  /** Platform 托管的 skill 资源前缀：Daemon 不可用时模型仍然可以经由 platform read 读取。 */
  public static final String PLATFORM_SKILLS_PREFIX = "kkstudio:/skills/";

  private final EnvironmentRegistry environmentRegistry;

  public SkillPromptPathResolver(EnvironmentRegistry environmentRegistry) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  /**
   * 解析单个 skill 的 SKILL.md 路径。
   *
   * @param environmentId 当前 branch 选择的 Environment；null 表示未选择环境
   * @param packageName Skill Package 名
   * @param skillName Package 内的 skill 名
   * @param currentCommit Package 的权威 currentCommit
   * @return 精确安装成立时返回 Daemon 本地稳定路径，否则返回 platform URI
   */
  public String resolve(
      EnvironmentId environmentId, String packageName, String skillName, String currentCommit) {
    Objects.requireNonNull(packageName, "packageName");
    Objects.requireNonNull(skillName, "skillName");
    if (environmentId != null) {
      EnvironmentConnection connection = environmentRegistry.find(environmentId).orElse(null);
      if (connection != null) {
        String root = connection.installedSkillRoot(packageName, currentCommit).orElse(null);
        if (root != null) {
          return root + "/" + skillName + "/SKILL.md";
        }
      }
    }
    return platformPath(packageName, skillName);
  }

  /** platform 托管的稳定 skill 资源路径。 */
  public static String platformPath(String packageName, String skillName) {
    return PLATFORM_SKILLS_PREFIX + packageName + "/" + skillName + "/SKILL.md";
  }
}
