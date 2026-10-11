package fun.fengwk.kkstudio.web.environment.ide;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可复用的有界 WebSocket 帧桥：在两个 {@link IdeStreamPeer} 之间逐帧透传文本/二进制帧。
 *
 * <p>跨节点单跳转发必须保持原始 wire 帧语义：本类只做帧上限保护（文本 16 KiB、二进制 65538 字节 = 2 字节分段头 + 65536 载荷）与透传，
 * 不解析协议、不重编码、不二次 ACK，因此 credit 窗口在整条链路上保持一次。任一侧关闭或出现超界帧都 fail-closed，双向一起收尾。
 */
final class IdeStreamFrameBridge implements AutoCloseable {

  /** 二进制帧上限：与 wire 分段头的 2 字节 + 65536 载荷一致。 */
  static final int MAX_BINARY_FRAME_BYTES = 65538;

  /** 文本控制帧上限：与 Node peer 一致。 */
  static final int MAX_TEXT_FRAME_BYTES = 16 * 1024;

  private static final long POLL_TICK_MILLIS = 5L;

  private final IdeStreamPeer left;
  private final IdeStreamPeer right;
  private final AtomicBoolean closed = new AtomicBoolean();

  IdeStreamFrameBridge(IdeStreamPeer left, IdeStreamPeer right) {
    this.left = Objects.requireNonNull(left, "left");
    this.right = Objects.requireNonNull(right, "right");
  }

  /** 启动双向透传；任一侧结束即关闭两侧。 */
  void start() {
    Thread leftToRight = new Thread(() -> pump(left, right), "ide-frame-bridge-l2r");
    Thread rightToLeft = new Thread(() -> pump(right, left), "ide-frame-bridge-r2l");
    leftToRight.setDaemon(true);
    rightToLeft.setDaemon(true);
    leftToRight.start();
    rightToLeft.start();
  }

  /** 转发单个入站帧；超界或发送失败返回 false（调用方必须收尾）。 */
  static boolean forward(IdeStreamPeer.Inbound frame, IdeStreamPeer target) {
    if (frame instanceof IdeStreamPeer.Inbound.Text text) {
      if (utf8Length(text.json()) > MAX_TEXT_FRAME_BYTES) {
        return false;
      }
      target.sendText(text.json());
      return true;
    }
    byte[] bytes = ((IdeStreamPeer.Inbound.Binary) frame).frame();
    if (bytes.length > MAX_BINARY_FRAME_BYTES) {
      return false;
    }
    target.sendBinary(bytes);
    return true;
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private void pump(IdeStreamPeer source, IdeStreamPeer target) {
    try {
      while (!closed.get()) {
        IdeStreamPeer.Inbound frame = source.poll(Duration.ofMillis(POLL_TICK_MILLIS));
        if (frame == null) {
          if (source.closed()) {
            break;
          }
          continue;
        }
        if (!forward(frame, target)) {
          break;
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException error) {
    } finally {
      close();
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      left.close();
      right.close();
    }
  }
}
