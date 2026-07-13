/**
 * 从活动 Session leaf 构建模型上下文。
 *
 * <p>管线固定为默认 transform、按优先级的扩展 transform 和 AgentMessage projection；compaction 仅改变读取视图，不修改历史 Entry。
 */
package fun.fengwk.kkstudio.harness.runtime.context;
