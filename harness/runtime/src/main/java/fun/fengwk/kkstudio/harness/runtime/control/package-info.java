/**
 * Durable run control queue：typed domain、冻结策略、USER message 序列化契约和持久化 port。
 *
 * <p>控制消息在数据库里是 steering/follow-up 的唯一可恢复事实源；policy 冻结、消息编码与状态
 * 转换在入库前定型，runtime 只读取并推进 status。
 */
package fun.fengwk.kkstudio.harness.runtime.control;
