package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogPageDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource;
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 来源隔离、6 小时内存缓存与 single-flight 的模型目录服务。
 *
 * <p>切换来源会递增 generation；旧来源或旧 generation 的迟到下载结果绝不发布。同来源刷新失败保留上次成功候选和真实
 * fetchedAt，只记录错误；成功才更新时间。一次请求只使用当前设置选中的来源，不自动回退或混合版本。
 */
@Component
public class ModelCatalogService {

  static final Duration TTL = Duration.ofHours(6);
  static final int DEFAULT_PAGE_SIZE = 20;
  static final int MAX_PAGE_SIZE = 50;
  static final int MAX_QUERY_LENGTH = 200;
  static final int MAX_CURSOR_LENGTH = 512;

  private final SystemSettingsSnapshot settings;
  private final ModelCatalogTransport transport;
  private final Clock clock;
  private final Map<ModelCatalogSource, ModelsDevCatalogAdapter> adapters =
      new EnumMap<>(ModelCatalogSource.class);
  private final Map<ModelCatalogSource, SourceState> states =
      new EnumMap<>(ModelCatalogSource.class);
  private final Object lock = new Object();
  private final AtomicLong generation = new AtomicLong();

  private ModelCatalogSource activeSource;

  @Autowired
  public ModelCatalogService(
      SystemSettingsSnapshot settings,
      ModelCatalogTransport transport,
      ModelsDevV1Adapter v1Adapter,
      ModelsDevV2Adapter v2Adapter) {
    this(settings, transport, v1Adapter, v2Adapter, Clock.systemUTC());
  }

