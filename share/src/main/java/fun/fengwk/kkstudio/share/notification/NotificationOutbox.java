package fun.fengwk.kkstudio.share.notification;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-safe bounded fair cursor queue over complete logical packets, shared by every transport's
 * transient sender. It owns only scheduling: it reserves the whole logical budget from {@link
 * #offer} until the final native batch completes, hands out at most one in-flight {@link Batch} of
 * at most {@code sendBatchFrames} encoded fragments, and rotates an unfinished packet to the tail
 * so a large body cannot monopolize the queue.
 *
 * <p>It never performs I/O, retries, callbacks or payload logging, and it reuses the shared {@link
 * NotificationCarrier} framing instead of defining a second wire format. A failed batch drops its
 * logical packet and releases the reservation; success advances the cursor and only the final
 * fragment releases the packet. Budgets come exclusively from {@link NotificationLimits}.
 */
public final class NotificationOutbox {
  private static final class Cursor {
    final NotificationPacket packet;
    int nextIndex;

    Cursor(NotificationPacket packet) {
      this.packet = packet;
    }
  }

  /**
   * Read-only view of one polled send batch: immutable packet metadata, a bounded immutable frame
   * list and the object identity that {@link #complete} accepts. {@code toString} never renders the
   * frames, the body or its Base64.
   */
  public static final class Batch {
    private final UUID publisher;
    private final UUID target;
    private final String topic;
    private final UUID messageId;
    private final int totalBytes;
    private final int startIndex;
    private final List<String> frames;

    private Batch(NotificationPacket packet, int startIndex, List<String> frames) {
      this.publisher = packet.publisher();
      this.target = packet.target();
      this.topic = packet.topic();
      this.messageId = packet.messageId();
      this.totalBytes = packet.byteLength();
      this.startIndex = startIndex;
      this.frames = List.copyOf(frames);
    }

    public UUID publisher() {
      return publisher;
    }

    /** Null broadcasts; a non-null value selects one receiver identity. */
    public UUID target() {
      return target;
    }

    public String topic() {
      return topic;
    }

    public UUID messageId() {
      return messageId;
    }

    public int totalBytes() {
      return totalBytes;
    }

    public int frameCount() {
      return frames.size();
    }

    /** Immutable encoded fragments; never exposes the logical body. */
    public List<String> frames() {
      return frames;
    }

    @Override
    public String toString() {
      return "NotificationOutbox.Batch[topic="
          + topic
          + ", target="
          + target
          + ", messageId="
          + messageId
          + ", frames="
          + frames.size()
          + ", totalBytes="
          + totalBytes
          + "]";
    }
  }

  private final NotificationLimits limits;
  private final ArrayDeque<Cursor> queued = new ArrayDeque<>();
  private int pendingBytes;
  private int pendingMessages;
  private Cursor active;
  private Batch activeBatch;
  private boolean closed;

  public NotificationOutbox(NotificationLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /**
   * Reserves the whole logical packet against the queue and byte budgets. Returns false for an
   * oversized packet, a full queue, an exhausted byte budget or a closed outbox; a null packet is a
   * fixed validation error. Does not retry, log payload or invoke callbacks.
   */
  public synchronized boolean offer(NotificationPacket message) {
    Objects.requireNonNull(message, "message");
    if (closed
        || message.byteLength() > limits.maxMessageBytes()
        || pendingMessages >= limits.queueCapacity()
        || message.byteLength() > limits.pendingBytes() - pendingBytes) {
      return false;
    }
    queued.addLast(new Cursor(message));
    pendingBytes += message.byteLength();
    pendingMessages++;
    return true;
  }

  /**
   * Returns the next bounded send batch, or empty while closed, empty or already serving an active
   * batch. It encodes at most {@code sendBatchFrames} fragments of the head packet under the lock.
   */
  public synchronized Optional<Batch> pollBatch() {
    if (closed || active != null || queued.isEmpty()) {
      return Optional.empty();
    }
    Cursor cursor = queued.removeFirst();
    active = cursor;
    int total = NotificationCarrier.count(cursor.packet.byteLength());
    int end = (int) Math.min((long) total, (long) cursor.nextIndex + limits.sendBatchFrames());
    List<String> frames = new ArrayList<>(end - cursor.nextIndex);
    for (int index = cursor.nextIndex; index < end; index++) {
      frames.add(NotificationCarrier.chunk(cursor.packet, index).encode());
    }
    activeBatch = new Batch(cursor.packet, cursor.nextIndex, frames);
    return Optional.of(activeBatch);
  }

  /**
   * Completes the active batch by identity. Wrong, stale, foreign or duplicate batches are rejected
   * without releasing another packet's reservation. Success advances the cursor and rotates an
   * unfinished packet to the tail, releasing the reservation only on the final fragment; failure
   * drops the logical packet and releases it with no retry.
   */
  public synchronized boolean complete(Batch batch, boolean success) {
    if (batch == null || batch != activeBatch) {
      return false;
    }
    Cursor cursor = active;
    active = null;
    activeBatch = null;
    if (!success) {
      release(cursor.packet);
      return true;
    }
    int end = batch.startIndex + batch.frameCount();
    if (end >= NotificationCarrier.count(cursor.packet.byteLength())) {
      release(cursor.packet);
    } else {
      cursor.nextIndex = end;
      queued.addLast(cursor);
    }
    return true;
  }

  /** Measured queued plus in-flight logical packets. */
  public synchronized int pendingMessages() {
    return pendingMessages;
  }

  /** Measured queued plus in-flight logical bytes. */
  public synchronized int pendingBytes() {
    return pendingBytes;
  }

  /** Releases every queued and in-flight packet; the outbox still accepts further offers. */
  public synchronized void clear() {
    queued.clear();
    active = null;
    activeBatch = null;
    pendingBytes = 0;
    pendingMessages = 0;
  }

  /** Permanently refuses new offers and releases every queued and in-flight packet. */
  public synchronized void close() {
    closed = true;
    clear();
  }

  private void release(NotificationPacket packet) {
    pendingBytes -= packet.byteLength();
    pendingMessages--;
  }
}
