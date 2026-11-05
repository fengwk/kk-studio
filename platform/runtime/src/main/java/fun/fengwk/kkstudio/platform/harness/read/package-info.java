/**
 * 平台统一读取的内容来源实现。
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformSkillContentReader}：按 {@code
 *       vfs:/skills/<package>/<skill>/...} 读取已发布 Skill 归档中的内容；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader}：按 {@code
 *       vfs:/resources/<blobId>} 读取当前会话授权的 Blob 内容；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.TextReadWindow}：文本窗口读取和格式化工具，复刻与 daemon
 *       {@code fs.read} 完全一致的有界文本输出语义；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformReadException}：平台侧读取确定性失败异常；
 *   <li>{@link fun.fengwk.kkstudio.platform.harness.read.PlatformReadConfiguration}：自动装配上述组件的
 *       Spring 配置。
 * </ul>
 *
 * <p>{@code vfs:} 路径的路由与其余文件工具操作见 {@code fun.fengwk.kkstudio.platform.vfs}。
 */
package fun.fengwk.kkstudio.platform.harness.read;
