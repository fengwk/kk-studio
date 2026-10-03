/**
 * JDK HTTP 代理选择与主机绕过规则。
 *
 * <p>本包只构造不可变的 ProxySelector：Backend 采用显式固定配置，Daemon 采用环境变量与调用方提供的系统代理回退。
 * 不读取数据库、不修改 JVM 全局选择器，也不负责客户端、连接或线程的生命周期。
 */
package fun.fengwk.kkstudio.harness.common.network;
