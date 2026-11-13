/**
 * 内置上下文整理工具 {@code compact} 的模型侧契约。
 *
 * <p>本包只提供模型可见的 {@link
 * fun.fengwk.kkstudio.harness.contributor.builtin.compact.CompactTool}（无参数说明、排队语义与固定回执）与它的
 * classpath 资源。工具本身是可执行的普通 READ_ONLY 调用，只确认请求已排队；真正的压缩由 Runtime 在同一工具批次结束后的安全
 * 边界按手动压缩规则启动，COMPACTION Thread 内平台工具一律禁用，因此不会递归压缩。
 */
package fun.fengwk.kkstudio.harness.contributor.builtin.compact;
