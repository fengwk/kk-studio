/**
 * 各产品模块共享的 HTTP 边界约定。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.http.RequestParameters} 只翻译共同解析器的拒绝，不复制值算法。错误响应由 Web 的全局
 * advice 构造，本包不装配 advice、MVC 或容器。
 */
package fun.fengwk.kkstudio.platform.http;
