/**
 * 第一方内置功能包 Contributor 及内置工具定义。
 *
 * <p>本模块承载唯一的 {@link fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor} 贡献者入口，集中注册系统
 * 内置的 13 个统一工具（统一 {@code read}、8 个 Environment capability 工具、1 个内部工具 {@code task}、 2 个 Goal 管理工具、1
 * 个人工输入工具 {@code ask_user}）与 {@code goal.progress} 自定义 Entry ownership。 其中 {@code read} 声明 OPTIONAL
 * 环境关系，8 个宿主工具声明 REQUIRED 环境关系，{@code task}、Goal 工具与 {@code ask_user} 声明 NONE。
 *
 * <p>依赖与边界：
 *
 * <ul>
 *   <li>依赖 {@code harness-common}、{@code harness-contributor-api}、{@code harness-tool}、{@code
 *       harness-environment} 与 Jackson；
 *   <li>不直接实现底层 Environment transport 或 Daemon 进程通信；
 *   <li>不为 Goal 建立独立数据库表；不管理 durable 事务与持久化调度；
 *   <li>{@code ask_user} 只提供模型可见契约，等待与答案物化由 Runtime 拥有。
 * </ul>
 *
 * <p>关键不变量：每个内置工具的模型可见 name（{@code read}/{@code write}/{@code edit}/{@code bash}/{@code
 * grep}/{@code find}/{@code lsp_*}/{@code task}/{@code *_goal}/{@code ask_user}）是全局唯一产品身份，并在系统
 * 启动时一次性装配冻结。
 */
package fun.fengwk.kkstudio.harness.builtin;
