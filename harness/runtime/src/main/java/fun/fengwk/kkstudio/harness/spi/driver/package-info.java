/**
 * 具体执行后端接入 Runtime 的窄 Agent Driver 契约与中立投影。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.spi.driver.AgentDriver} 是唯一入口：按锁定 Thread 视图准备
 * Turn、推进已完成响应、投影中立活动/诊断与停止私有执行。
 *
 * <p>包内只放契约与中立事实：{@link fun.fengwk.kkstudio.harness.spi.driver.TurnResolution}、{@link
 * fun.fengwk.kkstudio.harness.spi.driver.CompactionResolution}、{@link
 * fun.fengwk.kkstudio.harness.spi.driver.DriverCommit} 与 {@link
 * fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState} 等。{@link
 * fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState#projection()} 返回的 generation
 * 投影属于公开快照，定义在 {@code fun.fengwk.kkstudio.harness.api.generation}。注册、路由与未安装 Driver 替身属于 Runtime
 * 内部实现，见 {@code fun.fengwk.kkstudio.harness.runtime.driver}。
 */
package fun.fengwk.kkstudio.harness.spi.driver;
