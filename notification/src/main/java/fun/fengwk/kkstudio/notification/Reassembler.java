package fun.fengwk.kkstudio.notification;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Reserves the entire logical size before accepting a fragment, with bounded completion tombstones.
 */
final class Reassembler {
  private record Key(UUID publisher, UUID id) {}

  private static final class Pending {
    final Carrier first;
    final byte[] bytes;
    final boolean[] received;
    final long deadline;
    int remaining;

    Pending(Carrier first, long deadline) {
      this.first = first;
      this.bytes = new byte[first.totalBytes()];
      this.received = new boolean[first.count()];
      this.deadline = deadline;
      this.remaining = first.count();
    }
  }

  private final UUID self;
  private final NotificationLimits limits;
  private final LongSupplier clock;
  private final Consumer<WireMessage> delivery;
  private final Consumer<String> resync;
  private final Map<Key, Pending> pending = new HashMap<>();
  private final Map<Key, Long> finished = new LinkedHashMap<>();
  private int reservedBytes;

  Reassembler(
      UUID self,
      NotificationLimits limits,
      LongSupplier clock,
      Consumer<WireMessage> delivery,
      Consumer<String> resync) {
    this.self = self;
    this.limits = limits;
    this.clock = clock;
    this.delivery = delivery;
    this.resync = resync;
  }

  synchronized void accept(Carrier frame) {
    // Own echoes are discarded before expiry, lookup, or any allocation.
    if (self.equals(frame.publisher())) {
      return;
    }
    if (frame.target() != null && !self.equals(frame.target())) {
      return;
    }
    expire();
    Key key = new Key(frame.publisher(), frame.messageId());
    if (finished.containsKey(key)) {
      return;
    }
    Pending message = pending.get(key);
    if (message == null) {
      if (pending.size() >= limits.reassemblyMessages()
          || frame.totalBytes() > limits.reassemblyBytes() - reservedBytes) {
        remember(key);
        resync.accept(frame.topic());
        return;
      }
      message = new Pending(frame, clock.getAsLong() + limits.reassemblyTimeout().toNanos());
      pending.put(key, message);
      reservedBytes += frame.totalBytes();
    } else if (!sameHeader(message.first, frame)) {
      reject(key, message);
      resync.accept(frame.topic());
      return;
    }
    int offset = frame.index() * Carrier.CHUNK_BYTES;
    if (message.received[frame.index()]) {
      if (!Arrays.equals(
          message.bytes,
          offset,
          offset + frame.bytes().length,
          frame.bytes(),
          0,
          frame.bytes().length)) {
        reject(key, message);
      }
      return;
    }
    System.arraycopy(frame.bytes(), 0, message.bytes, offset, frame.bytes().length);
    message.received[frame.index()] = true;
    if (--message.remaining == 0) {
      remove(key, message);
      remember(key);
      delivery.accept(
          new WireMessage(
              frame.publisher(), frame.target(), frame.topic(), frame.messageId(), message.bytes));
    }
  }

  synchronized void expire() {
    long now = clock.getAsLong();
    finished.values().removeIf(deadline -> deadline <= now);
    Iterator<Map.Entry<Key, Pending>> iterator = pending.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<Key, Pending> entry = iterator.next();
      Pending message = entry.getValue();
      if (message.deadline <= now) {
        iterator.remove();
        reservedBytes -= message.bytes.length;
        remember(entry.getKey());
        resync.accept(message.first.topic());
      }
    }
  }

  synchronized void clear() {
    pending.clear();
    finished.clear();
    reservedBytes = 0;
  }

  synchronized int reservedBytes() {
    return reservedBytes;
  }

  private void reject(Key key, Pending message) {
    remove(key, message);
    remember(key);
    resync.accept(message.first.topic());
  }

  private void remove(Key key, Pending message) {
    pending.remove(key);
    reservedBytes -= message.bytes.length;
  }

  private void remember(Key key) {
    if (finished.size() == limits.queueCapacity()) {
      finished.remove(finished.keySet().iterator().next());
    }
    finished.put(key, clock.getAsLong() + limits.reassemblyTimeout().toNanos());
  }

  private static boolean sameHeader(Carrier a, Carrier b) {
    return Objects.equals(a.target(), b.target())
        && a.topic().equals(b.topic())
        && a.count() == b.count()
        && a.totalBytes() == b.totalBytes();
  }
}
