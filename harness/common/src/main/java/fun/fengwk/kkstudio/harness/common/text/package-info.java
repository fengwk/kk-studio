/**
 * 内置文件读取与受管文本读取共享的窗口核心。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.common.text.TextReadWindow} 是 {@code read}
 * 契约中“分页窗口、正文预算、截断元数据与编号正文渲染”的 唯一实现：本地 Daemon 与 Platform
 * 各自只保留解码、BOM、超时/中断与失败映射的薄适配器，两侧输出因此不会漂移。本包不做 I/O 判定、不引入额外依赖， 字符流与编码判定由调用方提供。
 */
package fun.fengwk.kkstudio.harness.common.text;
