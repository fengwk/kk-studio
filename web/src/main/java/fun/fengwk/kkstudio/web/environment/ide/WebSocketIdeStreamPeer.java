package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 由真实反向 WebSocket 连接支撑的 {@link IdeStreamPeer}：入站帧进入同时受帧数与字节预算约束的队列，出站帧在会话锁内串行发送。
 *
 * <p>数据面线程在单线程内驱动 {@link #sendText}/{@link #sendBinary}/{@link #poll}，WebSocket
 * 容器线程只负责入队。入站队列严格有界：credit 只限制数据载荷，控制帧不受 credit
 * 约束，因此这里额外以字节预算与帧数上限兜住「大量小零载荷控制帧」造成的无界内存；超界即拒绝入队，由调用方 fail-closed 关闭连接，绝不无界缓冲、也绝不先 ACK 后缓冲。
 *
 * <p>{@link #close()} 与连接侧关闭都会唤醒全部等待者，使数据面立即得知连接已终止。
 */
@Slf4j
final class WebSocketIdeStreamPeer implements IdeStreamPeer {

  /** 入站队列的帧数上限：足够容纳一个 credit 窗口内的分段与少量控制帧。 */
  static final int MAX_QUEUED_FRAMES = 512;

  /** 入站队列的字节预算：覆盖 2 个 256 KiB credit 窗口，控制帧同样计入。 */
  static final long MAX_QUEUED_BYTES = 2L * 262144L;

  private final WebSocketSession session;
  private final Deque<Inbound> inbound = new ArrayDeque<>();
  private final Object stateLock = new Object();
  private final Object sendLock = new Object();
  private long queuedBytes;
  private volatile boolean closed;

  WebSocketIdeStreamPeer(WebSocketSession session) {
    this.session = Objects.requireNonNull(session, "session");
  }

  @Override
  public void sendText(String frame) {
    sendText(frame, true);
  }

  @Override
  public void sendBinary(byte[] frame) {
    sendBinary(frame, true);
  }

  @Override
  public void sendAcknowledgement(byte[] frame) {
    send(new BinaryMessage(frame, true), true);
  }

  /** 原生分段发送文本：{@code last=false} 表示本帧不是该消息的最后一段。 */
  void sendText(String frame, boolean last) {
    send(new TextMessage(frame, last), false);
  }

  /** 原生分段发送二进制：{@code last=false} 表示本帧不是该消息的最后一段。 */
  void sendBinary(byte[] frame, boolean last) {
    send(new BinaryMessage(frame, last), false);
  }

  /**
   * 在会话锁内串行发送一帧。
   *
   * <p>{@code acknowledgement=true} 表示这是消费 credit 的 ACK：它只承载「已真实交付字节」的反馈，连接关闭后属于可安全丢弃的信息，因此 closed
   * 或发送失败（IOException，或底层已关闭会话抛出的 IllegalStateException）只丢弃该 ACK，不改变接收 closed 状态（真正的接收关闭由容器
   * onTransportClosed 负责）；数据/控制帧仍 fail-closed。 会话仍存活时抛出的 IllegalStateException 是未知状态，必须原样抛出而不是吞掉。
   */
  private void send(WebSocketMessage<?> message, boolean acknowledgement) {
    synchronized (sendLock) {
      if (closed) {
        if (acknowledgement) {
          return;
        }
        throw new IllegalStateException("reverse stream peer is closed");
      }
      try {
        session.sendMessage(message);
      } catch (IOException error) {
        if (acknowledgement) {
          log.debug("reverse stream ACK write failed, dropping ACK", error);
          return;
        }
        onTransportClosed();
        throw new IllegalStateException("failed to send on the reverse stream", error);
      } catch (IllegalStateException error) {
        if (acknowledgement && (closed || !session.isOpen())) {
          // 底层会话确已关闭：只丢弃该 ACK 不改变 closed，最终结果由 HTTP_END/WS CLOSE 判定。
          log.debug("reverse stream session is closed, dropping ACK", error);
          return;
        }
        throw error;
      }
    }
  }

  @Override
  public Inbound poll(Duration timeout) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + Math.max(0L, timeout.toNanos());
    synchronized (stateLock) {
      while (true) {
        Inbound frame = inbound.pollFirst();
        if (frame != null) {
          queuedBytes -= sizeOf(frame);
          return frame;
        }
        if (closed) {
          return null;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
          return null;
        }
        long millis = remaining / 1_000_000L;
        int nanos = (int) (remaining % 1_000_000L);
        stateLock.wait(millis, nanos);
      }
    }
  }

  /**
   * WebSocket 容器线程入队一个入站帧。
   *
   * @return false 表示超出帧数或字节预算（连接必须 fail-closed 关闭，而不是继续缓冲）
   */
  boolean offer(Inbound frame) {
    long size = sizeOf(frame);
    synchronized (stateLock) {
      if (closed) {
        return false;
      }
      if (inbound.size() >= MAX_QUEUED_FRAMES || queuedBytes + size > MAX_QUEUED_BYTES) {
        return false;
      }
      inbound.addLast(frame);
      queuedBytes += size;
      stateLock.notifyAll();
      return true;
    }
  }

  @Override
  public boolean closed() {
    return closed;
  }

  @Override
  public void close() {
    synchronized (stateLock) {
      if (closed) {
        return;
      }
      closed = true;
      stateLock.notifyAll();
    }
    try {
      session.close(CloseStatus.NORMAL);
    } catch (IOException ignored) {
      // 会话在关闭前已断开。
    }
  }

  /** 连接侧关闭（对端断开/错误）：标记关闭并唤醒全部等待者。 */
  void onTransportClosed() {
    synchronized (stateLock) {
      closed = true;
      stateLock.notifyAll();
    }
  }

  private static long sizeOf(Inbound frame) {
    return frame instanceof Inbound.Binary binary
        ? binary.frame().length
        : ((Inbound.Text) frame).json().getBytes(StandardCharsets.UTF_8).length;
  }
}