  ModelCatalogService(
      SystemSettingsSnapshot settings,
      ModelCatalogTransport transport,
      ModelsDevV1Adapter v1Adapter,
      ModelsDevV2Adapter v2Adapter,
      Clock clock) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.clock = Objects.requireNonNull(clock, "clock");
    adapters.put(v1Adapter.source(), v1Adapter);
    adapters.put(v2Adapter.source(), v2Adapter);
    for (ModelCatalogSource source : ModelCatalogSource.values()) {
      states.put(source, new SourceState());
    }
  }

  /** 按需加载后返回有界候选页；首次加载最多等待一次下载超时。 */
  public ModelCatalogPageDTO page(String query, String cursor, int pageSize) {
    for (int attempt = 0; attempt < 3; attempt++) {
      ModelCatalogSource source = observeCurrentSource();
      SourceState state = states.get(source);
      Load load = null;
      synchronized (lock) {
        if (state.snapshot == null || expired(state)) {
          startLoadLocked(source, state);
        }
        if (state.loading) {
          load = new Load(state.loadGeneration, state.load);
        }
      }
      if (load != null) {
        try {
          load.future().join();
        } catch (CompletionException ignored) {
          // 错误已经记录在来源状态中；保留旧快照语义。
        }
      }
      synchronized (lock) {
        if (source == activeSource) {
          if (load != null && state.generation < load.generation()) {
            // 这一轮加载在来源/generation 切换后被丢弃，不能把空页当作最终结果。
            continue;
          }
          return pageLocked(source, state, query, cursor, pageSize);
        }
      }
    }
    ModelCatalogSource source = observeCurrentSource();
    synchronized (lock) {
      return pageLocked(source, states.get(source), query, cursor, pageSize);
    }
  }

  /** 手动刷新当前来源；存在进行中的 single-flight 时直接复用。 */
  public ModelCatalogPageDTO refresh() {
    ModelCatalogSource source = observeCurrentSource();
    SourceState state = states.get(source);
    synchronized (lock) {
      startLoadLocked(source, state);
      return pageLocked(source, state, null, null, DEFAULT_PAGE_SIZE);
    }
  }

  private ModelCatalogSource observeCurrentSource() {
    ModelCatalogSource selected = settings.get().aiRuntime().modelCatalogSource();
    synchronized (lock) {
      if (selected != activeSource) {
        activeSource = selected;
        generation.incrementAndGet();
      }
      return selected;
    }
  }

  private void startLoadLocked(ModelCatalogSource source, SourceState state) {
    if (state.loading) {
      return;
    }
    long loadGeneration = generation.incrementAndGet();
    state.loading = true;
    state.loadGeneration = loadGeneration;
    state.error = null;
    CompletableFuture<Void> future =
        CompletableFuture.runAsync(
            () -> {
              try {
                String json = transport.fetch(source);
                List<ModelDefaultCandidateDTO> candidates = adapters.get(source).adapt(json);
                synchronized (lock) {
                  if (loadGeneration == generation.get()) {
                    state.snapshot = List.copyOf(candidates);
                    state.fetchedAt = Instant.now(clock);
                    state.generation = loadGeneration;
                    state.error = null;
                  }
                  state.loading = false;
                }
              } catch (RuntimeException error) {
                synchronized (lock) {
                  if (loadGeneration == generation.get()) {
                    state.error = message(error);
                    state.generation = loadGeneration;
                  }
                  state.loading = false;
                }
              }
            });
    state.load = future;
  }

  private ModelCatalogPageDTO pageLocked(
      ModelCatalogSource source, SourceState state, String query, String cursor, int pageSize) {
    int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    String generationText = Long.toString(state.generation);
    int offset = cursorOffset(cursor, generationText);
    List<ModelDefaultCandidateDTO> filtered = filter(state.snapshot, query);
    int from = Math.min(offset, filtered.size());
    int to = Math.min(from + size, filtered.size());
    ModelCatalogPageDTO page = new ModelCatalogPageDTO();
    page.setSource(source.value());
    page.setGeneration(generationText);
    page.setFetchedAt(state.fetchedAt);
    page.setLoading(state.loading);
    page.setError(state.error);
    page.setItems(List.copyOf(filtered.subList(from, to)));
    page.setNextCursor(to < filtered.size() ? encodeCursor(generationText, to) : null);
    return page;
  }

  private static List<ModelDefaultCandidateDTO> filter(
      List<ModelDefaultCandidateDTO> candidates, String query) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    String needle = normalizeQuery(query);
    List<ModelDefaultCandidateDTO> filtered = new ArrayList<>();
    for (ModelDefaultCandidateDTO candidate : candidates) {
      if (needle.isEmpty() || matches(candidate, needle)) {
        filtered.add(candidate);
      }
    }
    filtered.sort(
        Comparator.comparing(
                (ModelDefaultCandidateDTO candidate) ->
                    candidate.getProvider() == null ? "" : candidate.getProvider(),
                String.CASE_INSENSITIVE_ORDER)
            .thenComparing(
                candidate -> candidate.getModelId() == null ? "" : candidate.getModelId(),
                String.CASE_INSENSITIVE_ORDER));
    return filtered;
  }

  private static String normalizeQuery(String query) {
    if (query == null) {
      return "";
    }
    String normalized = query.strip();
    if (normalized.length() > MAX_QUERY_LENGTH) {
      normalized = normalized.substring(0, MAX_QUERY_LENGTH);
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static boolean matches(ModelDefaultCandidateDTO candidate, String needle) {
    return contains(candidate.getName(), needle)
        || contains(candidate.getProvider(), needle)
        || contains(candidate.getModelId(), needle);
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  private boolean expired(SourceState state) {
    return state.fetchedAt == null || !state.fetchedAt.plus(TTL).isAfter(Instant.now(clock));
  }

  private static int cursorOffset(String cursor, String generation) {
    if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
      return 0;
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int split = decoded.indexOf(':');
      if (split < 0 || !decoded.substring(0, split).equals(generation)) {
        return 0;
      }
      return Math.max(0, Integer.parseInt(decoded.substring(split + 1)));
    } catch (IllegalArgumentException error) {
      return 0;
    }
  }

  private static String encodeCursor(String generation, int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((generation + ":" + offset).getBytes(StandardCharsets.UTF_8));
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? "catalog refresh failed" : message;
  }

  private static final class SourceState {

    private List<ModelDefaultCandidateDTO> snapshot;
    private Instant fetchedAt;
    private long generation;
    private boolean loading;
    private long loadGeneration;
    private String error;
    private CompletableFuture<Void> load;
  }

  private record Load(long generation, CompletableFuture<Void> future) {}
}
