package fun.fengwk.kkstudio.harness.daemon.coding;

/**
 * 派发前的确定性输入拒绝：参数、workdir 或路径校验失败，此时能力尚未开始任何文件系统或进程副作用。
 *
 * <p>只有在本模块确认「拒绝发生在任何副作用之前」的校验点才抛出该类型，因此未被 capability 捕获时可以安全声明未执行。消息由抛出点给出，必须是本模块自己的固定文案，
 * 不得携带凭据或原始 arguments 片段；其他异常一律按无法确认副作用处理。
 */
final class ToolInputRejectedException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  ToolInputRejectedException(String message) {
    super(message);
  }

  ToolInputRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
