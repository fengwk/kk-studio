package fun.fengwk.kkstudio.core.harness.run.store;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeRunIdGenerator implements RunIdGenerator {
  @Override
  public long newRunId() {
    return AgentIdGenerator.nextHarnessRunId();
  }

  @Override
  public long newRunEventId() {
    return AgentIdGenerator.nextHarnessRunEventId();
  }

  @Override
  public long newSessionEntryId() {
    return AgentIdGenerator.nextHarnessSessionEntryId();
  }
}
