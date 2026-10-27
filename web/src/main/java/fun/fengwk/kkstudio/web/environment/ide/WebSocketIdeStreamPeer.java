package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.ide.protocol.IdeStreamCreditWindow;

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
 * 容器线程只负责入队。入站队列的上界由协议推导：每个数据帧至少消耗一个最小 credit，因此在途数据帧至多 {@link
 * IdeStreamCreditWindow#MAX_IN_FLIGHT_FRAMES} 个；对端的 ACK 至少确认一个最小帧，因此未处理 ACK
 * 同样至多这么多；文本控制帧只有少数几个。守约的对端永远不会 超界，超界即协议错误，由调用方 fail-closed 关闭连接，绝不无界缓冲、也绝不先 ACK 后缓冲。
 *
 * <p>{@link #close()} 与连接侧关闭都会唤醒全部等待者，使数据面立即得知连接已终止；连接侧关闭同时记录对端关闭状态，供数据面判定关闭方并转述关闭码。
 */
@Slf4j
final class WebSocketIdeStreamPeer implements IdeStreamPeer {

  /** 一条交换内的文本控制帧（响应头、WS_READY、ERROR）与 END 的余量。 */
  private static final int CONTROL_FRAME_ALLOWANCE = 8;

  /** 入站队列的帧数上限：在途数据帧与未处理 ACK 各至多一个窗口的帧数，加少量控制帧。 */
  static final int MAX_QUEUED_FRAMES =
      2 * IdeStreamCreditWindow.MAX_IN_FLIGHT_FRAMES + CONTROL_FRAME_ALLOWANCE;

  /** 入站队列的字节预算：数据载荷不超过一个 credit 窗口，另为控制帧与 ACK 留出一个窗口的余量。 */
  static final long MAX_QUEUED_BYTES = 2L * IdeStreamCreditWindow.INITIAL_CREDIT;

  private final WebSocketSession session;
  private final Deque<Inbound> inbound = new ArrayDeque<>();
  private final Object stateLock = new Object();
  private final Object sendLock = new Object();
  private long queuedBytes;

  /** 发送端写失败后不可复用；只受 sendLock 保护，不代表入站已关闭。 */
  private boolean outboundFailed;

  private volatile boolean closed;
  private volatile CloseStatus remoteCloseStatus;

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
   * <p>{@code acknowledgement=true} 表示这是消费 credit 的 ACK：它只承载「已真实交付字节」的反馈。首次写失败后封闭发送端，后续 ACK
   * 可直接丢弃，但不封闭入站（否则可能丢失随后到达的 END）。Tomcat 的 partial-write 状态在 IOException 后可能未复原，因此不能再次尝试发送；数据/控制帧仍
   * fail-closed。没有先前写失败且会话仍存活时的 IllegalStateException 必须原样抛出。
   */
  private void send(WebSocketMessage<?> message, boolean acknowledgement) {
    synchronized (sendLock) {
      if (closed || outboundFailed) {
        if (acknowledgement) {
          return;
        }
        throw new IllegalStateException("reverse stream peer is not writable");
      }
      try {
        session.sendMessage(message);
      } catch (IOException error) {
        outboundFailed = true;
        if (acknowledgement) {
          log.debug(
              "reverse stream ACK write failed, dropping ACK; phase=IDE_ACK_WRITE; exception={}",
              error.getClass().getSimpleName());
          return;
        }
        onTransportClosed(
            CloseStatus.NO_CLOSE_FRAME.withReason(
                "send failed: " + error.getClass().getSimpleName()));
        throw new IllegalStateException("failed to send on the reverse stream", error);
      } catch (IllegalStateException error) {
        if (acknowledgement && (closed || !session.isOpen())) {
          outboundFailed = true;
          // 底层会话确已关闭：只丢弃该 ACK 不改变 closed，最终结果由 HTTP_END/WS CLOSE 判定。
          log.debug(
              "reverse stream session is closed, dropping ACK; phase=IDE_ACK_CLOSED; exception={}",
              error.getClass().getSimpleName());
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
  public CloseStatus remoteCloseStatus() {
    return remoteCloseStatus;
  }

  @Override
  public void close() {
    close(CloseStatus.NORMAL);
  }

  @Override
  public void close(CloseStatus status) {
    synchronized (stateLock) {
      if (closed) {
        return;
      }
      closed = true;
      stateLock.notifyAll();
    }
    try {
      session.close(status);
    } catch (IOException ignored) {
      // 会话在关闭前已断开。
    }
  }

  /** 连接侧关闭（对端断开/错误）：记录首个关闭状态，标记关闭并唤醒全部等待者。 */
  void onTransportClosed(CloseStatus status) {
    synchronized (stateLock) {
      if (!closed) {
        remoteCloseStatus = status;
      }
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
