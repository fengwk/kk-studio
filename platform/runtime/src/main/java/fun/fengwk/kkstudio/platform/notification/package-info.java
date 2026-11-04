/**
 * 进程内提示型通知基础设施：与具体业务领域无关的订阅容器和 PostgreSQL NOTIFY 失效分发基座。
 *
 * <p>Harness 的版本/失效订阅源（{@code platform.harness.notification}）与 web 的画布、环境失效 hub 共用这里的容器与解析语义，因此
 * 它不携带任一领域的事实、channel 名或持久化约定。
 */
package fun.fengwk.kkstudio.platform.notification;
