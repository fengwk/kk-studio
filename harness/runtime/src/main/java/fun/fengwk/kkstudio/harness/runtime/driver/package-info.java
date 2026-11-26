/**
 * Runtime 内部的 Driver 装配与路由。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry} 集中管理组合根注册的全部 Driver
 * 并按执行选择路由，拒绝未注册的 Driver。Driver 契约与中立投影位于 {@code fun.fengwk.kkstudio.harness.spi.driver}。
 */
package fun.fengwk.kkstudio.harness.runtime.driver;
