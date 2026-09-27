/**
 * 统一交互（问卷等待与工具审批等待）对外的 HTTP DTO。
 *
 * <p>只描述待处理列表与人工输入提交的 wire 契约，不复制 Harness Invocation 的领域事实：列表项由 platform 交互服务按 Session/Thread 解析产品
 * owner 后组装，提交结果只回执 durable 接受事实。
 */
package fun.fengwk.kkstudio.share.ai.interaction;
