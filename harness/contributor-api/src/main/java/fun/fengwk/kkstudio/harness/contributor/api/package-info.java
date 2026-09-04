/**
 * 受信任 Java Contributor 的构建期发现、注册 SPI 与启动期目录冻结机制。
 *
 * <p>本包提供 Harness 扩展点接入体系：
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor} 与 {@link
 *       fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor}： 声明贡献者身份、展示元数据与 {@code
 *       requires} 依赖 DAG；
 *   <li>{@link fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar}： 类型化注册统一 {@link
 *       fun.fengwk.kkstudio.harness.contributor.api.Tool}、Custom Entry ownership 与 {@link
 *       fun.fengwk.kkstudio.harness.contributor.api.ContextProjector}；
 *   <li>{@link fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog}： 验证 requires DAG
 *       无环无缺失，按拓扑序收集贡献，冻结为不可变目录；
 *   <li>{@link fun.fengwk.kkstudio.harness.contributor.api.BranchView}、{@link
 *       fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration} 与 {@link
 *       fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome}： 限定所有权边界的分支自定义状态只读查询、静态访问声明与状态追加
 *       effects。
 * </ul>
 *
 * <p>依赖与边界：
 *
 * <ul>
 *   <li>仅依赖 {@code harness-tool} 与 {@code harness-environment} 的值模型契约；
 *   <li>不暴露 Runtime Store、gateway、transaction、lock、调度状态机、Spring 或 JDBC；
 *   <li>不负责运行时动态安装、卸载、reload 或 classloader 隔离；Tool 无法直接写入 Entry、Thread、Invocation 或 Work。
 * </ul>
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li>全局唯一性：{@link fun.fengwk.kkstudio.harness.contributor.api.ContributorId}、{@link
 *       fun.fengwk.kkstudio.harness.tool.AgentToolId} 与模型调用名全局唯一； 贡献 {@code localName} 在所属
 *       Contributor 内唯一；Custom Entry ownership 键为 {@code (contributorId, customType)}；
 *   <li>静态状态声明校验：Tool 声明的 {@link fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration} 涉及的
 *       customType 必须在该 Contributor 内显式注册；
 *   <li>执行与 effects：{@link fun.fengwk.kkstudio.harness.contributor.api.Tool#execute} 为快速返回的启动式异步
 *       SPI； {@link fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome} 仅在成功时允许携带 {@link
 *       fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry} effects；
 *   <li>分支隔离：{@link fun.fengwk.kkstudio.harness.contributor.api.BranchView} 查询严格限定在当前 Contributor
 *       且限定在当前 Assistant branch 的 root-to-head 路径上；
 *   <li>冻结不可变性：冻结后各扩展点按 requires 传递偏序、priority 降序、ContributionId 字典序排序，目录完全不可变。
 * </ul>
 */
package fun.fengwk.kkstudio.harness.contributor.api;
