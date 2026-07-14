package fun.fengwk.kkstudio.core.harness.tool.store;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationIdGenerator;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeToolInvocationIdGenerator implements ToolInvocationIdGenerator {
  @Override
  public long newInvocationId() {
    return AgentIdGenerator.nextToolInvocationId();
  }
}
