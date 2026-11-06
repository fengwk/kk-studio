/**
 * Harness 族的 HTTP 传输对象。
 *
 * <p>固定形态用 sealed 类型表达（待处理交互条目、工具调用输入），多字段视图用 {@code *DTO} 类或 record 表达；可空性只由 {@code @Nullable}
 * 声明，时间统一为 {@link java.time.Instant}。本包不包含领域对象与持久化模型。
 */
package fun.fengwk.kkstudio.platform.harness.dto;
