package fun.fengwk.kkstudio.harness.runtime.usage;

/** 为账本生成独立 Snowflake bigint 标识；不复用其它 namespace。 */
public interface ModelUsageRecordIdGenerator {
  long newModelUsageRecordId();
}
