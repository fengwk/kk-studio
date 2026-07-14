package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/** Serializes mutations through a fixed stripe set, avoiding unbounded path-key retention. */
final class FileMutations {

  private static final int STRIPES = 64;
  private static final ReentrantLock[] LOCKS =
      IntStream.range(0, STRIPES)
          .mapToObj(ignored -> new ReentrantLock())
          .toArray(ReentrantLock[]::new);

  private FileMutations() {}

  static ReentrantLock lock(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    ReentrantLock lock = LOCKS[Math.floorMod(normalized.hashCode(), STRIPES)];
    lock.lock();
    return lock;
  }

  static int stripeCount() {
    return STRIPES;
  }
}
