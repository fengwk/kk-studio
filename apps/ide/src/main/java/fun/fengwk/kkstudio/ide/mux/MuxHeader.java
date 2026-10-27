package fun.fengwk.kkstudio.ide.mux;

import java.util.List;
import java.util.Objects;

/** 一条保序 header；同名 header 可重复出现。 */
public record MuxHeader(String name, String value) {

  public MuxHeader {
    name = MuxWireRules.requireHeaderName(name);
    value = MuxWireRules.requireHeaderValue(value);
  }

  /** 创建 header 列表的非空不可变副本。 */
  static List<MuxHeader> copyHeaders(List<MuxHeader> headers) {
    if (headers == null) {
      throw new IllegalArgumentException("headers must not be null");
    }
    for (MuxHeader header : headers) {
      Objects.requireNonNull(header, "header");
    }
    return List.copyOf(headers);
  }
}
