/**
 * Runtime 内部的 Driver 装配与路由。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry} 集中管理组合根注册的全部 Driver
 * 并按执行选择路由；未安装的 Driver 由包内 {@code UninstalledAgentDriver} 承接，拒绝规划但仍可读取与停止。Driver 契约与中立投影位于 {@code
 * fun.fengwk.kkstudio.harness.spi.driver}。
 */
package fun.fengwk.kkstudio.harness.runtime.driver;
