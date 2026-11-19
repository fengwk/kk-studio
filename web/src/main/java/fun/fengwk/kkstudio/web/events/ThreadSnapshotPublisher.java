package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Serializes authoritative projections per Thread and shares each multi-cursor read. */
final class ThreadSnapshotPublisher {

  private final Executor executor;
  private final BiFunction<UUID, List<UUID>, List<HarnessThreadSnapshotDTO>> projector;
  private final ScheduledExecutorService scheduler;
  private final Map<UUID, State> states = new LinkedHashMap<>();

  ThreadSnapshotPublisher(
      Executor executor,
      BiFunction<UUID, List<UUID>, List<HarnessThreadSnapshotDTO>> projector,
      ScheduledExecutorService scheduler) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.projector = Objects.requireNonNull(projector, "projector");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  Handle subscribe(
      UUID threadId,
      UUID afterEntryId,
      Consumer<HarnessThreadSnapshotDTO> onSnapshot,
      Runnable onFailure) {
    return new Subscriber(threadId, afterEntryId, onSnapshot, onFailure);
  }

  private final class State {
    private final UUID threadId;
    private final Map<Subscriber, Subscriber> subscribers = new LinkedHashMap<>();
    private boolean running;
    private boolean projecting;
    private boolean dirty;
    private boolean failureDispatching;
    private long requestSequence;
    private ScheduledFuture<?> freshnessTask;

    private State(UUID threadId) {
      this.threadId = threadId;
    }

    private void request() {
      long submissionSequence;
      synchronized (states) {
        if (subscribers.isEmpty()) {
          return;
        }
        requestSequence++;
        if (running || failureDispatching) {
          if (projecting || failureDispatching) {
            dirty = true;
          }
          return;
        }
        dirty = true;
        running = true;
        submissionSequence = requestSequence;
      }
      submit(submissionSequence);
    }

    private void submit(long submissionSequence) {
      try {
        executor.execute(this::projectUntilClean);
      } catch (RuntimeException rejected) {
        List<Subscriber> batch;
        synchronized (states) {
          running = false;
          projecting = false;
          failureDispatching = true;
          batch = new ArrayList<>(subscribers.keySet());
        }
        reportFailure(batch, submissionSequence);
      }
    }

    private void projectUntilClean() {
      while (true) {
        List<BatchEntry> batch;
        List<UUID> cursors;
        long batchSequence;
        synchronized (states) {
          if (subscribers.isEmpty()) {
            running = false;
            projecting = false;
            cleanupIfUnused();
            return;
          }
          dirty = false;
          projecting = true;
          batchSequence = requestSequence;
          batch =
              subscribers.keySet().stream()
                  .map(
                      subscriber ->
                          new BatchEntry(
                              subscriber, subscriber.afterEntryId, subscriber.generation))
                  .toList();
          cursors = batch.stream().map(BatchEntry::cursor).distinct().toList();
        }

        Instant projectionStartedAt = Instant.now();
        List<HarnessThreadSnapshotDTO> snapshots;
        Map<UUID, HarnessThreadSnapshotDTO> snapshotsByCursor = new LinkedHashMap<>();
        try {
          snapshots = projector.apply(threadId, cursors);
          if (snapshots.size() != cursors.size()) {
            throw new IllegalStateException("snapshot projection count does not match cursors");
          }
          for (int i = 0; i < cursors.size(); i++) {
            snapshotsByCursor.put(cursors.get(i), snapshots.get(i));
          }
        } catch (RuntimeException failure) {
          synchronized (states) {
            running = false;
            projecting = false;
            failureDispatching = true;
            cancelFreshness();
          }
          reportFailure(batch.stream().map(BatchEntry::subscriber).toList(), batchSequence);
          return;
        }

        try {
          scheduleFreshness(snapshots, projectionStartedAt);
        } catch (RuntimeException schedulingFailure) {
          synchronized (states) {
            running = false;
            projecting = false;
            failureDispatching = true;
            cancelFreshness();
          }
          reportFailure(batch.stream().map(BatchEntry::subscriber).toList(), batchSequence);
          return;
        }
        for (BatchEntry entry : batch) {
          Subscriber subscriber = entry.subscriber();
          synchronized (states) {
            if (subscriber.closed
                || !subscriber.active
                || !subscribers.containsKey(subscriber)
                || subscriber.generation != entry.generation()) {
              continue;
            }
          }
          HarnessThreadSnapshotDTO snapshot = snapshotsByCursor.get(entry.cursor());
          try {
            subscriber.onSnapshot.accept(snapshot);
            synchronized (states) {
              if (!subscriber.closed
                  && subscribers.containsKey(subscriber)
                  && subscriber.generation == entry.generation()) {
                String headEntryId = snapshot.getThread().getHeadEntryId();
                subscriber.afterEntryId = headEntryId == null ? null : UUID.fromString(headEntryId);
              }
            }
          } catch (RuntimeException deliveryFailure) {
            try {
              subscriber.onFailure.run();
            } catch (RuntimeException ignored) {
              // One failed connection must not prevent other subscribers from receiving the batch.
            }
          }
        }

        synchronized (states) {
          if (!dirty) {
            running = false;
            projecting = false;
            cleanupIfUnused();
            return;
          }
        }
      }
    }

