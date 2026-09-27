/**
 * Issue Controller 的部署级配置与确定性调度。
 *
 * <p>{@link fun.fengwk.kkstudio.project.controller.IssueControllerDispatcher} 只做 {@code
 * project_issue_work} 的 claim、bounded handoff 与 poll 生命周期；{@link
 * fun.fengwk.kkstudio.project.controller.IssueReconciler} 在 Project −&gt; Issue −&gt; 活动 Run
 * 的固定锁序下推进目标语义（安全等待、Run 收尾、交接、失败暂停与迟到回调拒绝）。Run 接受与收尾的原子编排仍在 {@code
 * fun.fengwk.kkstudio.project.service}，Work 邮箱的租约与唤醒在 {@code
 * fun.fengwk.kkstudio.project.service.IssueWorkStore}。
 */
package fun.fengwk.kkstudio.project.controller;
