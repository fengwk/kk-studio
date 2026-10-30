/**
 * Builtin 的执行端口：{@link fun.fengwk.kkstudio.harness.builtin.port.TurnResolver} 在调用方事务内同步解析 candidate
 * Turn，{@link fun.fengwk.kkstudio.harness.builtin.port.ModelGateway} 先做纯本地准入、再在调用线程上阻塞执行一次冻结的 Model
 * invocation。
 *
 * <p>两个端口都由组合根注入；实现不拥有事务，也不反写 durable 状态，终态由 MODEL Work 路径的 Processor 提交。
 */
package fun.fengwk.kkstudio.harness.builtin.port;
