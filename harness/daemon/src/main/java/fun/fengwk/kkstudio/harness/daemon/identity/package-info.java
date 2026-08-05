/**
 * Environment 持久身份：环境根下原子创建并复用的 canonical UUID 身份文件。
 *
 * <p>身份不是凭据；只用于跨连接/跨重启稳定地路由 Environment 绑定。见 {@link
 * fun.fengwk.kkstudio.harness.daemon.identity.EnvironmentIdentity}。
 */
package fun.fengwk.kkstudio.harness.daemon.identity;
