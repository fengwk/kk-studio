/**
 * Project Issue Controller 确定性调谐核心。
 *
 * <p>包含确定性业务调谐器 {@link fun.fengwk.kkstudio.platform.project.controller.IssueReconciler}、 调谐结果类型
 * {@link fun.fengwk.kkstudio.platform.project.controller.IssueReconcileOutcome} 与 控制配置属性 {@link
 * fun.fengwk.kkstudio.platform.project.controller.IssueControllerProperties}。
 *
 * <p>严格锁序：Project (FOR SHARE) -&gt; Issue (FOR UPDATE) -&gt; IssueRun (FOR UPDATE) -&gt;
 * issue_controller_work renewLease。
 */
package fun.fengwk.kkstudio.platform.project.controller;
