/**
 * 各产品模块共享的 HTTP 响应模型。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.http.ApiResponse} 是 JSON 响应的唯一信封，供拥有 HTTP 边界的应用模块与 Web
 * 组合根共同返回；本包不负责异常翻译、响应包装或状态同步，那些行为仍由宿主的 advice 承担。
 */
package fun.fengwk.kkstudio.platform.http;
