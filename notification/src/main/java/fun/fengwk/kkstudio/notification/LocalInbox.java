package fun.fengwk.kkstudio.notification;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.share.notification.Notification;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Independent serial subscriber workers; reader and commit hooks only perform bounded mailbox work.
 */
@Slf4j
final class LocalInbox implements AutoCloseable {
  private record Entry(Object payload, int bytes) {}

  private final NotificationLimits limits;
  private final Map<String, List<Mailbox<?>>> mailboxes = new HashMap<>();
  private final AtomicInteger reservedBytes = new AtomicInteger();
  private int subscribers;
  private boolean closed;

  LocalInbox(NotificationLimits limits) {
    this.limits = limits;
  }

  synchronized <T> NotificationSubscription subscribe(
      NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync) {
    if (closed) {
      throw new IllegalStateException("inbox is closed");
    }
    if (subscribers >= limits.queueCapacity()) {
      throw new IllegalStateException("notification subscriber budget exhausted");
    }
    Mailbox<T> mailbox = new Mailbox<>(topic, consumer, resync);
    mailboxes.computeIfAbsent(topic.name(), ignored -> new ArrayList<>()).add(mailbox);
    subscribers++;
    mailbox.worker.start();
    return mailbox;
  }

  synchronized void accept(Notification<?> notification, int bytes) {
    if (!closed) {
      for (Mailbox<?> mailbox : mailboxes.getOrDefault(notification.topic().name(), List.of())) {
        mailbox.offer(new Entry(notification.payload(), bytes));
      }
    }
  }

  synchronized void resync(String topic) {
    if (!closed) {
      for (Mailbox<?> mailbox : mailboxes.getOrDefault(topic, List.of())) {
        mailbox.recover();
      }
    }
  }

  synchronized void resyncAll() {
    if (!closed) {
      mailboxes.values().forEach(list -> list.forEach(Mailbox::recover));
    }
  }

  @Override
  public void close() {
    List<Mailbox<?>> closing = new ArrayList<>();
    synchronized (this) {
      if (!closed) {
        closed = true;
        mailboxes.values().forEach(closing::addAll);
        closing.forEach(Mailbox::shutdown);
        mailboxes.clear();
        subscribers = 0;
      }
    }
    for (Mailbox<?> mailbox : closing) {
      mailbox.awaitClosed();
    }
  }

  private void unsubscribe(Mailbox<?> mailbox) {
    synchronized (this) {
      List<Mailbox<?>> list = mailboxes.get(mailbox.topic.name());
      if (list != null && list.remove(mailbox)) {
        subscribers--;
        if (list.isEmpty()) {
          mailboxes.remove(mailbox.topic.name());
        }
      }
      mailbox.shutdown();
    }
    mailbox.awaitClosed();
  }

  private boolean reserve(int bytes) {
    int previous;
    do {
      previous = reservedBytes.get();
      if (bytes > limits.pendingBytes() - previous) {
        return false;
      }
    } while (!reservedBytes.compareAndSet(previous, previous + bytes));
    return true;
  }

  private final class Mailbox<T> implements NotificationSubscription {
    final NotificationTopic<T> topic;
    final Consumer<T> consumer;
    final Runnable resync;
    final ArrayDeque<Entry> queue = new ArrayDeque<>();
    final Thread worker;
    State state = State.ACTIVE;
    boolean recovery;
    int bytes;

    Mailbox(NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync) {
      this.topic = topic;
      this.consumer = consumer;
      this.resync = resync;
      this.worker = Thread.ofVirtual().name("notification-" + topic.name()).unstarted(this::drain);
    }

    synchronized void offer(Entry entry) {
      if (state != State.ACTIVE) {
        return;
      }
      if (queue.size() >= limits.queueCapacity() || !reserve(entry.bytes())) {
        recover();
      } else {
        queue.addLast(entry);
        bytes += entry.bytes();
        notifyAll();
      }
    }

    synchronized void recover() {
      if (state == State.ACTIVE) {
        clearQueue();
        recovery = true;
        notifyAll();
      }
    }

    void drain() {
      try {
        while (true) {
          Entry entry;
          boolean recovering;
          synchronized (this) {
            while (state == State.ACTIVE && !recovery && queue.isEmpty()) {
              wait();
            }
            if (state != State.ACTIVE) {
              return;
            }
            recovering = recovery;
            recovery = false;
            entry = recovering ? null : queue.removeFirst();
            if (entry != null) {
              bytes -= entry.bytes();
            }
          }
          try {
            if (recovering) {
              resync.run();
            } else {
              deliver(entry);
            }
          } catch (RuntimeException error) {
            if (recovering) {
              synchronized (this) {
                if (state == State.ACTIVE) {
                  state = State.FAILED;
                }
                clearQueue();
              }
              // Do not log payloads or exception messages from domain codecs/handlers.
              log.warn(
                  "Notification recovery failed topic={} errorType={}",
                  topic.name(),
                  error.getClass().getSimpleName());
              return;
            }
            recover();
          } finally {
            if (entry != null) {
              reservedBytes.addAndGet(-entry.bytes());
            }
          }
        }
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }

    @SuppressWarnings("unchecked")
    void deliver(Entry entry) {
      consumer.accept((T) entry.payload());
    }

    @Override
    public synchronized State state() {
      return state;
    }

    @Override
    public void close() {
      unsubscribe(this);
    }

    synchronized void shutdown() {
      state = State.CLOSED;
      clearQueue();
      recovery = false;
      notifyAll();
      if (worker != Thread.currentThread()) {
        worker.interrupt();
      }
    }

    private void clearQueue() {
      reservedBytes.addAndGet(-bytes);
      queue.clear();
      bytes = 0;
    }

    void awaitClosed() {
      if (worker != Thread.currentThread()) {
        try {
          worker.join(5000);
          if (worker.isAlive()) {
            throw new IllegalStateException("notification consumer failed to stop");
          }
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted closing notification consumer");
        }
      }
    }
  }
}
