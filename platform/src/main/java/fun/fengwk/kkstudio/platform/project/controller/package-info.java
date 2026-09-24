/**
 * Project Issue 的确定性 Controller 与 Work 调谐核心。
 *
 * <p>包含确定性业务调谐器 {@link fun.fengwk.kkstudio.platform.project.controller.IssueReconciler}、 调谐结果类型
 * {@link fun.fengwk.kkstudio.platform.project.controller.IssueReconcileOutcome}、 Work 调度器 {@link
 * fun.fengwk.kkstudio.platform.project.controller.IssueControllerDispatcher} 与控制配置属性 {@link
 * fun.fengwk.kkstudio.platform.project.controller.IssueControllerProperties}。
 *
 * <p>严格锁序：Project (FOR UPDATE) -&gt; Issue (FOR UPDATE) -&gt; active IssueRun -&gt;
 * project_issue_work renewLease。
 */
package fun.fengwk.kkstudio.platform.project.controller;
