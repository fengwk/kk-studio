package fun.fengwk.kkstudio.harness.tool.daemon;

/**
 * 目录浏览请求的确定性失败分类。
 *
 * <p>wire 只出现前四种 code（daemon 侧 {@link DaemonMessageType#DIRECTORY_LIST_FAILED}）；{@code OFFLINE} 与
 * {@code TIMEOUT} 是 gateway 在 daemon 无响应时计算的结果 code，不进入 wire。
 */
public enum DaemonDirectoryFailureCode {
  /** path 违反 wire 相对路径契约（absolute、空/`.`/`..` 段、控制字符）或越出 Environment Root。 */
  INVALID_PATH,

  /** Environment Root 下不存在该路径。 */
  NOT_FOUND,

  /** 路径存在但不是目录，不能浏览。 */
  NOT_DIRECTORY,

  /** daemon 读取目录时发生本地 IO 失败。 */
  IO_ERROR,

  /** Environment 不存在、未 READY 或连接已断开（gateway 计算）。 */
  OFFLINE,

  /** daemon 在超时窗口内未返回结果（gateway 计算）。 */
  TIMEOUT
}
