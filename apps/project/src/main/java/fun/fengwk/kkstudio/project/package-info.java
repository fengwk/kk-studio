/**
 * Project 产品模块：工作流配置、Issue 阶段执行与交付所需的领域契约。
 *
 * <p>内部按领域/服务/持久化/平台适配分包：领域事实与状态机、Issue 用例、PostgreSQL 持久化、REST 调度配置都在本模块内，并经 {@link
 * fun.fengwk.kkstudio.project.ProjectAutoConfiguration} 装配。跨宿主能力（全局 Blob、命令接受、Agent 分支设置、Session
 * 深删除）只经 {@code fun.fengwk.kkstudio.project.platform} 的窄适配调用，不直接写别人的表。
 */
package fun.fengwk.kkstudio.project;
