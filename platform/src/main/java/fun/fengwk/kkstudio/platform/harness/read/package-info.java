/**
 * Platform 统一 {@code read} 工具执行器与相关内容读取器实现。
 *
 * <p>本包提供 {@code ReadToolExecutor} 的平台侧实现及配套组件：
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformReadToolExecutor}：统一路由 {@code
 *       kkstudio:/skills/...}、{@code kkstudio:/resources/<blobId>} 与本地路径；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformSkillContentReader}：按 {@code
 *       kkstudio:/skills/...} 读取 Platform bare Git cache 中的已发布 Skill 内容；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader}：按 {@code
 *       kkstudio:/resources/<blobId>} 读取当前会话授权的 Blob 文本；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.ReadTextWindow}：纯函数文本窗口格式化工具，复刻与 daemon
 *       {@code fs.read} 完全一致的有界文本输出语义；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformReadException}：平台侧读取确定性失败异常；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformReadConfiguration}：自动装配上述组件的
 *       Spring 配置。
 * </ul>
 */
package fun.fengwk.kkstudio.platform.harness.read;
