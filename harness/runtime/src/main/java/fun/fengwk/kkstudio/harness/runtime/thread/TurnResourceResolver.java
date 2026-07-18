package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;

/** 根据冻结 runtime config 解析 Turn 所需 provider/model/tools。 */
@FunctionalInterface
public interface TurnResourceResolver {
  TurnResources resolve(long sessionId, long threadId, AgentRuntimeConfig config);
}
