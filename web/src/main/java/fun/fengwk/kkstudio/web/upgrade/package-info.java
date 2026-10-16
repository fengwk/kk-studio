/**
 * 一次性离线升级包：把旧部署的持久数据读入当前平台契约，供隔离演练与迁移工具复用。
 *
 * <p>它只在离线工具进程里被调用，绝不装配进常驻 Web 运行时，也不对外暴露平台 API。当前提供两条只读入口：
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.web.upgrade.LegacyPluginCredentialCodec}：严格按旧 format 1
 *       算法解密旧有密文，返回不透明载荷，再交由平台当前 format 2 codec 用真实初始 manifest 的代际与上下文重封装；
 *   <li>{@link fun.fengwk.kkstudio.web.upgrade.OfflinePluginCatalog}：在不启动 Web/worker
 *       的独立上下文里，用当前发行物真实安装的 Plugin 与内置贡献构造 epoch 1 初始目录候选，不连接数据库或解析凭据。
 * </ul>
 *
 * <p>两者都不读取业务表、不执行 Plugin 工具，不处理 Skill 令牌等其它密文边界；候选快照由调用方关闭。
 */
package fun.fengwk.kkstudio.web.upgrade;
