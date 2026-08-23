package fun.fengwk.kkstudio.harness.tool.daemon;

/**
 * 目录浏览请求的确定性 wire 失败分类：daemon 侧 {@link DaemonMessageType#DIRECTORY_LIST_FAILED} 只出现这四种 code。
 * 环境未知/不可用与超时是 platform gateway 在本地计算的应用结果 code（{@code EnvironmentDirectoryFailureCode}），不进入 wire。
 */
public enum DaemonDirectoryFailureCode {
  /** path 违反 wire 相对路径契约（absolute、反斜杠、空/`.`/`..` 段、控制字符）或越出 Environment Root。 */
  INVALID_PATH,

  /** Environment Root 下不存在该路径。 */
  NOT_FOUND,

  /** 路径存在但不是目录，不能浏览。 */
  NOT_DIRECTORY,

  /** daemon 读取目录时发生本地 IO 失败。 */
  IO_ERROR
}
