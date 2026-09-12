package fun.fengwk.kkstudio.platform.cloudfs.domain;

/** Cloud File System 节点类型。 */
public enum CloudNodeKind {

  /** 目录节点。 */
  DIRECTORY,

  /** 带版本历史的 UTF-8 文本节点。 */
  TEXT,

  /** 引用底层 StorageBlob 的不可变二进制或大文件节点。 */
  BLOB
}
