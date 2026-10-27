package fun.fengwk.kkstudio.ide.mux;

/** 整条数据连接已关闭或失败。 */
public class MuxConnectionClosedException extends IllegalStateException {

  public MuxConnectionClosedException(String message) {
    super(message);
  }
}
