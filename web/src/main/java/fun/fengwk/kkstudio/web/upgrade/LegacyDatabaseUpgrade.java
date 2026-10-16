package fun.fengwk.kkstudio.web.upgrade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.PostgresqlAgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.store.AgentDefinitionJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import javax.crypto.SecretKey;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 离线原子数据库转换内核：把旧部署的 41 张共有表按已验证 FK 拓扑复制进当前 V1 目标库，并把旧 Plugin 配置、settings、凭据与 Skill Package
 * 令牌封装成当前契约。
 *
 * <p>两个连接都必须是无遗留事务的普通连接：源以只读 {@code REPEATABLE READ} 一致快照打开，目标只取一个物理连接并在其上独占一个写事务，真实 Spring
 * 仓储/Store 与 COPY 共用该事务。两边的 session 前置（PG17、UTF8、origin 复制角色、search_path / DateStyle / UTC /
 * row_security 与真实可写性）在各自事务内验证，绝不复制两套逻辑也绝不假设默认值。
 *
 * <p>只接受 DEV / MAIN 的冻结 schema 指纹与对应 Flyway V1 校验和（源与目标都校验），目标必须是唯一 pristine 形态并带着当前唯一成功的 V1 历史。
 * 候选快照与离线装配在提交前释放，释放失败走真实回滚；提交/回滚通信失败或提交后关闭连接失败才标为结果需核验。异常只报告固定阶段与固定原因。
 *
 * <p>包内可见（package-private）：离线升级包内部的转换内核，不装配进常驻 Web 运行时。
 */
final class LegacyDatabaseUpgrade {

  private static final SystemSettingsCodec SETTINGS_CODEC = new SystemSettingsCodec();

  /** 当前目标目录未初始化 epoch；转换只从它推进到 1，绝不覆盖已有已初始化目录。 */
  private static final long UNINITIALIZED_EPOCH = 0L;

  private static final String SYSTEM_SETTING = "system_setting";

  private static final String PLUGIN_CREDENTIAL = "plugin_credential";

  private static final String SKILL_PACKAGE = "skill_package";

  private static final String ENVIRONMENT_CONNECTION = "environment_connection";

  private static final String AGENT_PROVIDER = "agent_provider";

  private static final String HARNESS_ENTRY = "harness_entry";

  private static final String HARNESS_THREAD_COMMAND = "harness_thread_command";

  private static final String HARNESS_THREAD_STOP_RECEIPT = "harness_thread_stop_receipt";

  private LegacyDatabaseUpgrade() {}

