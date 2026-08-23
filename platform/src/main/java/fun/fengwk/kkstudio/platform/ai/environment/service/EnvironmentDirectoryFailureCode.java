package fun.fengwk.kkstudio.platform.ai.environment.service;

/**
 * 目录浏览请求的应用级失败分类（platform gateway 计算；daemon wire 分类见 {@link DaemonDirectoryFailureCode}）。
 *
 * <p>wire 只承载 daemon 的确定性失败（{@code INVALID_PATH}/{@code NOT_FOUND}/{@code NOT_DIRECTORY}/{@code
 * IO_ERROR}）；环境未知/不可用与超时是 gateway 在本地计算的结果 code，不进入 wire。HTTP 映射：{@code
 * ENVIRONMENT_NOT_FOUND}/{@code NOT_FOUND} → 404，{@code ENVIRONMENT_UNAVAILABLE} → 409，{@code
 * INVALID_PATH}/{@code NOT_DIRECTORY} → 400，{@code TIMEOUT} → 504，{@code IO_ERROR} → 502。
 */
public enum EnvironmentDirectoryFailureCode {
  /** path 违反 wire 相对路径契约（absolute、反斜杠、空/{@code '.'}/{@code '..'} 段、控制字符）或越出 Environment Root。 */
  INVALID_PATH,

  /** Environment Root 下不存在该路径。 */
  NOT_FOUND,

  /** 路径存在但不是目录，不能浏览。 */
  NOT_DIRECTORY,

  /** daemon 读取目录时发生本地 IO 失败。 */
  IO_ERROR,

  /** registry 中不存在该 Environment（HTTP 404）。 */
  ENVIRONMENT_NOT_FOUND,

  /** Environment 已注册但未 READY，或连接/心跳不可用（HTTP 409）。 */
  ENVIRONMENT_UNAVAILABLE,

  /** daemon 在超时窗口内未返回结果（HTTP 504）。 */
  TIMEOUT
}
