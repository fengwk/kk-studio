/**
 * Durable Agent Runtime 的领域边界。
 *
 * <p>本模块承载 Session Tree、Entry/Thread 协调、Model/Provider 契约（{@code harness.model} 包）、Tool
 * Invocation、Interaction 与权限策略；只依赖 harness-tool 与 Jackson，不依赖 Provider SDK、Spring、MyBatis 或 HTTP
 * adapter。
 */
package fun.fengwk.kkstudio.harness.runtime;
