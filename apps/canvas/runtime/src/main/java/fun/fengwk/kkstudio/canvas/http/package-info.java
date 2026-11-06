/**
 * Canvas 的 HTTP 边界。
 *
 * <p>{@link fun.fengwk.kkstudio.canvas.http.CanvasController} 提供画布列表、快照与 typed command， {@link
 * fun.fengwk.kkstudio.canvas.http.CanvasFunctionController} 提供 Function 目录与 run 生命周期， {@link
 * fun.fengwk.kkstudio.canvas.http.CanvasResourceController} 签发资源直读 URL。控制器只声明路由与类型，直接返回资源或抛出 {@code
 * DomainException}；problem+json、状态码与本地化由宿主的全局 advice 统一处理，本包不含领域规则。
 */
package fun.fengwk.kkstudio.canvas.http;
