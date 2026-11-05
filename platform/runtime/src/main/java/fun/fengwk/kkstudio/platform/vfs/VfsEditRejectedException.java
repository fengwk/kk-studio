package fun.fengwk.kkstudio.platform.vfs;

/** VFS 编辑预览或快照校验的确定性拒绝：消息已包含执行事实，可直接作为工具错误文案。 */
public final class VfsEditRejectedException extends RuntimeException {

  public VfsEditRejectedException(String message) {
    super(message);
  }
}
