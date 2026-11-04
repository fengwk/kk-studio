/**
 * Harness 公开变更订阅的平台实现：Thread version、执行树与待处理交互。
 *
 * <p>写入口只在事务内投递有损失效提示；本包解析 payload、进程内定点分发或广播 resync，并实现 {@code harness.api} 的消费契约。 统一 LISTEN
 * 循环由组合根持有，本包只给出 {@link
 * fun.fengwk.kkstudio.platform.harness.notification.HarnessNotificationBinding}，不创建连接或线程，也不复制持久化事实。
 */
package fun.fengwk.kkstudio.platform.harness.notification;
