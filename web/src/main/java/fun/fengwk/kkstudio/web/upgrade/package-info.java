/**
 * 一次性离线升级包：把旧部署的持久数据读入当前平台契约，供隔离演练与迁移工具复用。
 *
 * <p>它只在离线工具进程里被调用，绝不装配进常驻 Web 运行时，也不对外暴露平台 API。当前只提供旧 Plugin 凭据密文的只读入口 {@link
 * fun.fengwk.kkstudio.web.upgrade.LegacyPluginCredentialCodec}：严格按旧 format 1
 * 算法解密旧有密文，返回不透明载荷，再交由平台当前 format 2 codec 用真实初始 manifest 的代际与上下文重封装。它不读主密钥以外的外部资源、不发送网络请求，也不处理
 * Skill 令牌等其它密文边界。
 */
package fun.fengwk.kkstudio.web.upgrade;
