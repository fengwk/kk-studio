/**
 * 平台统一读取全局资源的实现。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformResourceReadGateway} 将 {@code
 * resource://files/<uuid>} 路由到 {@link
 * fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader}；技能 URI 暂返回明确的不可用错误，后续由
 * 技能加载能力实现。
 */
package fun.fengwk.kkstudio.platform.harness.read;