  /**
   * 执行一次完整转换。
   *
   * @param sourceDataSource 旧部署句柄；本调用自行取得只读一致快照并关闭
   * @param targetDataSource 目标句柄；本调用只取一个物理连接并在其上独占一个写事务
   * @param sourceKey 读取旧 format 1 Plugin 凭据与旧 Skill Package 令牌的主密钥；源没有这两类密文时可为 null
   * @param targetKey 写入当前 Plugin 凭据与 Skill Package 令牌的主密钥；没有任何密文可写时可为 null
   * @param staticSecretsByPlugin 显式静态密钥材料（pluginId → 字段名到真实值）；没有材料的插件省略
   * @param dryRun 为 true 时执行同一写入与验证路径后回滚，目标保持 pristine
   * @param clock 新增静态凭据创建/更新时刻的来源
   * @return 只含 profile、逐表行数与对账数量的不可变小结果
   * @throws LegacyDatabaseUpgradeException 任何阶段失败（固定阶段与固定原因；提交/回滚结果未知时 {@code outcomeUncertain} 为
   *     true）
   */
  static LegacyDatabaseUpgradeResult convert(
      DataSource sourceDataSource,
      DataSource targetDataSource,
      SecretKey sourceKey,
      SecretKey targetKey,
      Map<String, Map<String, String>> staticSecretsByPlugin,
      boolean dryRun,
      Clock clock) {
    Objects.requireNonNull(sourceDataSource, "sourceDataSource");
    Objects.requireNonNull(targetDataSource, "targetDataSource");
    Objects.requireNonNull(staticSecretsByPlugin, "staticSecretsByPlugin");
    Objects.requireNonNull(clock, "clock");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_TRANSACTION,
          "the converter does not accept an ambient transaction");
    }
    Connection source = null;
    Connection target = null;
    try {
      source = openIdleConnection(sourceDataSource);
      target = openIdleConnection(targetDataSource);
    } catch (LegacyDatabaseUpgradeException | Error failure) {
      throw convergedFailure(
          releaseConnections(failure, target, source),
          LegacyDatabaseUpgradeException.Stage.CONNECTION);
    } catch (RuntimeException ignored) {
      // 连接取得的未受检失败不回显底层信息；释放本身出现 Error 时以 identity 提升为主失败。
      throw convergedFailure(
          releaseConnections(
              new LegacyDatabaseUpgradeException(
                  LegacyDatabaseUpgradeException.Stage.CONNECTION,
                  "cannot open the upgrade databases"),
              target,
              source),
          LegacyDatabaseUpgradeException.Stage.CONNECTION);
    }
    return run(source, target, sourceKey, targetKey, staticSecretsByPlugin, dryRun, clock);
  }

  private static LegacyDatabaseUpgradeResult run(
      Connection source,
      Connection target,
      SecretKey sourceKey,
      SecretKey targetKey,
      Map<String, Map<String, String>> staticSecretsByPlugin,
      boolean dryRun,
      Clock clock) {
    PlatformTransactionManager transactionManager = null;
    TransactionStatus transaction = null;
    OfflinePluginCatalog catalog = null;
    PluginCatalogBootstrap.PreparedInitial prepared = null;
    LegacyDatabaseUpgradeResult result = null;
    Throwable failure = null;
    boolean committed = false;
    try {
      SingleConnectionDataSource scoped = new SingleConnectionDataSource(target, true);
      transactionManager = new UpgradeTransactionManager(scoped);
      beginSourceSnapshot(source);
      String profile = LegacySchemaProfile.identify(LegacySchemaProfile.fingerprint(source));
      LegacySchemaProfile.requireSourceHistory(source, profile);
      LegacySourceGuard.requireDrained(source);
      LegacySourceGuard.SourceSettings systemSetting = LegacySourceGuard.readSystemSetting(source);
      SourceFacts facts =
          new SourceFacts(
              parseSettings(systemSetting.config()),
              systemSetting,
              profile,
              LegacySourceGuard.retiredClusterNodeRows(source));

      // 候选装配与 prepared 在任何目标写事务之外完成：两者都不打开连接、不写库，也不依赖目标 session。
      catalog = OfflinePluginCatalog.open(scoped, facts.settings().settings());
      StudioPluginRegistry registry = catalog.registry();
      LegacyStaticCredentials staticCredentials =
          LegacyStaticCredentials.validate(registry, staticSecretsByPlugin);
      prepared =
          prepareInitial(
              catalog,
              pluginValues(facts.settings(), registry, staticCredentials),
              staticCredentials.configuredSecretFields(registry));

      // 候选就绪之后才真正开始写事务，再在同一事务里做 session 门禁、写入、目录 CAS 与回读校验。
      transaction = openTargetTransaction(transactionManager, target);
      beginTargetSession(target);
      ConversionCounts counts =
          writeTarget(
              source,
              target,
              scoped,
              transactionManager,
              new CatalogCandidate(scoped, catalog, registry, prepared, staticCredentials),
              new CredentialKeys(sourceKey, targetKey),
              facts,
              clock);
      // 候选与离线装配的所有权在此交出：只有它们真的释放成功才允许提交。
      PluginCatalogSnapshot pendingSnapshot = prepared.snapshot();
      OfflinePluginCatalog pendingCatalog = catalog;
      catalog = null;
      prepared = null;
      releaseBeforeCommit(pendingSnapshot, pendingCatalog);
      // 事务所有权交给提交/回滚路径：通信失败绝不重试，也不在这里重复回滚。
      TransactionStatus pendingTransaction = transaction;
      transaction = null;
      commitOrRollback(transactionManager, pendingTransaction, dryRun);
      committed = !dryRun;
      result =
          new LegacyDatabaseUpgradeResult(
              facts.profile(),
              !dryRun,
              counts.rowCounts(),
              facts.retiredClusterNodeRows(),
              counts.rewrittenCredentials(),
              counts.installedStaticCredentials(),
              counts.rewrittenSkillTokens());
    } catch (Throwable error) {
      failure = error;
    } finally {
      failure =
          release(
              failure,
              transactionManager,
              transaction,
              catalog,
              prepared,
              target,
              source,
              committed);
    }
    if (failure != null) {
      throw convergedFailure(failure, LegacyDatabaseUpgradeException.Stage.CONVERT);
    }
    return result;
  }

  /** 目标写事务之外仍需的一组源端事实；只是参数收敛。 */
  private record SourceFacts(
      LegacySystemSettings settings,
      LegacySourceGuard.SourceSettings systemSetting,
      String profile,
      long retiredClusterNodeRows) {}

  /** 一次转换的目录候选身份：epoch 1 的 manifest/revision 都从同一份真实装配与同一目标事务派生。 */
  private record CatalogCandidate(
      DataSource scoped,
      OfflinePluginCatalog catalog,
      StudioPluginRegistry registry,
      PluginCatalogBootstrap.PreparedInitial prepared,
      LegacyStaticCredentials staticCredentials) {}

  private record CredentialKeys(SecretKey sourceKey, SecretKey targetKey) {}

  /** 逐表目标行数与三类凭据写入数量。 */
  private record ConversionCounts(
      Map<String, Long> rowCounts,
      long rewrittenCredentials,
      long installedStaticCredentials,
      long rewrittenSkillTokens) {}

  private static ConversionCounts writeTarget(
      Connection source,
      Connection target,
      DataSource scoped,
      PlatformTransactionManager transactionManager,
      CatalogCandidate candidate,
      CredentialKeys keys,
      SourceFacts facts,
      Clock clock) {
    try {
      lockCanonicalTables(target);
      LegacySchemaProfile.requireTarget(target);
      requirePristineTarget(target);

      Map<String, Long> counts = new LinkedHashMap<>();
      long legacyCredentialRows = 0;
      long rewrittenSkillTokens = 0;
      for (String table : LegacySchemaProfile.parentFirstOrder(target)) {
        long sourceRows = count(source, "select count(*) from " + table);
        if (SYSTEM_SETTING.equals(table)) {
          convertSystemSetting(target, facts.settings().settings(), facts.systemSetting());
          reconcile(counts, table, count(target, "select count(*) from " + table), sourceRows);
          continue;
        }
        if (PLUGIN_CREDENTIAL.equals(table)) {
          legacyCredentialRows = sourceRows;
          continue;
        }
        List<String> columns = copyColumns(source, target, table);
        long copied;
        if (AGENT_PROVIDER.equals(table)) {
          copied =
              LegacyTableCopy.text(
                  source, target, table, columns, LegacyDatabaseUpgrade::providerRow);
        } else if (HARNESS_ENTRY.equals(table)) {
          copied =
              LegacyTableCopy.text(source, target, table, columns, LegacyDatabaseUpgrade::entryRow);
        } else if (ENVIRONMENT_CONNECTION.equals(table)) {
          copied =
              LegacyTableCopy.text(
                  source,
                  target,
                  table,
                  columns,
                  row -> LegacyEnvironmentMetadata.row(row, facts.profile()));
        } else {
          validateMailboxPayloads(source, table);
          copied = LegacyTableCopy.binary(source, target, table, columns);
        }
        long targetRows = count(target, "select count(*) from " + table);
        if (copied >= 0 && copied != targetRows) {
          throw new LegacyDatabaseUpgradeException(
              LegacyDatabaseUpgradeException.Stage.TARGET_WRITE,
              "a copied table does not match its server confirmed row count");
        }
        reconcile(counts, table, targetRows, sourceRows);
        if (SKILL_PACKAGE.equals(table)) {
          // 整行已原生 COPY：只把令牌从源主密钥重封装到目标主密钥，匿名行保持 null。
          rewrittenSkillTokens =
              LegacySkillTokens.rewriteLegacy(source, target, keys.sourceKey(), keys.targetKey());
        }
      }

      initializeCatalog(candidate);
      Map<String, PluginCredentialReference> references =
          credentialReferences(candidate.prepared());
      long rewritten =
          LegacyPluginCredentials.rewriteLegacy(
              source,
              credentialRepository(scoped),
              candidate.registry(),
              references,
              keys.sourceKey(),
              keys.targetKey());
      long installedStatic =
          LegacyPluginCredentials.installStatic(
              credentialRepository(scoped),
              candidate.staticCredentials(),
              references,
              keys.targetKey(),
              clock.instant().truncatedTo(ChronoUnit.MILLIS));
      reconcile(
          counts,
          PLUGIN_CREDENTIAL,
          count(target, "select count(*) from " + PLUGIN_CREDENTIAL),
          legacyCredentialRows + installedStatic);

      verifyCatalogRestore(catalogRepository(scoped), candidate.catalog(), candidate.prepared());
      verifyHarness(scoped, transactionManager, target);
      return new ConversionCounts(counts, rewritten, installedStatic, rewrittenSkillTokens);
    } catch (LegacyDatabaseUpgradeException error) {
      throw error;
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_WRITE, "the target conversion failed");
    } catch (RuntimeException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONVERT,
          "the converted data was rejected while writing the target");
    }
  }

  /** 导完 Agent 后、目录 CAS 之前先按真实仓储锁住并核验普通值声明的 Agent 依赖，再赢得 epoch 0 → 1 的唯一 CAS。 */
  private static void initializeCatalog(CatalogCandidate candidate) {
    PluginConfigurationRepository repository = catalogRepository(candidate.scoped());
    PluginCatalogBootstrap.PreparedInitial prepared = candidate.prepared();
    try {
      PluginAgentDependencies dependencies =
          new PluginAgentDependencies(
              candidate.registry(),
              repository,
              new PostgresqlAgentDefinitionRepository(
                  new AgentDefinitionJdbcStore(JdbcClient.create(candidate.scoped()))));
      List<PluginAgentDependencies.Declaration> declarations = new ArrayList<>();
      for (PluginConfigurationRow row : prepared.configurationRows()) {
        declarations.add(
            new PluginAgentDependencies.Declaration(
                row.pluginId(), row.revision(), row.valuesJson()));
      }
      dependencies.requireDeclaredAgents(declarations);
      if (!repository.commit(
          UNINITIALIZED_EPOCH, prepared.configurationRows(), prepared.manifest())) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.CATALOG,
            "the target catalog epoch CAS did not win");
      }
    } catch (LegacyDatabaseUpgradeException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CATALOG,
          "the target plugin catalog could not be initialized");
    }
  }

  /** 提交前从事务内真实仓储回读，用正常严格 restore 校验完整身份、指纹与逐配置行都与候选一致。 */
  private static void verifyCatalogRestore(
      PluginConfigurationRepository repository,
      OfflinePluginCatalog catalog,
      PluginCatalogBootstrap.PreparedInitial prepared) {
    PluginCatalogRecord record = repository.readCatalog();
    if (record == null || record.epoch() != 1L) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CATALOG,
          "the committed target catalog is not the first initialized epoch");
    }
    try (PluginCatalogSnapshot actual = catalog.restore(record)) {
      for (PluginConfigurationRow row : prepared.configurationRows()) {
        PluginConfigurationRow persisted =
            repository
                .readConfiguration(row.pluginId(), row.revision())
                .orElseThrow(
                    () ->
                        new LegacyDatabaseUpgradeException(
                            LegacyDatabaseUpgradeException.Stage.CATALOG,
                            "a committed target configuration row is missing"));
        if (!persisted.equals(row)) {
          throw new LegacyDatabaseUpgradeException(
              LegacyDatabaseUpgradeException.Stage.CATALOG,
              "a committed target configuration row does not match the candidate");
        }
      }
      PluginCatalogSnapshot expected = prepared.snapshot();
      if (actual.epoch() != expected.epoch()
          || !Objects.equals(actual.catalogFingerprint(), expected.catalogFingerprint())
          || !actual.capabilityFingerprints().equals(expected.capabilityFingerprints())) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.VERIFY,
            "the restored target catalog identity differs from the candidate");
      }
      List<PluginCatalogSnapshot.ConfiguredPlugin> restoredPlugins = actual.configuredPlugins();
      List<PluginCatalogSnapshot.ConfiguredPlugin> expectedPlugins = expected.configuredPlugins();
      if (restoredPlugins.size() != expectedPlugins.size()) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.VERIFY,
            "the restored target catalog does not cover the installed plugins");
      }
      for (int index = 0; index < expectedPlugins.size(); index++) {
        PluginCatalogSnapshot.ConfiguredPlugin expectedPlugin = expectedPlugins.get(index);
        PluginCatalogSnapshot.ConfiguredPlugin restoredPlugin = restoredPlugins.get(index);
        if (!expectedPlugin.descriptor().equals(restoredPlugin.descriptor())
            || expectedPlugin.config().revision() != restoredPlugin.config().revision()
            || !expectedPlugin.config().valuesJson().equals(restoredPlugin.config().valuesJson())
            || !expectedPlugin.credentialReference().equals(restoredPlugin.credentialReference())) {
          throw new LegacyDatabaseUpgradeException(
              LegacyDatabaseUpgradeException.Stage.VERIFY,
              "a restored target plugin identity differs from the candidate");
        }
      }
    }
  }

  /** 逐 Thread 流式读回祖先路径与 typed payload，并拒绝任何仍未关闭的 Turn；表锁已在目标写事务内取得。 */
  private static void verifyHarness(
      DataSource scoped, PlatformTransactionManager transactionManager, Connection target) {
    PostgresqlHarnessStore store =
        new PostgresqlHarnessStore(scoped, transactionManager, UUID::randomUUID);
    store.transaction(
        transaction -> {
          try (PreparedStatement statement =
              target.prepareStatement("select id, head_entry_id from harness_thread order by id")) {
            // fetchSize 必须在 executeQuery 之前设定，否则查询已经执行，结果集不会被流式读取。
            statement.setFetchSize(LegacyTableCopy.FETCH_SIZE);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                UUID threadId = UUID.fromString(rows.getString(1));
                UUID headEntryId = UUID.fromString(rows.getString(2));
                if (transaction.findThread(threadId).isEmpty()) {
                  throw new LegacyDatabaseUpgradeException(
                      LegacyDatabaseUpgradeException.Stage.VERIFY,
                      "a converted thread could not be read back from the current store");
                }
                transaction.loadCommandsByThread(threadId);
                transaction.loadStopReceiptsByThread(threadId);
                EntryPath path = transaction.loadEntryPath(headEntryId);
                if (path.openTurnStart().isPresent()) {
                  throw new LegacyDatabaseUpgradeException(
                      LegacyDatabaseUpgradeException.Stage.VERIFY,
                      "a converted thread still has an open turn");
                }
              }
            }
          } catch (SQLException error) {
            throw new LegacyDatabaseUpgradeException(
                LegacyDatabaseUpgradeException.Stage.VERIFY,
                "cannot read back the converted harness threads");
          }
          return null;
        });
  }

  private static Map<String, PluginCredentialReference> credentialReferences(
      PluginCatalogBootstrap.PreparedInitial prepared) {
    Map<String, PluginCredentialReference> references = new LinkedHashMap<>();
    for (PluginCatalogSnapshot.ConfiguredPlugin plugin : prepared.snapshot().configuredPlugins()) {
      references.put(plugin.descriptor().pluginId(), plugin.credentialReference());
    }
    return references;
  }

  /** agent_provider 只改写 provider_type 的旧 wire 值，其余列原样。 */
  private static List<String> providerRow(ResultSet row) throws SQLException {
    return Arrays.asList(
        row.getString("name"),
        row.getString("description"),
        LegacyHarnessHistoryJson.providerType(row.getString("provider_type")),
        row.getString("base_url"),
        row.getString("credential"),
        row.getString("config"),
        row.getString("connection_generation_id"),
        row.getString("created_at"),
        row.getString("updated_at"),
        row.getString("version"));
  }

  /** harness_entry 只经已有 entryPayload / providerReplayState 两处变换，其余列原样。 */
  private static List<String> entryRow(ResultSet row) throws SQLException {
    String entryType = row.getString("entry_type");
    EntryType type;
    try {
      type = EntryType.valueOf(entryType);
    } catch (RuntimeException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONVERT,
          "legacy history entry carries an unknown type");
    }
    return Arrays.asList(
        row.getString("id"),
        row.getString("session_id"),
        row.getString("parent_entry_id"),
        entryType,
        LegacyHarnessHistoryJson.entryPayload(type, row.getString("payload")),
        row.getString("created_at"),
        LegacyHarnessHistoryJson.providerReplayState(row.getString("provider_replay_state")));
  }

  /** 逐行流式校验两张带结构化 JSON 的表：命令 payload 与 stop 回执的嵌套命令都按真实 codec 验证，字节不改写。 */
  private static void validateMailboxPayloads(Connection source, String table) throws SQLException {
    String sql;
    if (HARNESS_THREAD_COMMAND.equals(table)) {
      sql = "select command_type, payload::text from harness_thread_command";
    } else if (HARNESS_THREAD_STOP_RECEIPT.equals(table)) {
      sql = "select cancelled_inputs::text from harness_thread_stop_receipt";
    } else {
      return;
    }
    try (Statement statement = source.createStatement()) {
      statement.setFetchSize(LegacyTableCopy.FETCH_SIZE);
      try (ResultSet rows = statement.executeQuery(sql)) {
        while (rows.next()) {
          if (HARNESS_THREAD_COMMAND.equals(table)) {
            ThreadCommandType type;
            try {
              type = ThreadCommandType.valueOf(rows.getString(1));
            } catch (RuntimeException error) {
              throw new LegacyDatabaseUpgradeException(
                  LegacyDatabaseUpgradeException.Stage.CONVERT,
                  "legacy thread command carries an unknown type");
            }
            LegacyHarnessHistoryJson.threadCommandPayload(type, rows.getString(2));
          } else {
            LegacyHarnessHistoryJson.stopReceiptCancelledInputs(rows.getString(1));
          }
        }
      }
    }
  }

  /** 用当前六节 codec 重写目标唯一 settings 行，并保留源行的 id、version 与两个应用侧时间。 */
  private static void convertSystemSetting(
      Connection target, SystemSettings settings, LegacySourceGuard.SourceSettings source)
      throws SQLException {
    String converted = SETTINGS_CODEC.encode(settings);
    try (PreparedStatement update =
        target.prepareStatement(
            "update system_setting set config = cast(? as jsonb), version = ?,"
                + " created_at = cast(? as timestamptz), updated_at = cast(? as timestamptz)"
                + " where id = 1")) {
      update.setString(1, converted);
      update.setLong(2, source.version());
      update.setString(3, source.createdAt());
      update.setString(4, source.updatedAt());
      if (update.executeUpdate() != 1) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.TARGET_WRITE,
            "the target system settings row is not the canonical singleton");
      }
    }
  }

  private static void reconcile(
      Map<String, Long> counts, String table, long targetRows, long expectedRows) {
    if (targetRows != expectedRows) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_WRITE,
          "converted row counts do not reconcile with the legacy source");
    }
    counts.put(table, targetRows);
  }

  /** 目标写事务取得稳定顺序的 canonical 表 ACCESS EXCLUSIVE NOWAIT 锁；竞争即拒绝，绝不等待或重试。 */
  private static void lockCanonicalTables(Connection target) throws SQLException {
    List<String> tables = new ArrayList<>(LegacySchemaProfile.SHARED_TABLES);
    tables.addAll(LegacySchemaProfile.TARGET_ONLY_TABLES);
    tables.sort(null);
    try (Statement statement = target.createStatement()) {
      for (String table : tables) {
        statement.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE NOWAIT");
      }
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE,
          "target tables are locked by another writer");
    }
  }

  /** 目标必须是唯一 pristine 形态：默认 settings 一行、epoch 0 指针与空 manifest 各恰好一行，其余共有与新增表全空。 */
  private static void requirePristineTarget(Connection target) throws SQLException {
    for (String table : LegacySchemaProfile.SHARED_TABLES) {
      if (!SYSTEM_SETTING.equals(table) && count(target, "select count(*) from " + table) != 0) {
        throw notPristine();
      }
    }
    for (String table : LegacySchemaProfile.TARGET_ONLY_TABLES) {
      if (table.equals("plugin_catalog_revision") || table.equals("plugin_catalog_state")) {
        continue;
      }
      if (count(target, "select count(*) from " + table) != 0) {
        throw notPristine();
      }
    }
    if (count(target, "select count(*) from plugin_catalog_state") != 1
        || count(target, "select count(*) from plugin_catalog_state where id = 1 and epoch = 0")
            != 1) {
      throw notPristine();
    }
    if (count(target, "select count(*) from plugin_catalog_revision") != 1
        || count(
                target,
                "select count(*) from plugin_catalog_revision where epoch = 0"
                    + " and manifest_json = cast('{\"plugins\":{},\"catalogFingerprint\":null}' as jsonb)")
            != 1) {
      throw notPristine();
    }
    requireDefaultSystemSetting(target);
  }

  private static void requireDefaultSystemSetting(Connection target) throws SQLException {
    int rows = 0;
    long id = 0;
    long version = -1;
    SystemSettings settings = null;
    try (Statement statement = target.createStatement();
        ResultSet resultSet =
            statement.executeQuery("select id, version, config::text from system_setting")) {
      while (resultSet.next()) {
        rows++;
        id = resultSet.getLong(1);
        version = resultSet.getLong(2);
        try {
          settings = SETTINGS_CODEC.decode(resultSet.getString(3));
        } catch (RuntimeException error) {
          throw notPristine();
        }
      }
    }
    if (rows != 1 || id != 1L || version != 0L || !SystemSettings.DEFAULT.equals(settings)) {
      throw notPristine();
    }
  }

  private static LegacyDatabaseUpgradeException notPristine() {
    return new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE,
        "target is not in the unique pristine baseline shape");
  }

  /** 目标可写列（排除 generated 列）名单；同时要求源表列名与类型逐列一致，否则拒绝运输。 */
  private static List<String> copyColumns(Connection source, Connection target, String table)
      throws SQLException {
    List<String> sourceNames = new ArrayList<>();
    List<String> sourceTypes = new ArrayList<>();
    readColumnLayout(source, table, sourceNames, sourceTypes);
    List<String> targetNames = new ArrayList<>();
    List<String> targetTypes = new ArrayList<>();
    readColumnLayout(target, table, targetNames, targetTypes);
    if (targetNames.isEmpty()
        || !sourceNames.equals(targetNames)
        || !sourceTypes.equals(targetTypes)) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_WRITE,
          "legacy and target column layouts differ for a copied table");
    }
    return List.copyOf(targetNames);
  }

  private static void readColumnLayout(
      Connection connection, String table, List<String> names, List<String> types)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select a.attname, format_type(a.atttypid, a.atttypmod) from pg_attribute a"
                + " where a.attrelid = to_regclass(?) and a.attnum > 0 and not a.attisdropped"
                + " and a.attgenerated = '' order by a.attnum")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          names.add(rows.getString(1));
          types.add(rows.getString(2));
        }
      }
    }
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static PluginConfigurationRepository catalogRepository(DataSource scoped) {
    return new PostgresqlPluginConfigurationRepository(
        new PluginConfigurationJdbcStore(JdbcClient.create(scoped)),
        new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(scoped)));
  }

  private static PluginCredentialRepository credentialRepository(DataSource scoped) {
    return new PostgresqlPluginCredentialRepository(
        new PluginCredentialJdbcStore(JdbcClient.create(scoped)));
  }

  private static LegacySystemSettings parseSettings(String legacyJson) {
    try {
      return LegacySystemSettings.parse(legacyJson);
    } catch (IllegalArgumentException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONVERT,
          "legacy system settings cannot be converted");
    }
  }

  private static Map<String, String> pluginValues(
      LegacySystemSettings legacy,
      StudioPluginRegistry registry,
      LegacyStaticCredentials staticCredentials) {
    try {
      return legacy.pluginValues(
          registry,
          staticCredentials.hasRequiredSecret(registry, "comfyui"),
          staticCredentials.hasRequiredSecret(registry, "opencli"));
    } catch (IllegalArgumentException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONVERT,
          "legacy plugin values do not match the installed plugins");
    }
  }

  private static PluginCatalogBootstrap.PreparedInitial prepareInitial(
      OfflinePluginCatalog catalog,
      Map<String, String> values,
      Map<String, List<String>> configuredSecretFields) {
    try {
      return catalog.prepareInitial(values, configuredSecretFields);
    } catch (RuntimeException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONVERT,
          "the current plugin catalog rejected the upgraded configuration");
    }
  }

  /** 两个连接都必须是无遗留事务的普通连接：不接管调用方已经打开的写事务，也不假设数据源一定会给出新连接。 */
  private static Connection openIdleConnection(DataSource dataSource) {
    Connection connection;
    try {
      connection = dataSource.getConnection();
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONNECTION, "cannot open the upgrade databases");
    }
    boolean idle;
    try {
      idle = connection.getAutoCommit();
    } catch (SQLException | RuntimeException | Error error) {
      // 探测失败时刚取得、尚未交给外层的句柄仍需归还；Error 以 identity 上抛，其余固定去敏。
      throw rejectHandle(connection, openFailure(error));
    }
    if (!idle) {
      throw rejectHandle(
          connection,
          new LegacyDatabaseUpgradeException(
              LegacyDatabaseUpgradeException.Stage.CONNECTION,
              "the upgrade requires connections without an ambient transaction"));
    }
    return connection;
  }

  /** 连接取得阶段的固定失败：Error 保持 identity，其余（SQL 或未受检）固定为连接阶段失败。 */
  private static Throwable openFailure(Throwable failure) {
    if (failure instanceof Error) {
      return failure;
    }
    return new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.CONNECTION, "cannot open the upgrade databases");
  }

  /** 归还被拒绝的连接句柄：只关闭本次取得的句柄，绝不接管或回滚它自己带着的事务。 */
  private static RuntimeException rejectHandle(Connection connection, Throwable rejection) {
    return convergedFailure(
        mergeRelease(
            rejection,
            closeResource(connection),
            LegacyDatabaseUpgradeException.Stage.CONNECTION,
            "the rejected connection handle could not be returned"),
        LegacyDatabaseUpgradeException.Stage.CONNECTION);
  }

  /** 源连接只读一致快照：固定 search_path / DateStyle / UTC 并关闭 row security，再验证 PG17、UTF-8 与 origin 写角色。 */
  private static void beginSourceSnapshot(Connection source) {
    try {
      source.setAutoCommit(false);
      try (Statement statement = source.createStatement()) {
        statement.execute("set transaction isolation level repeatable read");
        statement.execute("set transaction read only");
        applySessionSettings(statement);
        requireServerSession(statement, true);
      }
    } catch (LegacyDatabaseUpgradeException error) {
      throw error;
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONNECTION,
          "cannot establish the read-only source snapshot");
    }
  }

  /** 目标写事务内的 session 前置：与源同一份 search_path / DateStyle / UTC / row_security 与服务器形态检查。 */
  private static void beginTargetSession(Connection target) {
    try (Statement statement = target.createStatement()) {
      applySessionSettings(statement);
      requireServerSession(statement, false);
    } catch (LegacyDatabaseUpgradeException error) {
      throw error;
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.CONNECTION,
          "cannot establish the target write session");
    }
  }

  /** 两边共用的会话固定项：裸表名与时间/RLS 语义必须与本内核读取目录时的假设一致。 */
  private static void applySessionSettings(Statement statement) throws SQLException {
    // 显式把 pg_temp 放最后，避免已有临时表遮蔽 public 业务表。
    statement.execute("set local search_path = pg_catalog, public, pg_temp");
    statement.execute("set local client_encoding = 'UTF8'");
    statement.execute("set local datestyle = 'ISO, MDY'");
    statement.execute("set local time zone 'UTC'");
    statement.execute("set local row_security = off");
  }

  /** 服务器形态检查：PG17、UTF-8、origin 复制角色（replica 会让真实 FK 不生效），以及事务真实可写性。 */
  private static void requireServerSession(Statement statement, boolean readOnlyExpected)
      throws SQLException {
    try (ResultSet rows =
        statement.executeQuery(
            "select current_setting('server_version_num')::int,"
                + " current_setting('server_encoding'),"
                + " current_setting('session_replication_role'),"
                + " current_setting('transaction_read_only')::boolean")) {
      rows.next();
      int serverVersion = rows.getInt(1);
      if (serverVersion < 170000 || serverVersion >= 180000) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.CONNECTION, "the upgrade requires PostgreSQL 17");
      }
      if (!"UTF8".equals(rows.getString(2))) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.CONNECTION,
            "the upgrade requires a UTF-8 database");
      }
      if (!"origin".equals(rows.getString(3))) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.CONNECTION,
            "the upgrade requires the origin replication role");
      }
      if (rows.getBoolean(4) != readOnlyExpected) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.CONNECTION,
            readOnlyExpected
                ? "the legacy source session must be read only"
                : "the target session must be writable");
      }
    }
  }

  private static TransactionStatus openTargetTransaction(
      PlatformTransactionManager manager, Connection target) {
    DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
    definition.setName("legacy-database-upgrade");
    definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    try {
      // 本调用独占连接且只用一次事务；不让事务管理器在回滚失败后恢复 autocommit，造成隐式提交。
      target.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      target.setAutoCommit(false);
      return manager.getTransaction(definition);
    } catch (SQLException error) {
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_TRANSACTION,
          "the target write transaction could not be started");
    } catch (RuntimeException error) {
      if (error.getCause() instanceof Error fatal) {
        throw fatal;
      }
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.TARGET_TRANSACTION,
          "the target write transaction could not be started");
    }
  }

  /** 提交前释放候选快照与其离线装配：两项都尝试，只有真的释放成功才允许提交；其余失败在提交前走真实回滚。 */
  private static void releaseBeforeCommit(
      PluginCatalogSnapshot snapshot, OfflinePluginCatalog catalog) {
    Throwable failure =
        mergeRelease(
            null,
            closeResource(snapshot),
            LegacyDatabaseUpgradeException.Stage.CONVERT,
            "the assembled target candidate could not be released before commit");
    failure =
        mergeRelease(
            failure,
            closeResource(catalog),
            LegacyDatabaseUpgradeException.Stage.CONVERT,
            "the assembled target candidate could not be released before commit");
    if (failure != null) {
      throw convergedFailure(failure, LegacyDatabaseUpgradeException.Stage.CONVERT);
    }
  }

  /** dry run 与真实提交的唯一差异：dry run 走同一条写入路径后回滚。通信失败一律标为结果未知，绝不重试。 */
  private static void commitOrRollback(
      PlatformTransactionManager manager, TransactionStatus transaction, boolean dryRun) {
    try {
      if (dryRun) {
        manager.rollback(transaction);
      } else {
        manager.commit(transaction);
      }
    } catch (RuntimeException error) {
      if (error.getCause() instanceof Error fatal) {
        throw uncertainTransactionError(fatal);
      }
      throw new LegacyDatabaseUpgradeException(
          LegacyDatabaseUpgradeException.Stage.COMMIT,
          "the target transaction result could not be confirmed",
          true);
    } catch (Error error) {
      throw uncertainTransactionError(error);
    }
  }

  private static Error uncertainTransactionError(Error error) {
    error.addSuppressed(
        new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.COMMIT,
            "the target transaction result could not be confirmed",
            true));
    return error;
  }

  /** 提交 Error 也按结果未知处理，不让框架追加回滚并以回滚失败覆盖原发 Error；边界处恢复其 identity。 */
  private static final class UpgradeTransactionManager extends DataSourceTransactionManager {

    private UpgradeTransactionManager(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      try {
        super.doCommit(status);
      } catch (Error error) {
        throw new TransactionSystemException(
            "the target commit result could not be confirmed", error);
      }
    }
  }

  /**
   * 失败与成功路径共用的唯一收敛点：先回滚尚未交出的事务（已交给提交/回滚路径或已结束的事务绝不重复处理，通信失败也不重试），再释放候选，最后关闭两个连接。
   *
   * <p>任一释放失败都不跳过其余资源；非 Error 一律固定去敏（原始 SQL/message/cause 绝不进入失败链），Error 保持 identity 并提升为主失败。
   *
   * <p>{@code committed} 是「一次真实提交已被证明」的事实：此后任何失败都必须提示结果需核验，绝不暗示零写入；真实回滚失败也无法排除目标已被写入。
   *
   * @return 应向外传播的失败；没有失败时为 null
   */
  private static Throwable release(
      Throwable primary,
      PlatformTransactionManager manager,
      TransactionStatus transaction,
      OfflinePluginCatalog catalog,
      PluginCatalogBootstrap.PreparedInitial prepared,
      Connection target,
      Connection source,
      boolean committed) {
    Throwable failure =
        primary == null ? null : sanitized(primary, LegacyDatabaseUpgradeException.Stage.CONVERT);
    boolean uncertain = committed;
    if (transaction != null && !transaction.isCompleted()) {
      try {
        manager.rollback(transaction);
      } catch (RuntimeException | Error rollbackFailure) {
        failure =
            mergeRelease(
                failure,
                rollbackFailure,
                LegacyDatabaseUpgradeException.Stage.COMMIT,
                "the target transaction rollback could not be confirmed");
        uncertain = true;
      }
    }
    if (prepared != null) {
      failure =
          mergeRelease(
              failure,
              closeResource(prepared.snapshot()),
              LegacyDatabaseUpgradeException.Stage.CONVERT,
              "the assembled target candidate could not be released");
    }
    if (catalog != null) {
      failure =
          mergeRelease(
              failure,
              closeResource(catalog),
              LegacyDatabaseUpgradeException.Stage.CONVERT,
              "the assembled target candidate could not be released");
    }
    LegacyDatabaseUpgradeException.Stage closeStage =
        committed
            ? LegacyDatabaseUpgradeException.Stage.COMMIT
            : LegacyDatabaseUpgradeException.Stage.CONNECTION;
    failure =
        mergeRelease(
            failure,
            closeResource(target),
            closeStage,
            "an upgrade database connection could not be released");
    failure =
        mergeRelease(
            failure,
            closeConnection(source),
            closeStage,
            "an upgrade database connection could not be released");
    if (failure == null) {
      return null;
    }
    if (!uncertain) {
      return failure;
    }
    if (failure instanceof LegacyDatabaseUpgradeException upgradeFailure) {
      upgradeFailure.markOutcomeUncertain();
      return upgradeFailure;
    }
    // Error 保持 identity 不包装：结果未知只能以固定安全提示挂在压制链上。
    failure.addSuppressed(
        new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.COMMIT,
            "the target transaction result could not be confirmed",
            true));
    return failure;
  }

  /** 释放两个连接（连接取得阶段的失败路径）：两个都尝试；非 Error 只以固定安全提示并入，Error 提升为主失败。 */
  private static Throwable releaseConnections(
      Throwable primary, Connection target, Connection source) {
    Throwable failure =
        mergeRelease(
            primary,
            closeConnection(target),
            LegacyDatabaseUpgradeException.Stage.CONNECTION,
            "an upgrade database connection could not be released");
    return mergeRelease(
        failure,
        closeConnection(source),
        LegacyDatabaseUpgradeException.Stage.CONNECTION,
        "an upgrade database connection could not be released");
  }

  /** 关闭一个连接（必要时先回滚）；任何 Throwable 之后仍尝试 close，返回真实失败（含 Error）而不是吞掉它。 */
  private static Throwable closeConnection(Connection connection) {
    if (connection == null) {
      return null;
    }
    Throwable failure = null;
    try {
      if (!connection.isClosed() && !connection.getAutoCommit()) {
        connection.rollback();
      }
    } catch (Throwable error) {
      failure = error;
    }
    try {
      connection.close();
    } catch (Throwable error) {
      failure =
          mergeRelease(
              failure,
              error,
              LegacyDatabaseUpgradeException.Stage.CONNECTION,
              "an upgrade database connection could not be released");
    }
    return failure;
  }

  /** 关闭一个候选资源；返回真实失败（含 Error），绝不吞掉。 */
  private static Throwable closeResource(AutoCloseable resource) {
    if (resource == null) {
      return null;
    }
    try {
      resource.close();
    } catch (Throwable failure) {
      return failure;
    }
    return null;
  }

  /**
   * 合并一次释放失败：Error 保持 identity 并提升为返回的主失败（原失败去敏后进入压制链，或原失败本身是首个 Error 时保留其 identity）， 其余释放失败只以固定
   * stage 与原因并入。原始 SQL/message/cause 绝不进入失败链；primary 为空时以该安全失败作为主失败。
   */
  private static Throwable mergeRelease(
      Throwable primary,
      Throwable release,
      LegacyDatabaseUpgradeException.Stage stage,
      String reason) {
    if (release == null) {
      return primary;
    }
    if (release instanceof Error releaseError) {
      if (primary == null) {
        return releaseError;
      }
      if (primary instanceof Error primaryError) {
        if (primaryError != releaseError) {
          primaryError.addSuppressed(releaseError);
        }
        return primaryError;
      }
      releaseError.addSuppressed(sanitized(primary, stage));
      return releaseError;
    }
    Throwable safePrimary = primary == null ? null : sanitized(primary, stage);
    if (safePrimary == null) {
      return new LegacyDatabaseUpgradeException(stage, reason);
    }
    safePrimary.addSuppressed(new LegacyDatabaseUpgradeException(stage, reason));
    return safePrimary;
  }

  /** 固定去敏：Error 与自身失败类型保持 identity，其余原始 SQL/未受检失败替换为只带固定原因的安全失败。 */
  private static Throwable sanitized(
      Throwable failure, LegacyDatabaseUpgradeException.Stage stage) {
    if (failure instanceof Error || failure instanceof LegacyDatabaseUpgradeException) {
      return failure;
    }
    return new LegacyDatabaseUpgradeException(
        stage, "the upgrade failed for an unclassified reason");
  }

  /** 交给调用点抛出：Error 保持 identity，其余统一为固定的安全失败类型（返回值总被调用点 throw）。 */
  private static RuntimeException convergedFailure(
      Throwable failure, LegacyDatabaseUpgradeException.Stage stage) {
    if (failure instanceof Error error) {
      throw error;
    }
    return (LegacyDatabaseUpgradeException) sanitized(failure, stage);
  }
}
