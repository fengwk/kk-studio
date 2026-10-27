package fun.fengwk.kkstudio.ide.mux;

/** 九种 wire 帧类型及其固定编码。 */
public enum MuxFrameType {
  OPEN(1),
  READY(2),
  HEADERS(3),
  DATA(4),
  WINDOW(5),
  END(6),
  COMPLETE(7),
  RESET(8),
  CLOSE(9);

  private final int code;

  MuxFrameType(int code) {
    this.code = code;
  }

  /** 单字节 wire code。 */
  public int code() {
    return code;
  }

  /** 按 wire code 查找；未知 code 抛 {@link IllegalArgumentException}。 */
  public static MuxFrameType fromCode(int code) {
    for (MuxFrameType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown mux frame type: " + code);
  }
}
