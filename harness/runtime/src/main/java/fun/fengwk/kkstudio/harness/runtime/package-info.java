/**
 * Durable Agent Runtime 的领域边界。
 *
 * <p>本模块承载 Session Tree、Entry/Thread command 与 reconcile 协调、Model/Provider 契约（{@code
 * harness.runtime.model} 包）、Tool Invocation、Interaction 与权限策略；通过 feature-local
 * transaction/config/execution SPI 反转外部能力。只依赖 harness-tool 与 Jackson，不依赖 Provider
 * SDK、Spring、MyBatis 或 HTTP adapter。
 */
package fun.fengwk.kkstudio.harness.runtime;
