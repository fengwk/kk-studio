package fun.fengwk.kkstudio.harness.runtime.control;

/** 为控制消息生成独立 Snowflake bigint 标识；不复用 Run id namespace。 */
public interface RunControlIdGenerator {
  long newControlMessageId();
}