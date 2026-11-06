/**
 * Harness 族的 HTTP 出入口：待处理交互、工具调用输入与只读会话文件。
 *
 * <p>控制器只声明路由与类型化参数，请求体与查询参数由 HTTP 边界统一校验；本包不翻译异常、不手工解析 id，也不返回响应信封。 操作者身份只来自部署边界建立的认证主体。
 */
package fun.fengwk.kkstudio.platform.harness.http;
