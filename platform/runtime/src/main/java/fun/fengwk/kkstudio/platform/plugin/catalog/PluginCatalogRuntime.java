package fun.fengwk.kkstudio.platform.plugin.catalog;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 完整两域 Plugin 快照的运行期所有者，负责权威 epoch 对账、原子发布与租用退役。
 *
 * <p>{@link #refresh()} 串行回读权威状态，只在 epoch 前进时通过 {@link PluginCatalogBootstrap#restore}
 * 构建并校验完整候选。候选构建不占生命周期锁；发布前再次回读，过期候选独立关闭，等待下次对账。任一插件应用失败时保留整个旧视图，不部分发布。
 *
 * <p>发布、退役与 {@link #acquireCurrent()} 同锁。每个已发布快照持有一个所有者引用和若干活动租用，最后一个引用释放时才在锁外关闭。 {@link #close()}
 * 禁止新租用并释放所有者引用，不强杀活动使用者；通知、调度与消费者装配由组合根负责。
 *
 * <p>{@link RuntimeException} 转为固定受控错误码，日志只记录 epoch、阶段与异常类型；{@link Error} 保持原样传播，候选回收失败不得替代原始致命错误。
 */
@Slf4j
public final class PluginCatalogRuntime implements AutoCloseable {

  /** 权威回读失败、权威行缺失或仍为未初始化形态时的固定受控错误码。 */
  public static final String ERROR_AUTHORITY_UNAVAILABLE = "authority-unavailable";

  /** 候选构建失败时的固定受控错误码。 */
  public static final String ERROR_APPLY_FAILED = "apply-failed";

  private final PluginCatalogBootstrap bootstrap;

  private final PluginConfigurationRepository repository;

  private final BiFunction<String, PluginCredentialReference, PluginContext> contexts;

  private final List<HarnessContributor> builtins;

  private final List<CanvasFunctionAdapter> builtinAdapters;

  /** 生命周期锁：保护当前视图的引用计数、发布/退役、关闭标志与对外状态，只做短临界区。 */
  private final Object lifecycleLock = new Object();

  /** 对账串行锁：整个权威对账一次只跑一个，构建期只持有本锁，绝不持有生命周期锁。 */
  private final Object refreshLock = new Object();

  private View current;

  private long desiredEpoch;

  private String applicationError;

  private boolean closed;

  /**
   * @param initialSnapshot 启动期 {@link PluginCatalogBootstrap#bootstrap} 已冻结的完整快照，必须对应已初始化 epoch
   */
  public PluginCatalogRuntime(
      PluginCatalogBootstrap bootstrap,
      PluginConfigurationRepository repository,
      BiFunction<String, PluginCredentialReference, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters,
      PluginCatalogSnapshot initialSnapshot) {
    this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.builtins = List.copyOf(Objects.requireNonNull(builtins, "builtins"));
    this.builtinAdapters = List.copyOf(Objects.requireNonNull(builtinAdapters, "builtinAdapters"));
    Objects.requireNonNull(initialSnapshot, "initialSnapshot");
    if (initialSnapshot.epoch() <= PluginCatalogBootstrap.UNINITIALIZED_EPOCH) {
      throw new IllegalArgumentException("the initial snapshot must be an initialized epoch");
    }
    this.current = new View(initialSnapshot);
    this.desiredEpoch = initialSnapshot.epoch();
  }

  /**
   * 做一次权威对账：权威 epoch 前进时全量校验并一次发布，否则保持当前视图。
   *
   * <p>整个对账由 {@link #refreshLock} 串行：并发刷新只会有一次构建，后到的刷新看到已推进的权威 epoch 便不再
   * configure，因此不会用较旧的失败覆盖较新的已发布状态。 构建期只持有对账锁，租用、状态读取与关闭只走生命周期锁，绝不因此阻塞。
   *
   * <p>失败不改变已发布视图，只更新 {@link #state()} 的固定受控错误码；{@link Error} 原样传播。已 {@link #close()} 时不做任何发布。
   */
  public void refresh() {
    synchronized (refreshLock) {
      refreshSerialized();
    }
  }

  private void refreshSerialized() {
    PluginCatalogRecord authority;
    try {
      authority = repository.readCatalog();
    } catch (RuntimeException error) {
      log.warn(
          "Plugin catalog refresh failed: stage=authority error={}",
          error.getClass().getSimpleName());
      recordError(ERROR_AUTHORITY_UNAVAILABLE);
      return;
    }
    if (authority == null || authority.epoch() == PluginCatalogBootstrap.UNINITIALIZED_EPOCH) {
      log.warn("Plugin catalog refresh rejected: stage=authority epoch=uninitialized");
      recordError(ERROR_AUTHORITY_UNAVAILABLE);
      return;
    }

    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      desiredEpoch = Math.max(desiredEpoch, authority.epoch());
      if (authority.epoch() <= current.snapshot.epoch()) {
        // 相同 epoch 不 configure；乱序回读绝不降级已发布视图。只有确实已同步时才清掉旧错误，
        // 以免抹掉已知更高 desired 上的应用失败。
        if (desiredEpoch == current.snapshot.epoch()) {
          applicationError = null;
        }
        return;
      }
    }

    PluginCatalogSnapshot candidate;
    try {
      candidate = bootstrap.restore(authority, contexts, builtins, builtinAdapters);
    } catch (RuntimeException error) {
      log.warn(
          "Plugin catalog refresh failed: stage=apply epoch={} error={}",
          authority.epoch(),
          error.getClass().getSimpleName());
      recordError(ERROR_APPLY_FAILED);
      return;
    }

    // 构建不占生命周期锁：发布前复核权威 epoch，期间权威推进意味着本次候选已经过期。
    PluginCatalogRecord latest;
    try {
      latest = repository.readCatalog();
    } catch (RuntimeException error) {
      log.warn(
          "Plugin catalog refresh failed: stage=recheck epoch={} error={}",
          candidate.epoch(),
          error.getClass().getSimpleName());
      recordError(ERROR_AUTHORITY_UNAVAILABLE);
      candidate.close();
      return;
    } catch (Error error) {
      // 复核期致命错误：候选仍必须独立回收，回收期的次要 Error 绝不能遮蔽它。
      log.error(
          "Plugin catalog refresh failed fatally: stage=recheck epoch={} error={}",
          candidate.epoch(),
          error.getClass().getSimpleName());
      recordError(ERROR_AUTHORITY_UNAVAILABLE);
      throw closePreservingPrimary(candidate, error);
    }
    if (latest == null || latest.epoch() != candidate.epoch()) {
      log.info(
          "Plugin catalog refresh discarded a stale candidate: epoch={} authorityEpoch={}",
          candidate.epoch(),
          latest == null ? null : latest.epoch());
      if (latest != null) {
        recordDesired(latest.epoch());
      }
      candidate.close();
      return;
    }
    publish(candidate);
  }

  /** 在短锁内一次发布候选：同/更旧 epoch 已存在或已关闭时关闭候选而不采纳，退役快照在锁外关闭。 */
  private void publish(PluginCatalogSnapshot candidate) {
    PluginCatalogSnapshot retired = null;
    boolean published = false;
    synchronized (lifecycleLock) {
      if (!closed && candidate.epoch() > current.snapshot.epoch()) {
        View previous = current;
        current = new View(candidate);
        previous.holders--;
        if (previous.holders == 0) {
          retired = previous.snapshot;
        }
        desiredEpoch = Math.max(desiredEpoch, candidate.epoch());
        applicationError = null;
        published = true;
      }
    }
    if (!published) {
      log.info(
          "Plugin catalog refresh discarded an already applied candidate: epoch={}",
          candidate.epoch());
      candidate.close();
      return;
    }
    if (retired != null) {
      retired.close();
    }
  }

  /**
   * 租用当前已发布的完整快照。
   *
   * @return 幂等关闭的租用；租用期间该快照不会被退役关闭
   * @throws IllegalStateException 已 {@link #close()} 关闭时（关闭后不再授予新租用）
   */
  public Lease acquireCurrent() {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("plugin catalog runtime is closed");
      }
      View view = current;
      view.holders++;
      return new Lease(view);
    }
  }

  /** 不含实例与秘密的目录同步状态：最近一次权威回读的 epoch、当前已发布 epoch 与固定受控错误码。 */
  public RuntimeState state() {
    synchronized (lifecycleLock) {
      return new RuntimeState(desiredEpoch, current.snapshot.epoch(), applicationError);
    }
  }

  /**
   * 停止运行：撤销当前快照的所有者引用并禁止新的租用，但绝不强杀仍在使用中的实例。
   *
   * <p>已有租用不受影响；最后一个引用释放时关闭快照。幂等，关闭动作在短锁外执行。
   */
  @Override
  public void close() {
    PluginCatalogSnapshot released = null;
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      View view = current;
      view.holders--;
      if (view.holders == 0) {
        released = view.snapshot;
      }
    }
    if (released != null) {
      released.close();
    }
  }

  /**
   * 独立回收候选并保留原始致命错误：快照自身保证全部实例都被回收，回收期的次要 {@link Error} 绝不遮蔽原始致命错误。
   *
   * @return 调用方必须原样抛出的原始致命错误
   */
  private static Error closePreservingPrimary(PluginCatalogSnapshot candidate, Error primary) {
    try {
      candidate.close();
    } catch (Error cleanup) {
      log.error(
          "Plugin catalog candidate cleanup failed fatally: {}",
          cleanup.getClass().getSimpleName());
    }
    return primary;
  }

  /** 单调记录最近一次权威回读看到的 epoch：乱序回读绝不把对外状态回退。 */
  private void recordDesired(long epoch) {
    synchronized (lifecycleLock) {
      desiredEpoch = Math.max(desiredEpoch, epoch);
    }
  }

  /** 记录固定受控错误码；成功发布或已同步时由调用方清空。 */
  private void recordError(String error) {
    synchronized (lifecycleLock) {
      applicationError = error;
    }
  }

  /** 一次发布的活跃视图：1 个所有者引用加若干活动租用，计数归零时快照恰好关闭一次。 */
  private static final class View {

    private final PluginCatalogSnapshot snapshot;

    private int holders = 1;

    View(PluginCatalogSnapshot snapshot) {
      this.snapshot = snapshot;
    }
  }

  /** 已发布快照的租用：{@link #close()} 幂等，最后一个引用释放时快照关闭。 */
  public final class Lease implements AutoCloseable {

    private final View view;

    private boolean closedLease;

    private Lease(View view) {
      this.view = view;
    }

    /**
     * 租用期内完整的两域快照：其 epoch 是取租用当时的已发布 epoch，发布更新的 epoch 后本租用仍钉住旧快照，因此它可能落后于 {@link #state()} 最新的
     * {@code appliedEpoch}。
     */
    public PluginCatalogSnapshot snapshot() {
      return view.snapshot;
    }

    @Override
    public void close() {
      PluginCatalogSnapshot released = null;
      synchronized (lifecycleLock) {
        if (closedLease) {
          return;
        }
        closedLease = true;
        view.holders--;
        if (view.holders == 0) {
          released = view.snapshot;
        }
      }
      if (released != null) {
        released.close();
      }
    }
  }

  /**
   * 不含实例与秘密的目录同步状态。
   *
   * <p>{@code desiredEpoch} 是最近一次权威回读看到的 epoch，{@code appliedEpoch} 是当前已发布快照的 epoch；二者相等即已同步。
   * {@code applicationError} 只在最近一次对账失败时取 {@link #ERROR_AUTHORITY_UNAVAILABLE} 或 {@link
   * #ERROR_APPLY_FAILED}，其余为 {@code null}。
   */
  public record RuntimeState(long desiredEpoch, long appliedEpoch, String applicationError) {}
}
