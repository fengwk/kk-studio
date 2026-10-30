/**
 * Builtin debug 专属只读查询：{@link fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestQuery} 在同一
 * Runtime 事务内锁定 Thread，读取当前 open Turn 的 Builtin generation 冻结请求，投影为 {@link
 * fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestView}。
 *
 * <p>没有当前 generation、或调用不归本 Thread / open Turn 时返回 empty；公共 ThreadSnapshot 不携带私有 request spec。
 */
package fun.fengwk.kkstudio.harness.builtin.query;
