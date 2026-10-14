package fun.fengwk.kkstudio.platform.plugin;

/** 凭据不可用于本次调用的确定性原因。 */
public enum PluginCredentialUnavailableReason {

  /** 该 Plugin 尚无凭据行，用户必须先完成登录。 */
  NOT_CONNECTED,

  /** 部署主密钥不可用或密文认证解密失败，读写 fail closed。 */
  KEY_UNAVAILABLE,

  /** 刷新正在持有 lease，Tool 侧暂停凭据解析；调用方可稍后重试。 */
  AUTH_REFRESHING,

  /** 服务端确定性拒绝凭据，必须重新登录。 */
  REAUTH_REQUIRED,

  /** 刷新请求可能已发出但结果未知，不重放也不继续使用该凭据。 */
  REFRESH_UNCERTAIN,

  /** 凭据本地时限已过，必须重新登录。 */
  EXPIRED
}
