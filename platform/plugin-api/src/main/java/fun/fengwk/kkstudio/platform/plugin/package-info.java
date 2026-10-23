/**
 * 构建期 Plugin 的控制面：Studio Plugin SPI、安装目录与凭据生命周期。
 *
 * <p>Plugin 是 classpath 构建期单元，不是运行时安装物：{@link fun.fengwk.kkstudio.platform.plugin.StudioPlugin}
 * bean 由各 Plugin JAR 自己的 auto-configuration 提供，{@link
 * fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry} 只收集当前 Spring 容器中的
 * bean，因此「是否安装」完全由依赖决定，数据库里不存在第二个 enabled 开关，也没有运行时类加载或目录扫描路径。
 *
 * <p>Plugin 只编译期依赖轻量 plugin-api 与两域 SPI，Platform 与 web 主源码不依赖具体 Plugin 实现。凭据是本包的秘密载体： {@link
 * fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore}
 * 提供与普通配置隔离的凭据访问，不提供加密保证。Plugin 只能通过 SPI 交还 opaque JSON 与读取本次调用所需的凭据快照，拿不到 repository 或 lease。
 *
 * <p>安装期定义（{@link fun.fengwk.kkstudio.platform.plugin.StudioPlugin}）与运行期实例（{@link
 * fun.fengwk.kkstudio.platform.plugin.PluginInstance}）分离：定义声明身份、受控配置与认证协议，配置产出的实例才持有资源并贡献 Harness /
 * Canvas 两域能力；Plugin 经由 {@link fun.fengwk.kkstudio.platform.plugin.PluginContext} 只拿到绑定自身 pluginId
 * 的 scoped 凭据视图。实例还可选声明 {@link
 * fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheck}，由管理面的显式请求按需触发一次只读检查。
 */
package fun.fengwk.kkstudio.platform.plugin;
