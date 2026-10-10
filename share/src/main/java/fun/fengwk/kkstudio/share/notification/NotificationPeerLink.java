package fun.fengwk.kkstudio.share.notification;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One per-connection framing link over the shared carrier, reassembler and outbox. It is the single
 * physical format for both WebSocket endpoints: every logical message, including {@code count=1},
 * is carried as {@link NotificationCarrier} fragments with a fixed topic.
 *
 * <p><b>Peer freeze:</b> a connection has no out-of-band handshake metadata, so the first valid
 * fragment that strictly decodes with the fixed topic and a {@code self}/{@code *} target freezes
 * the peer publisher UUID; a later fragment from a different publisher, a wrong topic or a foreign
 * target is a fatal violation. Until a peer is frozen outbound messages broadcast ({@code *});
 * after that they target the peer. Own echoes are dropped by the shared decoder before Base64
 * decoding, and a disconnect releases the peer together with the reassembler.
 *
 * <p><b>Bounded and reentrancy-safe:</b> the reassembler callbacks only collect the completed
 * packet or a resync marker; {@link #accept} delivers the reassembled UTF-8 body and requests the
 * single close path outside every monitor. A missing fragment, an over-budget or expired message,
 * an invalid frame, a peer/topic/target violation and a periodic expiry all funnel into that one
 * close request. Both callbacks are invoked outside every internal monitor and may re-enter the
 * link (a receiving endpoint may reply through {@link #offer} from its delivery callback). {@link
 * #close()} cancels the expiry task, clears the peer and releases every reserved byte without
 * retry.
 *
 * <p>Inbound bytes must be valid UTF-8: an invalid sequence is a fatal violation that closes the
 * link without any delivery.
 *
 * <p>It performs no I/O: the owning endpoint owns the native read/write, deadlines and reconnect
 * policy, and drives {@link #pollBatch()} / {@link #complete}.
 */
public final class NotificationPeerLink implements AutoCloseable {

  /**
   * The single fixed carrier topic of the daemon WebSocket wire, shared by the Daemon client and
   * the environment daemon endpoint. The logical wire version lives inside the reassembled JSON.
   */
  public static final String DAEMON_TOPIC = "daemon.v3";

  private final UUID self;
  private final String topic;
  private final NotificationLimits limits;
  private final Consumer<String> delivery;
  private final Runnable closeRequest;
  private final NotificationReassembler reassembler;
  private final NotificationOutbox outbox;
  private final Object lock = new Object();
  private final ScheduledFuture<?> expireTask;
  private NotificationPacket completed;
  private boolean resync;
  private UUID peer;
  private boolean closed;

  /**
   * Creates one link. {@code delivery} receives a fully reassembled UTF-8 body and {@code
   * closeRequest} is invoked at most once per fatal condition; both are called outside every
   * internal monitor and may re-enter the link. The required {@code expireTimer} drives the
   * periodic reassembly sweep at the reassembly timeout interval; there is no disabled-expiry mode.
   */
  public NotificationPeerLink(
      UUID self,
      String topic,
      NotificationLimits limits,
      ScheduledExecutorService expireTimer,
      Consumer<String> delivery,
      Runnable closeRequest) {
    this.self = Objects.requireNonNull(self, "self");
    this.topic = NotificationCarrier.requireTopic(topic);
    this.limits = Objects.requireNonNull(limits, "limits");
    this.delivery = Objects.requireNonNull(delivery, "delivery");
    this.closeRequest = Objects.requireNonNull(closeRequest, "closeRequest");
    this.reassembler =
        new NotificationReassembler(
            self,
            limits,
            System::nanoTime,
            packet -> completed = packet,
            ignoredTopic -> resync = true);
    this.outbox = new NotificationOutbox(limits);
    long period = Math.max(1L, limits.reassemblyTimeout().toMillis());
    this.expireTask =
        Objects.requireNonNull(expireTimer, "expireTimer")
            .scheduleWithFixedDelay(this::expire, period, period, TimeUnit.MILLISECONDS);
  }

  /** Frozen peer publisher; null until the first valid inbound fragment. */
  public UUID peer() {
    synchronized (lock) {
      return peer;
    }
  }

  /** Whether the link has been torn down by a fatal violation or an explicit close. */
  public boolean isClosed() {
    synchronized (lock) {
      return closed;
    }
  }

  /**
   * Validates one physical frame, freezes the peer, reassembles and delivers a complete logical
   * body. Own echoes and incomplete fragments are ignored; any fatal condition funnels into the
   * single close request. Never throws for rejected input.
   */
  public void accept(String rawFrame) {
    Objects.requireNonNull(rawFrame, "rawFrame");
    String delivered = null;
    boolean violation = false;
    synchronized (lock) {
      if (closed) {
        return;
      }
      NotificationCarrier frame = null;
      try {
        frame = NotificationCarrier.decode(rawFrame, limits, self);
      } catch (IllegalArgumentException invalid) {
        violation = true;
      }
      if (!violation && frame == null) {
        // Own echo: discarded by the shared decoder before any reassembly allocation.
        return;
      }
      if (!violation
          && (!topic.equals(frame.topic())
              || (frame.target() != null && !self.equals(frame.target())))) {
        violation = true;
      }
      if (!violation) {
        UUID current = peer;
        if (current == null) {
          peer = frame.publisher();
        } else if (!current.equals(frame.publisher())) {
          violation = true;
        }
      }
      if (!violation) {
        completed = null;
        resync = false;
        reassembler.accept(frame);
        NotificationPacket message = completed;
        completed = null;
        if (resync) {
          violation = true;
        }
        resync = false;
        if (message != null && !violation) {
          String decoded = strictUtf8(message.bytes());
          if (decoded == null) {
            // Invalid UTF-8 is a fatal loss: close once, deliver nothing.
            violation = true;
          } else {
            delivered = decoded;
          }
        }
      }
      if (violation) {
        tearDownLocked();
      }
    }
    if (delivered != null) {
      delivery.accept(delivered);
    }
    if (violation) {
      closeRequest.run();
    }
  }

  /**
   * Sweeps expired reassembly. A dropped message is a fatal loss for this wire, so an expired
   * packet funnels into the single close request. Called periodically by the injected timer or
   * directly.
   */
  public void expire() {
    boolean violation;
    synchronized (lock) {
      if (closed) {
        return;
      }
      resync = false;
      reassembler.expire();
      violation = resync;
      resync = false;
      if (violation) {
        tearDownLocked();
      }
    }
    if (violation) {
      closeRequest.run();
    }
  }

  /**
   * Reserves one complete logical packet against the shared outbox. Returns false for a closed
   * link, an oversized body or an exhausted queue/byte budget; the caller retries nothing.
   */
  public boolean offer(UUID messageId, String logicalBody) {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(logicalBody, "logicalBody");
    // UTF-8 bytes are never fewer than the UTF-16 length, so an oversized logical size is rejected
    // before allocating the encoded array.
    if (logicalBody.length() > limits.maxMessageBytes()) {
      return false;
    }
    byte[] bytes = logicalBody.getBytes(StandardCharsets.UTF_8);
    synchronized (lock) {
      if (closed) {
        return false;
      }
      try {
        return outbox.offer(new NotificationPacket(self, peer, topic, messageId, bytes));
      } catch (IllegalArgumentException oversized) {
        return false;
      }
    }
  }

  /** Next bounded batch of encoded fragments, or empty while closed, drained or already active. */
  public Optional<NotificationOutbox.Batch> pollBatch() {
    synchronized (lock) {
      if (closed) {
        return Optional.empty();
      }
      return outbox.pollBatch();
    }
  }

  /**
   * Completes the active batch by identity: success advances the cursor and only the final batch
   * releases the logical packet; failure drops it with no retry.
   */
  public boolean complete(NotificationOutbox.Batch batch, boolean success) {
    synchronized (lock) {
      return outbox.complete(batch, success);
    }
  }

  /** Measured queued plus in-flight logical bytes; returns to zero after a full release. */
  public int pendingBytes() {
    synchronized (lock) {
      return outbox.pendingBytes();
    }
  }

  /** Whether the outbox still holds a queued or in-flight packet for {@link #pollBatch()}. */
  public boolean hasPending() {
    synchronized (lock) {
      return !closed && outbox.pendingMessages() > 0;
    }
  }

  /** Releases every queued and in-flight packet plus every reassembly byte; idempotent. */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      tearDownLocked();
    }
  }

  private void tearDownLocked() {
    closed = true;
    peer = null;
    expireTask.cancel(false);
    outbox.close();
    reassembler.clear();
  }

  /** Strictly decodes reassembled bytes; returns null for any malformed UTF-8 sequence. */
  private static String strictUtf8(byte[] bytes) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException malformed) {
      return null;
    }
  }

  @Override
  public String toString() {
    return "NotificationPeerLink[topic=" + topic + ", peer=" + peer + ", closed=" + closed + "]";
  }
}
