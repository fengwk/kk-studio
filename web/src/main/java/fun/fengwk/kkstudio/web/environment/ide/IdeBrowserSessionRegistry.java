package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeViewAuthorizationListener;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * IDE 浏览器会话与受管 workspace 授权登记。
 *
 * <p><b>两个互不派生的生命周期：</b>
 *
 * <ul>
 *   <li><b>cookie 有效期</b>：bootstrap 消费时设置/续期的 host-only 凭据，过期只作废该 cookie；
 *   <li><b>视图授权</b>：严格跟随 Daemon 的真实视图事件（{@link IdeViewAuthorizationListener#viewOpened}/ {@link
 *       IdeViewAuthorizationListener#viewClosed}）与代际结束（{@link #closeGeneration}）。
 * </ul>
 *
 * <p>因此 cookie 过期 <em>不会</em> 撤销仍然打开着的视图，也绝不用空闲超时推断视图关闭（会误杀仍在使用的多面板视图）；反之，视图关闭或代际结束会立即撤销 授权并使
 * cookie 失效。代际即 Daemon 实例：同一实例断线重连时 code-server 与视图都还在，cookie 与授权保留；另一个实例在本节点接管时整代关闭。每个 Environment
 * 在本节点至多一个代际，同一代际只保留一个 cookie：多次 bootstrap（多面板、多 workspace）复用同一 cookie，其可访问的 workspace
 * 是该代际当前打开的视图集合，映射始终与真实视图生命周期一致。
 *
 * <p><b>并发模型：</b>全部公共元数据由单一 {@link #lock} 串行访问（并存代际 ≤ {@value #MAX_GENERATIONS}、单代际视图 ≤ {@value
 * #MAX_VIEWS_PER_GENERATION}，规模小且操作是纯内存映射读写），因此不存在「先 get 再 put」的非原子窗口，也不需要多层并发容器。
 *
 * <p><b>超界语义：</b>代际或视图超界一律显式抛 {@link IllegalStateException}，绝不静默丢弃他人视图、也绝不伪成功（调用方可在 Native 副作用之前 用
 * {@link #ensureViewCapacity} 预检）。
 *
 * <p>本登记不是用户级 ACL：同一 Environment 的各视图共享同一 cookie 属预期行为，这里保证的只是「cookie ↔ 代际」与「视图 → workspace」映射与真实
 * 生命周期一致。
 */
@Component
public final class IdeBrowserSessionRegistry implements IdeViewAuthorizationListener {

  /** 一个浏览器 IDE 会话：绑定代际，并给出该代际当前的受管 workspace 授权视图。 */
  public record Session(DaemonConnectionIdentity identity, Set<String> authorizedWorkspaces) {

    public Session {
      identity = Objects.requireNonNull(identity, "identity");
      authorizedWorkspaces = Set.copyOf(authorizedWorkspaces);
    }

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }

    /** 该代际是否已授权访问某个受管 workspace（IDE 根路径上的 {@code workspace}/{@code folder} 只能取受管值）。 */
    public boolean authorizesWorkspace(String workspacePath) {
      return workspacePath != null && authorizedWorkspaces.contains(workspacePath);
    }
  }

  private static final int TOKEN_BYTES = 32;

  /**
   * 单代际的视图授权上界（内存安全兜底）。
   *
   * <p>真实视图数量由 Daemon 的 {@code OPEN_VIEW}/{@code CLOSE_VIEW} 生命周期决定；本仓库只有 Daemon 的命令契约 （{@code
   * IdeAction.OPEN_VIEW}），没有 Daemon 侧视图上限常量可引用，因此这里不假设 Daemon 的具体额度：超界显式失败（绝不伪成功）， 调用方也可在 Native
   * 副作用之前用 {@link #ensureViewCapacity} 预检。
   */
  static final int MAX_VIEWS_PER_GENERATION = 64;

  /** 并存代际（会话）上界；超界显式失败，不淘汰他人会话。 */
  static final int MAX_GENERATIONS = 4096;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;

  /** 以下两个映射与其中每个 {@link GenerationState} 都由 {@link #lock} 串行保护。 */
  private final Object lock = new Object();

  private final Map<Generation, GenerationState> generations = new HashMap<>();
  private final Map<String, Generation> cookies = new HashMap<>();

  @Autowired
  public IdeBrowserSessionRegistry(IdeGatewayProperties properties) {
    this(new SecureRandom(), Clock.systemUTC(), properties.requireSessionTtl());
  }

  /** 测试用构造：可注入确定性时钟与 TTL。 */
  IdeBrowserSessionRegistry(SecureRandom random, Clock clock, Duration sessionTtl) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
  }

  /**
   * 消费一次性 bootstrap ticket：只校验「该视图已由真实 {@code OPEN_VIEW} 授权且 workspace 匹配」，随后设置/复用 cookie。
   *
   * <p>这里 <em>绝不</em> 新增授权：否则一个在视图关闭前签发、之后才被消费的旧 ticket 就能让已关闭的视图重新获得授权。
   *
   * @return 该代际的 cookie 值
   * @throws IllegalStateException 该代际/视图未被真实视图事件授权，或 ticket 的 workspace 与授权不一致
   */
  public String open(DaemonConnectionIdentity identity, String viewId, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    synchronized (lock) {
      Generation generation = new Generation(identity);
      GenerationState state = generations.get(generation);
      if (state == null || !workspacePath.equals(state.views.get(viewId))) {
        throw new IllegalStateException("bootstrap ticket does not match an open IDE view");
      }
      Instant now = clock.instant();
      if (state.cookie == null || !state.cookieExpiresAt.isAfter(now)) {
        // 过期 cookie 只作废凭据本身：仍然打开着的视图授权不受影响（无空闲自动销毁）。
        if (state.cookie != null) {
          cookies.remove(state.cookie);
        }
        state.cookie = newToken();
        cookies.put(state.cookie, generation);
      }
      state.cookieExpiresAt = now.plus(sessionTtl);
      return state.cookie;
    }
  }

  /** 解析 cookie 值；不存在或已过期返回 empty，且只作废该 cookie（保留活跃视图授权）。 */
  public Optional<Session> lookup(String cookieValue) {
    if (cookieValue == null || cookieValue.isBlank()) {
      return Optional.empty();
    }
    synchronized (lock) {
      Generation generation = cookies.get(cookieValue);
      if (generation == null) {
        return Optional.empty();
      }
      GenerationState state = generations.get(generation);
      if (state == null || !state.cookieExpiresAt.isAfter(clock.instant())) {
        if (state != null) {
          state.cookie = null;
        }
        cookies.remove(cookieValue);
        return Optional.empty();
      }
      return Optional.of(
          new Session(generation.identity, new LinkedHashSet<>(state.views.values())));
    }
  }

  /** 关闭某代际的会话与全部视图授权（另一个 Daemon 实例接管）；代际寿命只由该通知决定，与 cookie 过期无关。 */
  public void closeGeneration(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    synchronized (lock) {
      GenerationState state = generations.remove(new Generation(identity));
      if (state != null && state.cookie != null) {
        cookies.remove(state.cookie);
      }
    }
  }

  /**
   * 在任何 Native 副作用（Daemon 的 {@code OPEN_VIEW}）之前预检容量：该 Environment 现有代际已达视图上界时显式失败。
   *
   * @throws IllegalStateException 已达 {@link #MAX_VIEWS_PER_GENERATION} 或 {@link #MAX_GENERATIONS}
   */
  @Override
  public void ensureViewCapacity(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    synchronized (lock) {
      for (Map.Entry<Generation, GenerationState> entry : generations.entrySet()) {
        if (!entry.getKey().identity.environmentId().equals(environmentId)) {
          continue;
        }
        if (entry.getValue().views.size() >= MAX_VIEWS_PER_GENERATION) {
          throw new IllegalStateException("too many open IDE views for this environment");
        }
      }
    }
  }

  /** 真实视图已创建：授权其受管 workspace（同一代际下的多面板/多 workspace 共享一个 cookie）。 */
  @Override
  public void viewOpened(DaemonConnectionIdentity identity, String viewId, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    synchronized (lock) {
      Generation generation = new Generation(identity);
      GenerationState state = generations.get(generation);
      if (state == null) {
        if (generations.size() >= MAX_GENERATIONS) {
          throw new IllegalStateException("too many concurrent IDE browser generations");
        }
        state = new GenerationState();
        generations.put(generation, state);
      }
      if (!state.views.containsKey(viewId) && state.views.size() >= MAX_VIEWS_PER_GENERATION) {
        throw new IllegalStateException("too many open IDE views in this generation");
      }
      state.views.put(viewId, workspacePath);
    }
  }

  /** 真实视图已关闭：立即撤销其受管 workspace 授权（未消费的 bootstrap ticket 由调用方一并 revoke）。 */
  @Override
  public void viewClosed(DaemonConnectionIdentity identity, String viewId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    synchronized (lock) {
      GenerationState state = generations.get(new Generation(identity));
      if (state != null) {
        state.views.remove(viewId);
      }
    }
  }

  /** 当前有效 cookie 数量（诊断/测试）。 */
  public int size() {
    synchronized (lock) {
      return cookies.size();
    }
  }

  /** 某代际当前的视图授权数量（诊断/测试）。 */
  int viewCount(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    synchronized (lock) {
      GenerationState state = generations.get(new Generation(identity));
      return state == null ? 0 : state.views.size();
    }
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** 一个代际的状态：cookie（与其独立有效期）以及 view → workspace 授权映射（跟随真实视图生命周期）。 */
  private static final class GenerationState {

    private String cookie;
    private Instant cookieExpiresAt = Instant.EPOCH;
    private final Map<String, String> views = new LinkedHashMap<>();
  }

  private record Generation(DaemonConnectionIdentity identity) {}
}
