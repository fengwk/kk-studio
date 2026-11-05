/**
 * 各产品模块共享的 HTTP 边界约定。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.http.ApiResponse} 是 JSON 响应的唯一信封，供拥有 HTTP 边界的应用模块与 Web
 * 组合根共同返回；{@link fun.fengwk.kkstudio.platform.http.RequestParameters} 只翻译共同解析器的拒绝，不复制值算法；{@link
 * fun.fengwk.kkstudio.platform.http.DomainErrorStatus} 是领域错误到 HTTP 状态的唯一映射，{@link
 * fun.fengwk.kkstudio.platform.http.DomainErrorResponses} 是领域错误响应的唯一构造。本包不装配 advice、MVC 或容器。
 */
package fun.fengwk.kkstudio.platform.http;
