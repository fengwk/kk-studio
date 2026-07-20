package fun.fengwk.kkstudio.core.harness.thread.resource;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.session.HarnessAgentDefinitionSupport;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeConfigResolver;

import java.util.Objects;

/** Thread 状态 + 当前 AgentDefinition 装载 Turn 运行时配置。 */
@Component
public final class DatabaseThreadRuntimeConfigResolver implements ThreadRuntimeConfigResolver {
  private final HarnessAgentDefinitionSupport agentDefinitionSupport;

  public DatabaseThreadRuntimeConfigResolver(HarnessAgentDefinitionSupport agentDefinitionSupport) {
    this.agentDefinitionSupport =
        Objects.requireNonNull(agentDefinitionSupport, "agentDefinitionSupport");
  }

  @Override
  public AgentRuntimeConfig resolve(AgentThread thread) {
    return agentDefinitionSupport.runtimeConfig(Objects.requireNonNull(thread, "thread"));
  }
}
