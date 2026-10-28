package fun.fengwk.kkstudio.ide.mux;

/** 整条数据连接的生命周期回调。 */
public interface MuxConnectionListener {

  /** 连接因协议错误、物理写失败或适配器上报断开而终止；本地正常 close 不回调。 */
  default void onFailure(MuxConnection connection, Throwable cause) {}
}
