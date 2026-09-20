/**
 * 构建期 Plugin 的控制面：Studio Plugin SPI、安装目录与加密凭据生命周期。
 *
 * <p>Plugin 是 classpath 构建期单元，不是运行时安装物：{@link fun.fengwk.kkstudio.platform.plugin.StudioPlugin}
 * bean 由各 Plugin JAR 自己的 auto-configuration 提供，{@link
 * fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry} 只收集当前 Spring 容器中的
 * bean，因此「是否安装」完全由依赖决定，数据库里不存在第二个 enabled 开关，也没有 ServiceLoader、URLClassLoader 或目录扫描路径。
 *
 * <p>Plugin 可以编译期依赖 Platform 与本包的 SPI，Platform 与 web 绝不反向依赖具体 Plugin 实现。凭据是本包唯一的秘密载体： {@link
 * fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore} 用部署级 owner-only 主密钥做
 * AES-256-GCM 认证加密，Plugin 只能通过 SPI 交还 opaque JSON 与读取本次调用所需的解密快照，拿不到 repository、lease 或主密钥。
 */
package fun.fengwk.kkstudio.platform.plugin;
