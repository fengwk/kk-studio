/**
 * 共享严格 JSON 值校验器与有界编码器。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.common.json.JsonValues} 提供顶层 JSON 值与对象的严格合法性保证，
 * 强制启用严格重复键检测与尾随 token 拦截； {@link fun.fengwk.kkstudio.harness.common.json.BoundedOutputStream} 提供按
 * UTF-8 字节上限中止的共享输出流（可只计数或保留字节）； {@link fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter}
 * 在其上提供有界 UTF-8 JSON 文本序列化， 超过字节上限立即中止并不物化超限内容。
 *
 * <p>本包只提供无状态 JSON 边界工具，不持有领域特定模型或执行态副作用。
 */
package fun.fengwk.kkstudio.harness.common.json;
