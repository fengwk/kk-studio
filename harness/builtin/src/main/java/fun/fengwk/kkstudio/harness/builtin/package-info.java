/**
 * 第一方内置功能包 Contributor 及内置工具定义。
 *
 * <p>本模块承载唯一的 {@link fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor} 贡献者入口，集中注册系统
 * 内置的 15 个统一工具（10 个 Environment capability 工具、2 个内部工具 {@code load_skill} 与 {@code task}、 3 个 Goal
 * 管理工具）、{@code goal.state} 自定义 Entry ownership 与 {@code goal.context} 上下文投影器。
 *
 * <p>依赖与边界：
 *
 * <ul>
 *   <li>依赖 {@code harness-common}、{@code harness-contributor-api}、{@code harness-tool}、{@code
 *       harness-environment} 与 Jackson；
 *   <li>不直接实现底层 Environment transport 或 Daemon 进程通信；
 *   <li>不为 Goal 建立独立数据库表；不管理 durable 事务与持久化调度。
 * </ul>
 *
 * <p>关键不变量：每个内置工具的模型可见 name（{@code read}/{@code write}/{@code edit}/{@code bash}/{@code
 * grep}/{@code find}/{@code lsp_*}/{@code load_skill}/{@code task}/{@code *_goal}）是全局唯一产品身份，并在系统
 * 启动时一次性装配冻结。
 */
package fun.fengwk.kkstudio.harness.builtin;
