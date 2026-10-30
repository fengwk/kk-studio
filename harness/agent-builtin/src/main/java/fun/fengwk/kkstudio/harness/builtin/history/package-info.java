/**
 * Builtin 响应到公共历史的投影：{@link
 * fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper} 把 Provider 响应投影为公共
 * Assistant payload，保留 stop reason、usage、可选解码时长、实际模型与请求时刻。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.history.ModelAttemptMaterialization} 在删除临时
 * invocation 行前校验结果 路径已精确物化每个失败 attempt。两者都只读输入并返回不可变值，不写 Store。
 */
package fun.fengwk.kkstudio.harness.builtin.history;
