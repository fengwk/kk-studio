/**
 * 与 HTTP 无关的领域错误模型。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.error.DomainException} 是全仓唯一的领域异常；{@link
 * fun.fengwk.kkstudio.platform.error.DomainErrorCode} 是对外稳定的机器码。异常只携带错误码、稳定 {@code resource} 与去敏
 * {@code detail}，不接受任意底层异常文本；HTTP 状态与响应形状由 {@code platform.http} 的 {@code DomainErrorStatus}/{@code
 * DomainErrorResponses} 统一给出。
 */
package fun.fengwk.kkstudio.platform.error;
