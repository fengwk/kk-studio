package fun.fengwk.kkstudio.ide.mux;

/** 逻辑流已经关闭或正在关闭；不会再产生 wire 写入。 */
public class MuxStreamClosedException extends IllegalStateException {

  public MuxStreamClosedException(String message) {
    super(message);
  }
}
