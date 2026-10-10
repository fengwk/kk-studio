package fun.fengwk.kkstudio.web.events;

import jakarta.websocket.CloseReason;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;

import fun.fengwk.kkstudio.share.notification.NotificationOutbox;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每 Session 的浏览器事件出站发送器：逻辑缓冲与公平调度完全交给共享 {@link NotificationPeerLink}/{@link NotificationOutbox}，
 * 本类只借用组合根的 executor 驱动 Jakarta {@code AsyncRemote}。
 *
 * <p><b>每连接同时只有一帧物理在途：</b>{@link #offer} 只做确定性入队：逻辑消息（含短控制帧与 heartbeat）都作为共享 carrier
 * 的一个整包；本地队列/字节预算拒绝返回 false。drain 每次只向共享 outbox 取一个 {@code sendBatchFrames} 批，并只发送该批的下一条物理帧；只有成功的
 * native 回调才推进下一帧，批内最后一帧回调 OK 才 {@code complete(batch, true)}，因此整包预算（含在途
 * 整包）直到最后一帧完成都不会被提前释放。所有下一步都经 executor 调度，native 调用与外部回调都不持锁、也不递归串发同步回调。
 *
 * <p><b>迟到回调：</b>每个回调按 {@code batch + frameIndex} 身份比对，仅当前在途帧的回调有效；失败/关闭后到达的回调一律丢弃。发送失败或 {@link
 * #close()} 会释放共享 link 的全部排队与在途逻辑包且不重试。
 *
 * <p><b>错误帧同预算：</b>{@link #fail} 停止新 offer，把 error 帧按同一 outbox 预算排在已有逻辑包之后，不丢弃正在发送或已排队的逻辑包；error
 * 帧出队并完成回调后才以指定 close code 关闭。error 帧本身无法入队时直接放弃并关闭，不存在无预算的 raw/error 旁路。
 */
final class AsyncTextSender implements AutoCloseable {

  private final Session session;
  private final NotificationPeerLink link;
  private final ExecutorService sendExecutor;
  private final Object signal = new Object();
  private final AtomicBoolean closeRequested = new AtomicBoolean();
  private volatile CloseReason reason =
      new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "event channel send failed");
  private boolean stopped;

  /** true 表示已有 drain 步骤在 executor 排队、或一帧 native 回调尚未返回；用于避免重复调度。 */
  private boolean stepPending;

  private boolean closeAfterFlush;

  /** 当前整包批次与下一条待发物理帧下标；null 表示不在批中。 */
  private NotificationOutbox.Batch activeBatch;

  private int activeIndex;

  AsyncTextSender(
      Session session,
      NotificationPeerLink link,
      ExecutorService sendExecutor,
      int sendTimeoutMillis) {
    this.session = Objects.requireNonNull(session, "session");
    this.link = Objects.requireNonNull(link, "link");
    this.sendExecutor = Objects.requireNonNull(sendExecutor, "sendExecutor");
    if (sendTimeoutMillis <= 0) {
      throw new IllegalArgumentException("sendTimeoutMillis must be positive");
    }
    session.getAsyncRemote().setSendTimeout(sendTimeoutMillis);
  }

  /** 非阻塞入队一条逻辑消息。已关闭/正在 drain-close 或共享 outbox 预算拒绝时返回 false，表示该逻辑消息未被本地接受；调用方据此走过载关闭，绝不重试。 */
  boolean offer(String text) {
    Objects.requireNonNull(text, "text");
    boolean accepted;
    boolean schedule = false;
    synchronized (signal) {
      if (stopped || closeAfterFlush) {
        return false;
      }
      accepted = link.offer(UUID.randomUUID(), text);
      if (accepted && !stepPending) {
        stepPending = true;
        schedule = true;
      }
    }
    if (schedule) {
      submitStep();
    }
    return accepted;
  }

  /**
   * 终止发送：停止新 offer，把 {@code errorFrame} 按同一预算排在已有逻辑包之后，出队并完成回调后以 {@code code} 关闭；error
   * 帧本身无法入队时直接放弃并关闭。
   */
  void fail(String errorFrame, CloseReason.CloseCodes code) {
    Objects.requireNonNull(errorFrame, "errorFrame");
    Objects.requireNonNull(code, "code");
    boolean abort = false;
    boolean schedule = false;
    synchronized (signal) {
      if (stopped) {
        return;
      }
      this.reason = new CloseReason(code, "event channel failed");
      this.closeAfterFlush = true;
      if (link.offer(UUID.randomUUID(), errorFrame)) {
        if (!stepPending) {
          stepPending = true;
          schedule = true;
        }
      } else {
        // 错误帧也无法容纳在同一预算内：不另设无预算旁路，放弃并直接关闭。
        stopped = true;
        stepPending = false;
        link.close();
        abort = true;
      }
    }
    if (abort) {
      submitClose();
      return;
    }
    if (schedule) {
      submitStep();
    }
  }

  /** 显式释放：禁止新入队并释放共享 link 的全部排队与在途逻辑包，随后关闭底层会话。 */
  @Override
  public void close() {
    synchronized (signal) {
      if (!stopped) {
        stopped = true;
        stepPending = false;
      }
    }
    link.close();
    submitClose();
  }

  /** 唯一 drain 步骤：发送当前批的下一条物理帧，或在空闲时收敛到关闭；每帧 native 回调再调度下一步。 */
  private void step() {
    NotificationOutbox.Batch batch;
    int index;
    boolean idleClose = false;
    synchronized (signal) {
      if (stopped) {
        stepPending = false;
        return;
      }
      if (activeBatch != null) {
        batch = activeBatch;
        index = activeIndex;
      } else {
        Optional<NotificationOutbox.Batch> polled = link.pollBatch();
        if (polled.isEmpty()) {
          stepPending = false;
          batch = null;
          index = -1;
          if (closeAfterFlush) {
            // 错误帧已全部出队并完成回调：现在才终止，后续 fail/offer 一律拒绝。
            stopped = true;
            idleClose = true;
          }
        } else {
          activeBatch = polled.get();
          activeIndex = 0;
          batch = activeBatch;
          index = 0;
        }
      }
    }
    if (batch == null) {
      if (idleClose) {
        closeSession();
      }
      return;
    }
    String frame = batch.frames().get(index);
    try {
      session.getAsyncRemote().sendText(frame, result -> onFrameResult(batch, index, result));
    } catch (RuntimeException error) {
      // native 调用同步抛错：帧肯定未在途，收敛到唯一关闭路径。
      onSendFailed();
    }
  }

  /** 仅当前在途帧（batch+index 身份）的成功回调推进下一帧；批最后一帧成功才 complete 释放整包预算。 */
  private void onFrameResult(NotificationOutbox.Batch batch, int index, SendResult result) {
    boolean failed = false;
    boolean schedule = false;
    synchronized (signal) {
      if (stopped) {
        return; // 失败/关闭后的迟到回调：释放已由 link.close() 完成，不重试。
      }
      if (activeBatch != batch || activeIndex != index) {
        return; // 迟到或重复的旧帧回调：身份不匹配，丢弃。
      }
      if (!result.isOK()) {
        stopped = true;
        stepPending = false;
        link.close();
        failed = true;
      } else {
        activeIndex = index + 1;
        if (index + 1 >= batch.frameCount()) {
          // 批内最后一帧成功：整包（含在途）预算此刻才释放。
          link.complete(batch, true);
          activeBatch = null;
        }
        schedule = true;
      }
    }
    if (failed) {
      submitClose();
      return;
    }
    if (schedule) {
      submitStep();
    }
  }

  /** native 发送失败（同步抛错或异步回调非 OK）：屏障新入队、释放 link，并在锁外真实关闭会话。 */
  private void onSendFailed() {
    boolean close;
    synchronized (signal) {
      close = !stopped;
      if (close) {
        stopped = true;
        stepPending = false;
        link.close();
      }
    }
    if (close) {
      submitClose();
    }
  }

  private void submitStep() {
    try {
      sendExecutor.execute(this::step);
    } catch (RuntimeException rejected) {
      onSendFailed();
    }
  }

  private void submitClose() {
    try {
      sendExecutor.execute(this::closeSession);
    } catch (RuntimeException rejected) {
      closeSession();
    }
  }

  private void closeSession() {
    if (!closeRequested.compareAndSet(false, true)) {
      return;
    }
    try {
      session.close(reason);
    } catch (Exception error) {
      // 连接已不可用
    }
  }
}
