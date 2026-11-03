/**
 * Builtin Driver 存储端口的 PostgreSQL 实现。
 *
 * <p>模型私有行与通用 Harness 行共享同一事务核心，包括连接、数据库时钟、锁序和首个数据库故障；深删除经 Driver 中立协作接口参与。
 */
package fun.fengwk.kkstudio.platform.harness.builtin.store;
