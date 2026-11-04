/**
 * 进程内提示型通知基础设施：与具体业务领域无关的订阅容器、版本推送与 PostgreSQL NOTIFY 失效分发基座。
 *
 * <p>Harness 的版本/失效订阅源、Canvas revision、Project 与 Environment 失效源都组合这里的 {@link
 * fun.fengwk.kkstudio.platform.notification.VersionNotificationHub} 或 {@link
 * fun.fengwk.kkstudio.platform.notification.NotificationInvalidationHub}。因此本包不携带任一领域的事实、channel 名或
 * payload 协议；这些由各生产方定义。
 */
package fun.fengwk.kkstudio.platform.notification;
