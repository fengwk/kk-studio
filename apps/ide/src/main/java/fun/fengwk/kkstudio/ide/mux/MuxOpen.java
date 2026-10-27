package fun.fengwk.kkstudio.ide.mux;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * OPEN 元数据：视图绑定、模式、请求行与有序多值 headers。
 *
 * <p>{@code viewId} 必须是以小写形式给出的 UUID；{@code bindingEpoch} 是正的 Java {@code long}。JSON 中 epoch
 * 仍以规范十进制字符串出现， 避免 JavaScript 数字精度损失。
 */
public record MuxOpen(
    UUID viewId,
    long bindingEpoch,
    MuxMode mode,
    String method,
    String path,
    List<MuxHeader> headers) {

  public MuxOpen {
    Objects.requireNonNull(viewId, "viewId");
    if (bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be positive");
    }
    Objects.requireNonNull(mode, "mode");
    method = MuxWireRules.requireMethod(method);
    path = MuxWireRules.requirePath(path);
    headers = MuxHeader.copyHeaders(headers);
    if (headers.size() > MuxLimits.MAX_OPEN_HEADERS) {
      throw new IllegalArgumentException(
          "headers must contain at most " + MuxLimits.MAX_OPEN_HEADERS + " items");
    }
  }

  /** 创建 HTTP 请求 OPEN。 */
  public static MuxOpen http(
      UUID viewId, long bindingEpoch, String method, String path, List<MuxHeader> headers) {
    return new MuxOpen(viewId, bindingEpoch, MuxMode.HTTP, method, path, headers);
  }

  /** 创建 WebSocket 升级 OPEN；升级请求固定使用 GET。 */
  public static MuxOpen webSocket(
      UUID viewId, long bindingEpoch, String path, List<MuxHeader> headers) {
    return new MuxOpen(viewId, bindingEpoch, MuxMode.WS, "GET", path, headers);
  }
}
