/**
 * Model/Tool Invocation 值列的严格确定性 JSON 协议。
 *
 * <p>每个公开 codec 仅负责一个具体 Runtime 值，并将其嵌套的 Provider/Tool 描述符委托给现有 canonical codec。 未知、缺失、重复、尾部或类型错误的
 * wire 字段都会在领域构造函数重新校验跨字段不变量之前被拒绝。
 */
package fun.fengwk.kkstudio.harness.runtime.invocation.codec;
