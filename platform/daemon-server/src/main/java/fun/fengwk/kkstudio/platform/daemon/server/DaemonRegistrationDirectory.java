package fun.fengwk.kkstudio.platform.daemon.server;

import java.util.Optional;

/**
 * 注册凭据窄端口：把握手请求头携带的 {@code registrationToken} 解析为规范化的 Environment 身份。
 *
 * <p>只做身份解析，不参与租约抢占；原子有效性校验仍由 {@link DaemonLeaseStore#tryAcquire} 完成。
 */
public interface DaemonRegistrationDirectory {

  /** 按注册凭据查询环境身份；不存在时返回空。 */
  Optional<DaemonRegistration> findByRegistrationToken(String registrationToken);
}