    private void scheduleFreshness(
        List<HarnessThreadSnapshotDTO> snapshots, Instant projectionStartedAt) {
      Instant next =
          snapshots.stream()
              .flatMap(snapshot -> snapshot.getToolInvocations().stream())
              .map(ToolInvocationDTO::getEnvironmentWaitFreshnessAt)
              .filter(Objects::nonNull)
              // The boundary can pass while a projection is still running; request again in that
              // case.
              .filter(value -> value.isAfter(projectionStartedAt))
              .min(Instant::compareTo)
              .orElse(null);
      synchronized (states) {
        cancelFreshness();
        if (next != null && !subscribers.isEmpty()) {
          long delay = Math.max(1, Duration.between(Instant.now(), next).toMillis());
          freshnessTask = scheduler.schedule(this::request, delay, TimeUnit.MILLISECONDS);
        }
      }
    }

    private void reportFailure(List<Subscriber> batch, long failureSequence) {
      for (Subscriber subscriber : batch) {
        synchronized (states) {
          if (subscriber.closed || !subscriber.active || !subscribers.containsKey(subscriber)) {
            continue;
          }
        }
        try {
          subscriber.onFailure.run();
        } catch (RuntimeException ignored) {
          // Failure callbacks are isolated; later real requests can still proceed.
        }
      }
      boolean submitAgain = false;
      synchronized (states) {
        failureDispatching = false;
        if (requestSequence > failureSequence && !subscribers.isEmpty()) {
          dirty = true;
          running = true;
          submitAgain = true;
        } else {
          cleanupIfUnused();
        }
      }
      if (submitAgain) {
        long nextSequence;
        synchronized (states) {
          nextSequence = requestSequence;
        }
        submit(nextSequence);
      }
    }

    private void cleanupIfUnused() {
      if (subscribers.isEmpty()) {
        cancelFreshness();
        states.remove(threadId, this);
      }
    }

    private void cancelFreshness() {
      if (freshnessTask != null) {
        freshnessTask.cancel(false);
        freshnessTask = null;
      }
    }
  }

  private record BatchEntry(Subscriber subscriber, UUID cursor, long generation) {}

  interface Handle extends AutoCloseable {
    void activate();

    void request(boolean full);

    @Override
    void close();
  }

  private final class Subscriber implements Handle {
    private final UUID threadId;
    private final Consumer<HarnessThreadSnapshotDTO> onSnapshot;
    private final Runnable onFailure;
    private UUID afterEntryId;
    private State state;
    private long generation;
    private boolean active;
    private boolean closed;

    private Subscriber(
        UUID threadId,
        UUID afterEntryId,
        Consumer<HarnessThreadSnapshotDTO> onSnapshot,
        Runnable onFailure) {
      this.threadId = Objects.requireNonNull(threadId, "threadId");
      this.afterEntryId = afterEntryId;
      this.onSnapshot = Objects.requireNonNull(onSnapshot, "onSnapshot");
      this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    @Override
    public void activate() {
      synchronized (states) {
        if (closed || active) {
          return;
        }
        state = states.computeIfAbsent(threadId, State::new);
        active = true;
        state.subscribers.put(this, this);
      }
      state.request();
    }

    @Override
    public void request(boolean full) {
      synchronized (states) {
        if (closed || !active) {
          return;
        }
        if (full) {
          afterEntryId = null;
          generation++;
        }
      }
      state.request();
    }

    @Override
    public void close() {
      synchronized (states) {
        if (closed) {
          return;
        }
        closed = true;
        if (!active) {
          return;
        }
        state.subscribers.remove(this);
        if (state.subscribers.isEmpty() && !state.running) {
          state.cleanupIfUnused();
        }
      }
    }
  }
}
