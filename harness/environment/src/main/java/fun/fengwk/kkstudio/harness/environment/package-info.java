/**
 * Environment 契约与绑定模型。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.EnvironmentId} 是跨 Runtime、Platform Gateway 与
 * Daemon 共享的 canonical 持久化路由身份（UUID 值对象），也是冻结执行的唯一环境绑定形态；目录只存在于具体工具的 arguments 中。
 *
 * <p>本包仅包含纯不可变值对象与语法规则，不包含本地文件系统/网络 I/O、执行逻辑与具体 transport。
 */
package fun.fengwk.kkstudio.harness.environment;
