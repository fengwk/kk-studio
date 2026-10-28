package fun.fengwk.kkstudio.ide.mux;

/** 本地有界发送队列或流槽位已满；调用方应等待已排队写入完成或取消等待。 */
public class MuxBackpressureException extends RuntimeException {

  public MuxBackpressureException(String message) {
    super(message);
  }
}
