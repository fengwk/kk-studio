/**
 * Project 产品模块：工作流配置、Issue 阶段执行与交付所需的领域契约。
 *
 * <p>内部按领域/服务/持久化分包，当前只提供领域契约；它不装配 Spring、不连接 PostgreSQL，也不认识 HTTP DTO。 宿主配置、权限、Blob 与 Harness
 * Thread/Session 由外层服务经明确端口适配，Project 不直接写别人的表。
 */
package fun.fengwk.kkstudio.project;
