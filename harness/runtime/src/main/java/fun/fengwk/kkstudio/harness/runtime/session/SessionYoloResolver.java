package fun.fengwk.kkstudio.harness.runtime.session;

/** Root Session 创建时解析全局 defaultYolo。 */
@FunctionalInterface
public interface SessionYoloResolver {
  boolean defaultYolo();
}
