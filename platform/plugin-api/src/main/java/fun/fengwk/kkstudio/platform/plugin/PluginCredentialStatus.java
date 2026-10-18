package fun.fengwk.kkstudio.platform.plugin;

/**
 * 管理面看到的凭据状态。
 *
 * <p>{@link #NOT_CONNECTED} 是投影态（没有凭据行，或凭据行只承载可选秘密字段），其余状态是 {@code plugin_credential.status} 的持久取值。
 */
public enum PluginCredentialStatus {

  /** 尚无可用认证。 */
  NOT_CONNECTED,

  /** 凭据可用。 */
  CONNECTED,

  /** 可证明未发出的刷新失败，或已收到但不可用的刷新结果，等待有界延迟重试；旧凭据在本地时限内继续可用。 */
  REFRESH_FAILED,

  /** 刷新结果未知，不重放也不再使用该凭据。 */
  REFRESH_UNCERTAIN,

  /** 服务端确定性拒绝，必须重新登录。 */
  REAUTH_REQUIRED;

  /** 该状态是否为持久化列取值。 */
  public boolean isPersisted() {
    return this != NOT_CONNECTED;
  }
}
