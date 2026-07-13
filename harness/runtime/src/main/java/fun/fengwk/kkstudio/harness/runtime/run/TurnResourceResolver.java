package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;

/** 按 Session 生效配置解析 ModelProvider、ModelDescriptor、Variant 与工具描述。 */
@FunctionalInterface
public interface TurnResourceResolver {
  TurnResources resolve(long sessionId, AgentRuntimeConfig config);
}
