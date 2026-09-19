package fun.fengwk.kkstudio.harness.builtin.skill;

/**
 * 按冻结身份精确加载 Skill 正文的端口。
 *
 * <p>实现必须精确匹配 {@code (packageName, packageVersion, name)}；找不到该三元组时抛出异常，绝不按名称回退到 当前 Skill
 * 版本。端口是同步的：正文加载不引入单独的异步超时通道。
 */
@FunctionalInterface
public interface SkillContentLoader {

  /**
   * 加载冻结的三元组正文。
   *
   * @throws IllegalArgumentException 缺失该三元组时
   */
  String load(String packageName, String packageVersion, String name);
}
