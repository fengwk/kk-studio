package fun.fengwk.kkstudio.harness.runtime.usage;

/** 为账本生成独立 decimal PostgreSQL sequence id；不复用其它业务 id 语义。 */
public interface ModelUsageRecordIdGenerator {
  long newModelUsageRecordId();
}
