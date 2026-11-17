package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadTreeNodeDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Coalesces one authoritative tree projection per subscribed root and emits only changed snapshots.
 */
final class ThreadTreeSnapshotPublisher {

  private final Executor executor;
  private final Function<UUID, List<HarnessThreadTreeNodeDTO>> projector;

  ThreadTreeSnapshotPublisher(
      Executor executor, Function<UUID, List<HarnessThreadTreeNodeDTO>> projector) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.projector = Objects.requireNonNull(projector, "projector");
  }

  Handle subscribe(
      UUID rootThreadId, Consumer<List<HarnessThreadTreeNodeDTO>> onSnapshot, Runnable onFailure) {
    return new Handle(rootThreadId, onSnapshot, onFailure);
  }

  final class Handle implements AutoCloseable {
    private final UUID rootThreadId;
    private final Consumer<List<HarnessThreadTreeNodeDTO>> onSnapshot;
    private final Runnable onFailure;
    private List<HarnessThreadTreeNodeDTO> lastSnapshot;
    private boolean active = true;
    private boolean running;
    private boolean dirty;
    private boolean forcePublish;

    private Handle(
        UUID rootThreadId,
        Consumer<List<HarnessThreadTreeNodeDTO>> onSnapshot,
        Runnable onFailure) {
      this.rootThreadId = Objects.requireNonNull(rootThreadId, "rootThreadId");
      this.onSnapshot = Objects.requireNonNull(onSnapshot, "onSnapshot");
      this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    void request() {
      request(false);
    }

    void request(boolean force) {
      synchronized (this) {
        if (!active) {
          return;
        }
        dirty = true;
        forcePublish |= force;
        if (running) {
          return;
        }
        running = true;
      }
      submit();
    }

    private void submit() {
      try {
        executor.execute(this::projectUntilClean);
      } catch (RuntimeException rejected) {
        synchronized (this) {
          if (!active) {
            running = false;
            dirty = false;
            return;
          }
          dirty = false;
        }
        onFailure.run();
        boolean retry;
        synchronized (this) {
          if (!active) {
            running = false;
            return;
          }
          retry = dirty;
          if (!retry) {
            running = false;
          }
        }
        if (retry) {
          submit();
        }
      }
    }

    private void projectUntilClean() {
      while (true) {
        synchronized (this) {
          if (!active) {
            running = false;
            return;
          }
          dirty = false;
        }
        List<HarnessThreadTreeNodeDTO> snapshot;
        try {
          snapshot = List.copyOf(projector.apply(rootThreadId));
        } catch (RuntimeException failure) {
          synchronized (this) {
            if (!active) {
              running = false;
              return;
            }
            if (dirty) {
              continue;
            }
          }
          onFailure.run();
          synchronized (this) {
            if (!active) {
              running = false;
              return;
            }
            if (dirty) {
              continue;
            }
            running = false;
            return;
          }
        }
        boolean publish;
        synchronized (this) {
          if (!active) {
            running = false;
            return;
          }
          publish = forcePublish || !snapshot.equals(lastSnapshot);
          if (publish) {
            forcePublish = false;
          }
        }
        if (publish) {
          try {
            onSnapshot.accept(snapshot);
          } catch (RuntimeException deliveryFailure) {
            // A partial fan-out is uncertain; report failure without creating a refresh loop.
            synchronized (this) {
              if (!active) {
                running = false;
                return;
              }
              forcePublish = true;
            }
            onFailure.run();
            synchronized (this) {
              if (!active) {
                running = false;
                return;
              }
              if (dirty) {
                continue;
              }
              running = false;
              return;
            }
          }
          synchronized (this) {
            if (!active) {
              running = false;
              return;
            }
            lastSnapshot = snapshot;
          }
        }
        synchronized (this) {
          if (!active) {
            running = false;
            return;
          }
          if (!dirty) {
            running = false;
            return;
          }
        }
      }
    }

    @Override
    public synchronized void close() {
      active = false;
      dirty = false;
    }
  }
}
