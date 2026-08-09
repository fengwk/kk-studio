/**
 * 自动对话压缩（compaction）的纯运行时域：部署配置、切分规划、Pi 风格 prompt 资源与最终 summary 组装。
 *
 * <p>压缩 Model 调用是普通的 durable {@link
 * fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation}， 通过冻结的 {@link
 * fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest} 元数据与正常调用区分； 本包不引入新的 Work
 * / 表 / processor。规划器（{@link
 * fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPlanner}）是纯函数：只读取 root-to-head EntryPath
 * 与配置，产出一次压缩 turn 的切分事实与待摘要消息，不接触 Store、无副作用。
 */
package fun.fengwk.kkstudio.harness.runtime.compaction;
