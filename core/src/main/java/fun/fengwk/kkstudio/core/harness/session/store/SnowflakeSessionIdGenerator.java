package fun.fengwk.kkstudio.core.harness.session.store;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.session.SessionIdGenerator;

/** 为 Runtime 提供单 Snowflake bigint Session/Entry 标识。 */
@Component
public class SnowflakeSessionIdGenerator implements SessionIdGenerator {
  @Override
  public long newSessionId() {
    return AgentIdGenerator.nextHarnessSessionId();
  }

  @Override
  public long newEntryId() {
    return AgentIdGenerator.nextHarnessSessionEntryId();
  }
}
