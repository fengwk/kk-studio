package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes write and edit operations targeting the same normalized file path. */
final class FileMutations {

  private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

  private FileMutations() {}

  static ReentrantLock lock(Path path) {
    ReentrantLock lock =
        LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new ReentrantLock());
    lock.lock();
    return lock;
  }
}
