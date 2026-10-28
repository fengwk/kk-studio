package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.web.socket.CloseStatus;

/** 单条物理数据连接在容器回调与 mux 驱动/节点桥之间的窄接口。 */
interface IdeDataPhysicalSocket {

  void onBinary(byte[] frame);

  void onPong();

  void onClosed(CloseStatus status);

  void onError(Throwable error);
}
