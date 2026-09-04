/**
 * Environment 契约与绑定模型。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.EnvironmentId} 是跨 Runtime、Platform Gateway 与
 * Daemon 共享的 canonical 持久化路由身份（UUID 值对象）；{@link
 * fun.fengwk.kkstudio.harness.environment.EnvironmentBinding} 封装已冻结的 Environment 路由与工作区相对路径；{@link
 * fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath} 提供纯跨平台的相对 wire 路径语法校验。
 *
 * <p>本包仅包含纯不可变值对象与语法规则，不包含本地文件系统/网络 I/O、执行逻辑与具体 transport。
 */
package fun.fengwk.kkstudio.harness.environment;
