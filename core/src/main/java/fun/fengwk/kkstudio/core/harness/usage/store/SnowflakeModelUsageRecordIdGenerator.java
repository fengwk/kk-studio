package fun.fengwk.kkstudio.core.harness.usage.store;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordIdGenerator;

/** 通过独立 Snowflake namespace 为账本生成主键；不复用 Run/control/其它 namespace。 */
@Component
public class SnowflakeModelUsageRecordIdGenerator implements ModelUsageRecordIdGenerator {

  @Override
  public long newModelUsageRecordId() {
    return AgentIdGenerator.nextModelUsageRecordId();
  }
}
