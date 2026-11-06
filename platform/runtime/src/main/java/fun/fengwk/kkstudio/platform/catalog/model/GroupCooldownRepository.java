package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.platform.harness.store.PostgresqlHarnessTransaction;

import java.sql.Timestamp;
import java.time.Instant;

/** 组成员冷却：使用调用者的 Harness 物理事务和数据库时钟。 */
public final class GroupCooldownRepository {

  public boolean isCooling(
      HarnessStore.Transaction transaction,
      String groupName,
      long groupVersion,
      String memberModelName,
      String memberVariant) {
    PostgresqlHarnessTransaction tx = requireTransaction(transaction);
    Instant now = tx.now();
    return tx.queryOne(
            """
            select exists (
                select 1
                from model_group_cooldown c
                join model m on m.name = c.group_name
                where c.group_name = ?
                  and c.member_model_name = ?
                  and c.member_variant = ?
                  and c.group_version = ?
                  and m.version = ?
                  and c.cooldown_until > ?
            )
            """,
            (rs, rowNumber) -> rs.getBoolean(1),
            groupName,
            memberModelName,
            memberVariant,
            groupVersion,
            groupVersion,
            Timestamp.from(now))
        .orElse(false);
  }

  public void freeze(
      HarnessStore.Transaction transaction,
      String groupName,
      long groupVersion,
      String memberModelName,
      String memberVariant,
      Instant cooldownUntil) {
    PostgresqlHarnessTransaction tx = requireTransaction(transaction);
    Instant now = tx.now();
    Long currentVersion =
        tx.queryOne(
                "select version from model where name = ? for share",
                (rs, rowNumber) -> rs.getLong(1),
                groupName)
            .orElse(null);
    if (currentVersion == null || currentVersion != groupVersion) {
      return;
    }
    tx.updateJdbc(
        """
        insert into model_group_cooldown (
            group_name, member_model_name, member_variant, group_version, cooldown_until,
            created_at, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (group_name, member_model_name, member_variant) do update
        set cooldown_until = greatest(
                model_group_cooldown.cooldown_until,
                excluded.cooldown_until),
            group_version = excluded.group_version,
            updated_at = excluded.updated_at
        where model_group_cooldown.group_version = excluded.group_version
        """,
        groupName,
        memberModelName,
        memberVariant,
        groupVersion,
        Timestamp.from(cooldownUntil),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  private static PostgresqlHarnessTransaction requireTransaction(HarnessStore.Transaction tx) {
    if (!(tx instanceof PostgresqlHarnessTransaction transaction)) {
      throw new IllegalArgumentException("cooldown requires a PostgreSQL Harness transaction");
    }
    transaction.checkOpen();
    return transaction;
  }
}
