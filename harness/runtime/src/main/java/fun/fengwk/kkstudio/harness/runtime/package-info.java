/**
 * Durable Agent Runtime 的领域边界。
 *
 * <p>本模块承载 Session Tree、Context、Durable Run、Model/Provider 契约、Tool Invocation 与权限策略； 只依赖
 * harness-kernel / harness-tool 与 Jackson，不依赖 Provider SDK、Spring、MyBatis 或 HTTP adapter。
 */
package fun.fengwk.kkstudio.harness.runtime;
