package fun.fengwk.kkstudio.harness.runtime.run;

/** 为新 Run 聚合生成单 Snowflake bigint 标识。 */
public interface RunIdGenerator {
  long newRunId();

  long newRunEventId();

  long newSessionEntryId();
}
