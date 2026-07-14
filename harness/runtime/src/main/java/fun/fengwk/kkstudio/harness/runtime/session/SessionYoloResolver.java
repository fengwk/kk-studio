package fun.fengwk.kkstudio.harness.runtime.session;

/** Root Session 创建时解析 Workspace defaultYolo。 */
@FunctionalInterface
public interface SessionYoloResolver {
  boolean defaultYolo(long workspaceId);
}
