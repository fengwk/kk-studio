package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;

/**
 * 从 Thread 当前状态解析 Turn 运行时配置。
 *
 * <p>实现应加载当前 AgentDefinition 的 prompt/tools/skills/policy，并与 Thread 的 model/variant/yolo 组合。
 */
@FunctionalInterface
public interface ThreadRuntimeConfigResolver {
  AgentRuntimeConfig resolve(AgentThread thread);
}
