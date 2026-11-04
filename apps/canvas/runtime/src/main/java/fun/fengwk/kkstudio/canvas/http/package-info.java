/**
 * Canvas 的 HTTP 边界。
 *
 * <p>{@link fun.fengwk.kkstudio.canvas.http.CanvasController} 提供画布列表、快照与 typed command， {@link
 * fun.fengwk.kkstudio.canvas.http.CanvasFunctionController} 提供 Function 目录与 run 生命周期， {@link
 * fun.fengwk.kkstudio.canvas.http.CanvasResourceController} 签发资源直读 URL。三者统一返回共享响应信封 {@code
 * fun.fengwk.kkstudio.platform.http.ApiResponse}，并以 {@code ResponseStatusException} 表达边界错误，
 * 具体的错误信封、状态同步与 locale 由宿主的全局 advice 统一处理；本包只做 HTTP 适配，不含领域规则。
 */
package fun.fengwk.kkstudio.canvas.http;
