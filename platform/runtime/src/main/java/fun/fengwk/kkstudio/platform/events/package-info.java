/**
 * 各产品模块共享的进程内事件订阅原语。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.notification.SubscriptionFanOut} 只提供 UUID
 * 定点/全局注册、幂等注销与消费者隔离投递，{@link fun.fengwk.kkstudio.platform.events.SourceSubscribed} 只描述「ack 游标 +
 * 释放句柄」的订阅结果；两者都不携带领域事件语义、不做持久化，也不接管 LISTEN 连接。Thread/Canvas version 与提示型失效 Hub 用它们实现各自的通道与恢复策略。
 */
package fun.fengwk.kkstudio.platform.events;
