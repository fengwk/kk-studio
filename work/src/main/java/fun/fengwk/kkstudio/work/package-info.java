/**
 * Durable Work：全部后台调度共用的 PostgreSQL mailbox。
 *
 * <p>{@code durable_work} 每个 (targetType, targetId) 至多一行。请求合并 due 并递增 wakeVersion；claim 用 {@code
 * FOR UPDATE SKIP LOCKED} 选出已到期且无有效租约的行；完成、重排与续租都以调用方 lease token 围栏。due 与租约是否有效一律以数据库时钟判定，
 * 调用方只提供相对 {@link java.time.Duration}，因此多节点时钟偏差不会提前抢占别人的租约。
 *
 * <p>行变为「到期且无有效租约」时，在同一事务内向 {@link fun.fengwk.kkstudio.work.DurableWorkQueue#CHANNEL} 发送 payload 为
 * targetType 的提示；提示只降低延迟，丢失时由各 dispatcher 的周期轮询恢复。
 */
package fun.fengwk.kkstudio.work;
