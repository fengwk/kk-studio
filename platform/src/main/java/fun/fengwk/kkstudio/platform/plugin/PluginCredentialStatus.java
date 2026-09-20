package fun.fengwk.kkstudio.platform.plugin;

/**
 * 管理面看到的凭据状态投影。
 *
 * <p>{@link #NOT_CONNECTED} 与 {@link #KEY_UNAVAILABLE} 是投影态（分别表示没有凭据行、主密钥不可用），其余四个状态是 {@code
 * plugin_credential.status} 的权威列取值。
 */
public enum PluginCredentialStatus {

  /** 尚无凭据行。 */
  NOT_CONNECTED,

  /** 部署主密钥不可用或密文无法解密，读写 fail closed。 */
  KEY_UNAVAILABLE,

  /** 凭据可用。 */
  CONNECTED,

  /** 可证明未发出的刷新失败，或已收到但不可用的刷新结果，等待有界延迟重试；两种情况下旧凭据都未被替换，仍在本地时限内时继续可用。 */
  REFRESH_FAILED,

  /** 刷新结果未知，不重放也不再使用该凭据。 */
  REFRESH_UNCERTAIN,

  /** 服务端确定性拒绝，必须重新登录。 */
  REAUTH_REQUIRED;

  /** 该状态是否为持久化列取值（即写在 {@code plugin_credential.status} 上）。 */
  public boolean isPersisted() {
    return this != NOT_CONNECTED && this != KEY_UNAVAILABLE;
  }
}
