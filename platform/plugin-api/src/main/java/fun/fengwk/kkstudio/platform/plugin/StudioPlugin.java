package fun.fengwk.kkstudio.platform.plugin;

import java.util.Optional;

/**
 * 构建期 Plugin SPI：一个可选 JAR 向 Platform 声明安装身份、认证交互与凭据刷新方式。
 *
 * <p>实现必须由 Plugin JAR 自己的 Spring Boot auto-configuration 注册为 bean；Platform 只收集容器中的实现，不扫描目录、不加载外部
 * classloader， 因此没有对应 JAR 就没有 descriptor、管理动作、后台任务与 Tool。
 *
 * <p>Plugin 通过本接口接触 Platform：认证回调交还的是待加密的 opaque 材料，刷新读到的是本次调用所需的解密快照。Plugin 不持有 repository、
 * lease、加密主密钥或任何 DTO，也不决定密文格式。
 */
public interface StudioPlugin {

  /** 安装身份与固定 region 候选；{@code pluginId} 全局唯一且不可变。 */
  PluginDescriptor descriptor();

  /** 交互式认证能力；不提供时管理面不开放任何 auth 动作。 */
  default Optional<PluginAuthHandler> authHandler() {
    return Optional.empty();
  }

  /** 凭据自动刷新能力；不提供时该 Plugin 的凭据只由人工重新登录更新。 */
  default Optional<PluginCredentialRefresher> refresher() {
    return Optional.empty();
  }
}
