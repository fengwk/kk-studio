/**
 * 源命令与首次递归空闲之间的持久 Join 协议。
 *
 * <p>接受时原子登记源命令；完成时只固定版本与历史 head，结果从不可变历史投影。向父线程入队与交付标记同事务提交， 不依赖进程内订阅或通知可靠性。无父 Join 用于内部 one-shot
 * 调用的完成凭据。
 */
package fun.fengwk.kkstudio.harness.runtime.join;
