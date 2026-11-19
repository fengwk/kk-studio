package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个资源的上游句柄、订阅者集合与激活前 early 缓冲。
 *
 * <p>所有可变字段只在该状态的监视器内读写：hub 的 {@code synchronized (state)} 与这里的锁是同一个对象。{@code retired} 一旦置位，
 * 迟到的上游回调一律丢弃，绝不向 detached state 累积。
 */
final class ApplicationEventResourceState {

  final ResourceKey key;
  final Set<ApplicationEventLocalSubscription> subscribers = new HashSet<>();
  final List<Signal> early = new ArrayList<>();
  AutoCloseable threadSnapshotInvalidationHandle;
  Runnable threadEnvironmentInvalidationRelease;
  final Map<ApplicationEventLocalSubscription, ThreadSnapshotPublisher.Handle>
      threadSnapshotHandles = new HashMap<>();
  AutoCloseable realtimeHandle;
  AutoCloseable canvasVersionHandle;
  Runnable invalidationRelease;
  ThreadTreeSnapshotPublisher.Handle threadTreeSnapshotHandle;
  long cursor;

  /** 已淘汰（最后释放/建立失败/hub close）：上游回调一律丢弃；只在状态锁内读写。 */
  boolean retired;

  /** early 已折叠为单个 Resync：首订阅加入前不再累积；只在状态锁内读写。 */
  boolean earlyCollapsed;

  ApplicationEventResourceState(ResourceKey key) {
    this.key = key;
  }
}
