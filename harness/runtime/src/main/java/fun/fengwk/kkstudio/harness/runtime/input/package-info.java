/**
 * Runtime 拥有的内部人工输入契约：{@code ask_user} 冻结问卷的解析、答案校验与规范化。
 *
 * <p>问卷原文不是新的持久化事实：它只存在于 Assistant ToolCall 的 arguments 中，运行时据此于接受调用时冻结等待、于提交答案时再次校验。
 * 答案本身是工具结果，规范化后的答案 JSON 同时作为模型可见内容与 ToolResult details；提交回执（submissionId / actor / acceptedAt）由
 * Invocation 与 Entry 元数据承载，本包不保存 Submitted 状态。
 *
 * <p>本包是纯值对象与校验：不访问 Store、不产生锁、不做任何 I/O。工具名 {@code ask_user} 属于运行时拥有的产品契约，具体 Tool
 * 的注册、schema、提示词与目录可见性由宿主 Contributor / 平台集成完成。
 */
package fun.fengwk.kkstudio.harness.runtime.input;
