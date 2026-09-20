package fun.fengwk.kkstudio.platform.plugin.testing;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 测试专用的内存凭据仓库，忠实模拟 PostgreSQL 版 {@code PluginCredentialMapper} 的原子 SQL 语义与状态约束。
 *
 * <p>方法与底层 SQL 映射关系如下：
 *
 * <ul>
 *   <li>{@link #find(String)} 模拟 {@code PluginCredentialMapper#getByPluginId(String)}:
 *       单行读取权威行，返回行快照；行不存在时返回空。
 *   <li>{@link #upsert(PluginCredentialRow)} 模拟 {@code
 *       PluginCredentialMapper#upsert(PluginCredentialDO)}: 行不存在则以 {@code version = 0}
 *       插入；已存在则在单条原子操作内覆盖载荷与元数据， 清空在途 lease（token 与 until 置空），推进 {@code version + 1}，无条件成功并返回 true。
 *   <li>{@link #delete(String)} 模拟 {@code PluginCredentialMapper#deleteByPluginId(String)}: 删除指定
 *       pluginId 的行并返回是否实际删除了一行。
 *   <li>{@link #markExpiredLeasesUncertain(List, Instant, String)} 模拟 {@code
 *       PluginCredentialMapper#markExpiredLeasesUncertain(List, Instant, String)}: 对列表内且 {@code
 *       refresh_lease_token != null && refresh_lease_until <= now} 的过期 in-flight 行生效， 将状态置为 {@code
 *       REFRESH_UNCERTAIN}、记录错误、清空 lease、{@code version + 1}，返回影响行数。
 *   <li>{@link #claimDue(List, Instant, Instant, String, int)} 模拟 {@code
 *       PluginCredentialMapper#claimDue(List, Instant, Instant, String, int)}: 筛选列表内状态为 {@code
 *       CONNECTED / REFRESH_FAILED}、{@code next_refresh_at <= now} 且 lease 为空或已过期的行，按 {@code
 *       (next_refresh_at, plugin_id)} 升序取前 limit 个， 原子写入 leaseToken、leaseUntil、{@code version +
 *       1}，并返回被领取行的新快照。
 *   <li>{@link #finalizeSuccess} 模拟 {@code PluginCredentialMapper#finalizeSuccess}: 仅当 {@code
 *       refresh_lease_token} 与 {@code version} 同时匹配时生效，替换密文、region、时间， 置状态为 {@code CONNECTED}，清空
 *       lease 并 {@code version + 1}；任一不匹配则返回 false 且绝不修改任何字段。
 *   <li>{@link #finalizeFailure} 模拟 {@code PluginCredentialMapper#finalizeFailure}: 仅当 {@code
 *       refresh_lease_token} 与 {@code version} 同时匹配时生效，写入终态、下一刷新时刻与错误， 清空 lease 并 {@code version +
 *       1}；任一不匹配则返回 false 且绝不修改任何字段。
 *   <li>{@link #releaseLease} 模拟 {@code PluginCredentialMapper#releaseLease}: 仅当 {@code
 *       refresh_lease_token} 与 {@code version} 同时匹配时生效，清空 lease 并 {@code version + 1}； 任一不匹配则返回
 *       false 且绝不修改任何字段。
 * </ul>
 */
public class InMemoryPluginCredentialRepository implements PluginCredentialRepository {

  private final Map<String, PluginCredentialRow> storage = new LinkedHashMap<>();

  @Override
  public synchronized Optional<PluginCredentialRow> find(String pluginId) {
    PluginCredentialRow row = storage.get(pluginId);
    return Optional.ofNullable(row == null ? null : cloneRow(row));
  }

  @Override
  public synchronized boolean upsert(PluginCredentialRow row) {
    Objects.requireNonNull(row, "row");
    PluginCredentialRow existing = storage.get(row.pluginId());
    Instant now = row.updateTime() != null ? row.updateTime() : Instant.now();
    if (existing == null) {
      PluginCredentialRow inserted =
          new PluginCredentialRow(
              row.pluginId(),
              row.encryptedPayload(),
              row.region(),
              row.expiresAt(),
              row.nextRefreshAt(),
              row.status(),
              null,
              null,
              null,
              null,
              0L,
              row.createTime() != null ? row.createTime() : now,
              now);
      storage.put(row.pluginId(), inserted);
    } else {
      PluginCredentialRow updated =
          new PluginCredentialRow(
              row.pluginId(),
              row.encryptedPayload(),
              row.region(),
              row.expiresAt(),
              row.nextRefreshAt(),
              row.status(),
              null,
              null,
              null,
              null,
              existing.version() + 1L,
              existing.createTime(),
              now);
      storage.put(row.pluginId(), updated);
    }
    return true;
  }

  @Override
  public synchronized boolean delete(String pluginId) {
    return storage.remove(pluginId) != null;
  }

  @Override
  public synchronized int markExpiredLeasesUncertain(
      List<String> pluginIds, Instant now, String error) {
    if (pluginIds == null || pluginIds.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (String id : pluginIds) {
      PluginCredentialRow row = storage.get(id);
      if (row != null
          && row.refreshLeaseToken() != null
          && row.refreshLeaseUntil() != null
          && !row.refreshLeaseUntil().isAfter(now)) {
        PluginCredentialRow updated =
            new PluginCredentialRow(
                row.pluginId(),
                row.encryptedPayload(),
                row.region(),
                row.expiresAt(),
                row.nextRefreshAt(),
                PluginCredentialStatus.REFRESH_UNCERTAIN,
                row.lastRefreshedAt(),
                error,
                null,
                null,
                row.version() + 1L,
                row.createTime(),
                now);
        storage.put(id, updated);
        count++;
      }
    }
    return count;
  }

  @Override
  public synchronized List<PluginCredentialRow> claimDue(
      List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit) {
    if (pluginIds == null || pluginIds.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<PluginCredentialRow> candidates = new ArrayList<>();
    for (String id : pluginIds) {
      PluginCredentialRow row = storage.get(id);
      if (row != null) {
        boolean statusMatch =
            row.status() == PluginCredentialStatus.CONNECTED
                || row.status() == PluginCredentialStatus.REFRESH_FAILED;
        boolean due = !row.nextRefreshAt().isAfter(now);
        boolean leaseFreeOrExpired =
            row.refreshLeaseToken() == null
                || (row.refreshLeaseUntil() != null && !row.refreshLeaseUntil().isAfter(now));
        if (statusMatch && due && leaseFreeOrExpired) {
          candidates.add(row);
        }
      }
    }

    candidates.sort(
        Comparator.comparing(PluginCredentialRow::nextRefreshAt)
            .thenComparing(PluginCredentialRow::pluginId));

    List<PluginCredentialRow> claimed = new ArrayList<>();
    int take = Math.min(limit, candidates.size());
    for (int i = 0; i < take; i++) {
      PluginCredentialRow row = candidates.get(i);
      PluginCredentialRow updated =
          new PluginCredentialRow(
              row.pluginId(),
              row.encryptedPayload(),
              row.region(),
              row.expiresAt(),
              row.nextRefreshAt(),
              row.status(),
              row.lastRefreshedAt(),
              row.lastRefreshError(),
              leaseToken,
              leaseUntil,
              row.version() + 1L,
              row.createTime(),
              now);
      storage.put(row.pluginId(), updated);
      claimed.add(cloneRow(updated));
    }
    return claimed;
  }

  @Override
  public synchronized boolean finalizeSuccess(
      String pluginId,
      String leaseToken,
      long version,
      byte[] encryptedPayload,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt,
      Instant refreshedAt,
      Instant now) {
    PluginCredentialRow row = storage.get(pluginId);
    if (row == null
        || !Objects.equals(row.refreshLeaseToken(), leaseToken)
        || row.version() != version) {
      return false;
    }
    PluginCredentialRow updated =
        new PluginCredentialRow(
            row.pluginId(),
            encryptedPayload,
            region,
            expiresAt,
            nextRefreshAt,
            PluginCredentialStatus.CONNECTED,
            refreshedAt,
            null,
            null,
            null,
            row.version() + 1L,
            row.createTime(),
            now);
    storage.put(pluginId, updated);
    return true;
  }

  @Override
  public synchronized boolean finalizeFailure(
      String pluginId,
      String leaseToken,
      long version,
      PluginCredentialStatus status,
      Instant nextRefreshAt,
      String error,
      Instant now) {
    PluginCredentialRow row = storage.get(pluginId);
    if (row == null
        || !Objects.equals(row.refreshLeaseToken(), leaseToken)
        || row.version() != version) {
      return false;
    }
    PluginCredentialRow updated =
        new PluginCredentialRow(
            row.pluginId(),
            row.encryptedPayload(),
            row.region(),
            row.expiresAt(),
            nextRefreshAt,
            status,
            row.lastRefreshedAt(),
            error,
            null,
            null,
            row.version() + 1L,
            row.createTime(),
            now);
    storage.put(pluginId, updated);
    return true;
  }

  @Override
  public synchronized boolean releaseLease(
      String pluginId, String leaseToken, long version, Instant now) {
    PluginCredentialRow row = storage.get(pluginId);
    if (row == null
        || !Objects.equals(row.refreshLeaseToken(), leaseToken)
        || row.version() != version) {
      return false;
    }
    PluginCredentialRow updated =
        new PluginCredentialRow(
            row.pluginId(),
            row.encryptedPayload(),
            row.region(),
            row.expiresAt(),
            row.nextRefreshAt(),
            row.status(),
            row.lastRefreshedAt(),
            row.lastRefreshError(),
            null,
            null,
            row.version() + 1L,
            row.createTime(),
            now);
    storage.put(pluginId, updated);
    return true;
  }

  // --- 测试断言与模拟并发/偷 version 的专用方法 ---

  /** 直接读取当前行的快照，不存在时返回 null。 */
  public synchronized PluginCredentialRow getDirect(String pluginId) {
    PluginCredentialRow row = storage.get(pluginId);
    return row == null ? null : cloneRow(row);
  }

  /** 测试直接植入或覆盖行。 */
  public synchronized void setDirect(PluginCredentialRow row) {
    Objects.requireNonNull(row, "row");
    storage.put(row.pluginId(), cloneRow(row));
  }

  /** 模拟另一节点或并发操作偷走 version（当前行 version + 1）。 */
  public synchronized void stealVersion(String pluginId) {
    PluginCredentialRow row = storage.get(pluginId);
    if (row != null) {
      PluginCredentialRow updated =
          new PluginCredentialRow(
              row.pluginId(),
              row.encryptedPayload(),
              row.region(),
              row.expiresAt(),
              row.nextRefreshAt(),
              row.status(),
              row.lastRefreshedAt(),
              row.lastRefreshError(),
              row.refreshLeaseToken(),
              row.refreshLeaseUntil(),
              row.version() + 1L,
              row.createTime(),
              row.updateTime());
      storage.put(pluginId, updated);
    }
  }

  /** 模拟另一节点换走 leaseToken。 */
  public synchronized void changeLeaseToken(String pluginId, String newToken) {
    PluginCredentialRow row = storage.get(pluginId);
    if (row != null) {
      PluginCredentialRow updated =
          new PluginCredentialRow(
              row.pluginId(),
              row.encryptedPayload(),
              row.region(),
              row.expiresAt(),
              row.nextRefreshAt(),
              row.status(),
              row.lastRefreshedAt(),
              row.lastRefreshError(),
              newToken,
              row.refreshLeaseUntil(),
              row.version(),
              row.createTime(),
              row.updateTime());
      storage.put(pluginId, updated);
    }
  }

  public synchronized int size() {
    return storage.size();
  }

  public synchronized void clear() {
    storage.clear();
  }

  private PluginCredentialRow cloneRow(PluginCredentialRow row) {
    return new PluginCredentialRow(
        row.pluginId(),
        row.encryptedPayload(),
        row.region(),
        row.expiresAt(),
        row.nextRefreshAt(),
        row.status(),
        row.lastRefreshedAt(),
        row.lastRefreshError(),
        row.refreshLeaseToken(),
        row.refreshLeaseUntil(),
        row.version(),
        row.createTime(),
        row.updateTime());
  }
}
