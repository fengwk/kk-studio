package fun.fengwk.kkstudio.ide.mux;

import java.util.Objects;

/**
 * 一条完整的 IDE 数据 wire 帧，不含 12 字节头。
 *
 * <p>{@code streamId} 0 仅允许出现在连接级 WINDOW；OPEN 与各流级帧必须使用 1..2^32-1。帧对象不持有连接状态，可在编码前安全构造。
 */
public sealed interface MuxFrame {

  /** data 帧使用的最大 streamId。 */
  long MAX_STREAM_ID = 0xFFFFFFFFL;

  /** 帧类型。 */
  MuxFrameType type();

  /** 所属流；连接级 WINDOW 为 0。 */
  long streamId();

  /** OPEN：Server 开始一条逻辑流。 */
  record Open(long streamId, MuxOpen request) implements MuxFrame {

    public Open {
      requireStreamId(streamId);
      Objects.requireNonNull(request, "request");
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.OPEN;
    }
  }

  /** READY：Daemon 接受逻辑流。 */
  record Ready(long streamId) implements MuxFrame {

    public Ready {
      requireStreamId(streamId);
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.READY;
    }
  }

  /** HEADERS：响应头或 WS 握手结果，每条流至多一次。 */
  record Headers(long streamId, MuxHeaders headers) implements MuxFrame {

    public Headers {
      requireStreamId(streamId);
      Objects.requireNonNull(headers, "headers");
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.HEADERS;
    }
  }

  /** DATA：HTTP 字节或一个 WS 消息分段。 */
  record Data(long streamId, MuxDataKind kind, boolean fin, byte[] payload) implements MuxFrame {

    public Data {
      requireStreamId(streamId);
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(payload, "payload");
      if (payload.length > MuxLimits.MAX_PAYLOAD_BYTES) {
        throw new IllegalArgumentException(
            "payload exceeds " + MuxLimits.MAX_PAYLOAD_BYTES + " bytes");
      }
      if (kind == MuxDataKind.HTTP && fin) {
        throw new IllegalArgumentException("HTTP DATA must not set FIN");
      }
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.DATA;
    }
  }

  /** WINDOW：正的 uint32 增量 credit；streamId 0 为连接级。 */
  record Window(long streamId, long increment) implements MuxFrame {

    public Window {
      if (streamId < 0 || streamId > MAX_STREAM_ID) {
        throw new IllegalArgumentException("WINDOW streamId must be an unsigned 32-bit value");
      }
      if (increment <= 0 || increment > MAX_STREAM_ID) {
        throw new IllegalArgumentException("WINDOW increment must be a positive uint32");
      }
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.WINDOW;
    }
  }

  /** END：HTTP 当前方向半关闭。 */
  record End(long streamId) implements MuxFrame {

    public End {
      requireStreamId(streamId);
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.END;
    }
  }

  /** COMPLETE：Server 已消费响应 END 并成功 flush 浏览器输出。 */
  record Complete(long streamId) implements MuxFrame {

    public Complete {
      requireStreamId(streamId);
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.COMPLETE;
    }
  }

  /** RESET：双向结束一条流，不表示业务副作用未发生。 */
  record Reset(long streamId, MuxReset reset) implements MuxFrame {

    public Reset {
      requireStreamId(streamId);
      Objects.requireNonNull(reset, "reset");
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.RESET;
    }
  }

  /** CLOSE：WS 关闭码与原因。 */
  record Close(long streamId, MuxClose close) implements MuxFrame {

    public Close {
      requireStreamId(streamId);
      Objects.requireNonNull(close, "close");
    }

    @Override
    public MuxFrameType type() {
      return MuxFrameType.CLOSE;
    }
  }

  private static void requireStreamId(long streamId) {
    if (streamId <= 0 || streamId > MAX_STREAM_ID) {
      throw new IllegalArgumentException("streamId must be within [1, 2^32-1]");
    }
  }
}
