package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

/** 节点类型与操作预期不符时抛出（如尝试在非 DIRECTORY 节点下创建子节点，或对非 TEXT 节点执行文本编辑）。 */
public class CloudNodeKindConflictException extends CloudFileSystemException {

  private final CloudPath path;
  private final CloudNodeKind expectedKind;
  private final CloudNodeKind actualKind;

  public CloudNodeKindConflictException(
      CloudPath path, CloudNodeKind expectedKind, CloudNodeKind actualKind) {
    super(
        String.format(
            "Node kind conflict at %s: expected %s, actual is %s", path, expectedKind, actualKind));
    this.path = path;
    this.expectedKind = expectedKind;
    this.actualKind = actualKind;
  }

  public CloudPath getPath() {
    return path;
  }

  public CloudNodeKind getExpectedKind() {
    return expectedKind;
  }

  public CloudNodeKind getActualKind() {
    return actualKind;
  }
}
