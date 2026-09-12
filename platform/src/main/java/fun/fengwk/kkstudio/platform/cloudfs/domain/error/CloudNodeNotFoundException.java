package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;

import java.util.UUID;

/** 目标路径或节点不存在时抛出。 */
public class CloudNodeNotFoundException extends CloudFileSystemException {

  private final CloudPath path;
  private final UUID nodeId;

  public CloudNodeNotFoundException(CloudPath path) {
    super("Cloud node not found at path: " + path);
    this.path = path;
    this.nodeId = null;
  }

  public CloudNodeNotFoundException(UUID nodeId) {
    super("Cloud node not found with id: " + nodeId);
    this.path = null;
    this.nodeId = nodeId;
  }

  public CloudNodeNotFoundException(String message) {
    super(message);
    this.path = null;
    this.nodeId = null;
  }

  public CloudNodeNotFoundException(CloudPath path, String message) {
    super(message);
    this.path = path;
    this.nodeId = null;
  }

  public CloudPath getPath() {
    return path;
  }

  public UUID getNodeId() {
    return nodeId;
  }
}
