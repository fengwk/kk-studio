package fun.fengwk.kkstudio.core.harness.thread.store;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;

@Component
public class SnowflakeThreadIdGenerator implements ThreadIdGenerator {
  @Override
  public long newThreadId() {
    return AgentIdGenerator.nextHarnessThreadId();
  }

  @Override
  public long newThreadInputId() {
    return AgentIdGenerator.nextHarnessThreadInputId();
  }

  @Override
  public long newThreadEventId() {
    return AgentIdGenerator.nextHarnessThreadEventId();
  }

  @Override
  public long newSessionEntryId() {
    return AgentIdGenerator.nextHarnessSessionEntryId();
  }
}
