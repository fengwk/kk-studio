/**
 * Project 的 HTTP 边界。
 *
 * <p>{@link fun.fengwk.kkstudio.project.http.ProjectController} 提供项目配置、工作流、YOLO、归档与聚合快照， {@link
 * fun.fengwk.kkstudio.project.http.IssueController} 提供 Issue 事实、流转、暂停/恢复、活动与证据。 控制器只做 HTTP 映射，领域行为在
 * {@code service}；成功响应直接返回资源，领域错误由 {@link fun.fengwk.kkstudio.common.error.DomainException} 经 Web
 * 组合根映射为稳定错误码。
 */
package fun.fengwk.kkstudio.project.http;
