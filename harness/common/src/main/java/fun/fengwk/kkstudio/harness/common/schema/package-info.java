/**
 * 输入参数 Schema 结构定义、校验、归一化与确定性 JSON 编解码。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.common.schema.InputSchema} 为顶层参数规范， {@link
 * fun.fengwk.kkstudio.harness.common.schema.SchemaElement} 密封表达各标量与复合元素类型， {@link
 * fun.fengwk.kkstudio.harness.common.schema.InputValidator} 实施严格参数校验， {@link
 * fun.fengwk.kkstudio.harness.common.schema.InputNormalizer} 执行前置别名与类型归一化， {@link
 * fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec} 提供规范确定性字典序 JSON 编解码。
 *
 * <p>本包只提供模式规范与校验，不负责工具执行路由或 Provider 模型参数转换。
 */
package fun.fengwk.kkstudio.harness.common.schema;
