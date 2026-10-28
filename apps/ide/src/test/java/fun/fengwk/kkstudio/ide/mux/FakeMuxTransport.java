package fun.fengwk.kkstudio.ide.mux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 可控物理写 fake：记录完整帧，并允许测试逐帧完成或失败，证明单写入者与 fail-stop。 */
final class FakeMuxTransport implements MuxTransport {

  private final Object monitor = new Object();
  private final List<byte[]> written = new ArrayList<>();
  private final Deque<CompletableFuture<Void>> pending = new ArrayDeque<>();
  private final boolean autoComplete;
  private boolean closed;
  private int closeCount;
  private int maxPending;

  FakeMuxTransport() {
    this(true);
  }

  FakeMuxTransport(boolean autoComplete) {
    this.autoComplete = autoComplete;
  }

  @Override
  public CompletionStage<Void> write(byte[] frame) {
    synchronized (monitor) {
      if (closed) {
        throw new IllegalStateException("transport is closed");
      }
      written.add(frame.clone());
      monitor.notifyAll();
      if (autoComplete) {
        return CompletableFuture.completedFuture(null);
      }
      CompletableFuture<Void> completion = new CompletableFuture<>();
      pending.addLast(completion);
      maxPending = Math.max(maxPending, pending.size());
      return completion;
    }
  }

  @Override
  public void close() {
    synchronized (monitor) {
      closed = true;
      closeCount++;
      monitor.notifyAll();
    }
  }

  List<byte[]> written() {
    synchronized (monitor) {
      List<byte[]> copy = new ArrayList<>(written.size());
      for (byte[] frame : written) {
        copy.add(frame.clone());
      }
      return copy;
    }
  }

  int writeCount() {
    synchronized (monitor) {
      return written.size();
    }
  }

  int pendingCount() {
    synchronized (monitor) {
      return pending.size();
    }
  }

  int maxPendingCount() {
    synchronized (monitor) {
      return maxPending;
    }
  }

  boolean isClosed() {
    synchronized (monitor) {
      return closed;
    }
  }

  int closeCount() {
    synchronized (monitor) {
      return closeCount;
    }
  }

  void completeNext() {
    CompletableFuture<Void> completion;
    synchronized (monitor) {
      completion = pending.pollFirst();
    }
    if (completion == null) {
      throw new AssertionError("no pending physical write");
    }
    completion.complete(null);
  }

  void failNext(Throwable failure) {
    CompletableFuture<Void> completion;
    synchronized (monitor) {
      completion = pending.pollFirst();
    }
    if (completion == null) {
      throw new AssertionError("no pending physical write");
    }
    completion.completeExceptionally(failure);
  }

  void awaitWrites(int count) {
    await(() -> written.size() >= count, "physical writes >= " + count);
  }

  void awaitPending(int count) {
    await(() -> pending.size() >= count, "pending physical writes >= " + count);
  }

  private void await(BooleanSupplier condition, String description) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    synchronized (monitor) {
      while (!condition.getAsBoolean()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new AssertionError("timed out waiting for " + description);
        }
        try {
          long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
          monitor.wait(Math.max(1, millis));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new AssertionError("interrupted waiting for " + description, error);
        }
      }
    }
  }
}
