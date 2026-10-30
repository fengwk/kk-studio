/**
 * Project 对平台与 Harness 能力的窄适配：Issue/Run 执行所需的 Thread 事实与命令控制，以及证据发布所需的全局 Blob 能力。
 *
 * <p>Project 只经由这里接触运行时与存储，平台异常在此收敛为 Project 领域异常；Issue+Agent owner 的层级锁与绑定则由 {@code
 * service.ProjectIssueAgentOwnership} 实现平台定义的 {@code IssueAgentOwnership}。
 */
package fun.fengwk.kkstudio.project.platform;
