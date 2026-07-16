/**
 * Database adapters for the durable model usage ledger: Snowflake id generation, MyBatis mapper,
 * row model and the {@code MysqlModelUsageRecordStore} that implements the runtime port on top of
 * MySQL/H2.
 *
 * <p>账本写入必须以 {@code unique(assistant_entry_id)} 与 {@code unique(run_id, attempt, turn_index)}
 * 兜底幂等；任何 0/2 行结果必须以 {@link java.util.ConcurrentModificationException} 上抛，由事务边界统一回滚。
 * 本包不在此层引入任何事务/聚合/补算业务。
 */
package fun.fengwk.kkstudio.core.harness.usage.store;
