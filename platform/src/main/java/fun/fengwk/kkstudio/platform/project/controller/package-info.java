/**
 * Issue Controller 的部署级配置属性。
 *
 * <p>本包当前只保留 {@link fun.fengwk.kkstudio.platform.project.controller.IssueControllerProperties}
 * 供宿主装配与测试上下文引用；确定性的 Run 接受与收尾编排位于 {@code fun.fengwk.kkstudio.platform.project.service}， Work
 * 邮箱的领取/租约位于 {@code fun.fengwk.kkstudio.platform.project.service.IssueWorkStore}。旧的角色调谐器与
 * IssueAgentSession 调度器已随目标模型移除。
 */
package fun.fengwk.kkstudio.platform.project.controller;
