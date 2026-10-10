package fun.fengwk.kkstudio.harness.environment.server.terminal;

import java.util.Optional;
import java.util.UUID;

/**
 * environment-server 的窄 READY 路由读取端口：按 Environment 返回当前权威 owner 与 lease。
 *
 * <p>实现必须只依据数据库当前事实（{@code environment_connection} 的 {@code status = 'READY'} 且 {@code lease_until
 * > statement_timestamp()}）判定，绝不使用本进程 JVM 时间授权或本地缓存。没有当前 READY 路由时返回 {@link
 * Optional#empty()}；数据库不可用等失败必须以异常表达，调用方据此在真正递交前把操作判为确定未执行。
 *
 * <p>读取不修改任何表或租约写 SQL；它只服务 ShellGateway 选择转发目标，副作用准入仍由 owner 的既有 READY/PG 围栏承担。
 */
@FunctionalInterface
public interface EnvironmentTerminalRouteSource {

  /**
   * 读取 Environment 当前未过期的 READY owner/lease。
   *
   * @param environmentId 目标 Environment id
   * @return 当前 READY 路由，或没有 READY 路由时的空
   * @throws RuntimeException 数据库读取失败
   */
  Optional<EnvironmentTerminalRoute> resolveReadyRoute(UUID environmentId);
}
