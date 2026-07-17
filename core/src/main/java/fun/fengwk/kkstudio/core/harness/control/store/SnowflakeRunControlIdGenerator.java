package fun.fengwk.kkstudio.core.harness.control.store;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlIdGenerator;

/** 通过独立 Snowflake namespace 为 control 消息生成主键，不复用 Run id namespace。 */
@Component
public class SnowflakeRunControlIdGenerator implements RunControlIdGenerator {

  @Override
  public long newControlMessageId() {
    return AgentIdGenerator.nextHarnessRunControlMessageId();
  }
}
