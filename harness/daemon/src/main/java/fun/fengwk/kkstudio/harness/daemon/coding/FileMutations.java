package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/** 通过固定的 stripe 集合串行化变更，避免无界保留 path key。 */
final class FileMutations {

  private static final int STRIPES = 64;
  private static final ReentrantLock[] LOCKS =
      IntStream.range(0, STRIPES)
          .mapToObj(ignored -> new ReentrantLock())
          .toArray(ReentrantLock[]::new);

  private FileMutations() {}

  static ReentrantLock lock(Path path) {
    ReentrantLock lock = LOCKS[stripeIndex(path)];
    lock.lock();
    return lock;
  }

  /** 按升序获取每个不同的 stripe，使多路径变更不会死锁。 */
  static List<ReentrantLock> lockAll(Path... paths) {
    int[] stripes =
        Arrays.stream(paths).mapToInt(FileMutations::stripeIndex).distinct().sorted().toArray();
    List<ReentrantLock> locks = new ArrayList<>(stripes.length);
    try {
      for (int stripe : stripes) {
        ReentrantLock lock = LOCKS[stripe];
        lock.lock();
        locks.add(lock);
      }
      return locks;
    } catch (RuntimeException error) {
      unlockAll(locks);
      throw error;
    }
  }

  static void unlockAll(List<ReentrantLock> locks) {
    for (int index = locks.size() - 1; index >= 0; index--) {
      locks.get(index).unlock();
    }
  }

  static int stripeIndex(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    return Math.floorMod(normalized.hashCode(), STRIPES);
  }

  static int stripeCount() {
    return STRIPES;
  }
}
