/**
 * Database adapters for the durable run control queue: Snowflake id generation, MyBatis mapper, row
 * model and the {@code MysqlRunControlMessageStore} that implements the runtime port on top of
 * MySQL/H2.
 *
 * <p>所有 CAS 状态迁移必须以 {@code where status='PENDING'} 为前置条件，查询统一 {@code order by id asc}；不在此层引入任何
 * worker/事务消费/abort 业务。
 */
package fun.fengwk.kkstudio.core.harness.control.store;
