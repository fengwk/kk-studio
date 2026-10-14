package fun.fengwk.kkstudio.platform.plugin;

import java.util.Optional;

/**
 * 构建期 Plugin SPI：一个可选 JAR 向 Platform 声明安装身份、受控配置、认证交互与运行期实例装配方式。
 *
 * <p>实现必须由 Plugin JAR 自己的 Spring Boot auto-configuration 注册为 bean；Platform 只收集容器中的实现，不扫描目录、不加载外部
 * classloader， 因此没有对应 JAR 就没有 descriptor、管理动作、后台任务与 Tool。
 *
 * <p>本接口区分「安装期定义」与「运行期实例」：{@link #configure(PluginConfig, PluginContext)} 按一次配置产出一个由 Plugin 拥有、可关闭的
 * {@link PluginInstance}，它是 Plugin 两域贡献（Harness / Canvas）与实例资源的唯一所有者。Plugin 通过 {@link
 * PluginContext} 接触 Platform：只能读取本 Plugin 的凭据快照与调用期作废，不持有 repository、lease、加密主密钥或任何 DTO。
 */
public interface StudioPlugin {

  /** 安装身份；{@code pluginId} 全局唯一且不可变。 */
  PluginDescriptor descriptor();

  /** 受控配置契约：普通值 schema、默认值声明与秘密字段说明。 */
  PluginConfigSchema configurationSchema();

  /** 安装期认证交互声明，必须与 {@link #authHandler()} 一致。 */
  PluginAuthSpec authenticationSpec();

  /** 按一次配置装配一个由本 Plugin 拥有、可关闭的运行期实例。 */
  PluginInstance configure(PluginConfig config, PluginContext context);

  /** 交互式认证能力；不提供时管理面不开放任何 auth 动作。 */
  default Optional<PluginAuthHandler> authHandler() {
    return Optional.empty();
  }

  /** 凭据自动刷新能力；不提供时该 Plugin 的凭据只由人工重新登录更新。 */
  default Optional<PluginCredentialRefresher> refresher() {
    return Optional.empty();
  }
}
