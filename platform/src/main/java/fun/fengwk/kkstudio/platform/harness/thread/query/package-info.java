/**
 * Thread 的只读诊断查询：在单次不可变 snapshot 或一次 Entry 读取上现算结构化投影，不写库、不做 CAS、不触发同步。
 *
 * <p>费用投影只消费 durable 记录的真实用量与当前 catalog 价格，因此历史永远不会被价格快照污染。
 */
package fun.fengwk.kkstudio.platform.harness.thread.query;
