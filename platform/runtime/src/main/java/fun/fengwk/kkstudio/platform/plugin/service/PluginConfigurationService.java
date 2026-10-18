package fun.fengwk.kkstudio.platform.plugin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.FixedPluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plugin 普通配置与凭据的唯一写入口：保存配置、登录完成与断连。
 *
 * <p>保存配置在同一事务内以 revision CAS 写普通值，并按秘密补丁（KEEP / SET / CLEAR）写入或删除凭据行。静态密钥的 {@code
 * contextFields}（例如目标地址）变化而本次没有重新录入密钥时，旧凭据被删除，避免把密钥发往新的目标。保存前先用候选配置与候选凭据 configure
 * 一次目标插件，配置不可用时直接拒绝。提交后唤醒本节点目录刷新与凭据刷新调度。
 */
@Slf4j
public final class PluginConfigurationService {

  private static final String RESOURCE = "plugin";
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final StudioPluginRegistry registry;
  private final PluginConfigurationRepository configurations;
  private final PluginCredentialRepository credentials;
  private final PluginContextFactory contextFactory;
  private final PluginAgentDependencies agentDependencies;
  private final TransactionTemplate transactions;
  private final Runnable catalogChanged;
  private final Runnable credentialChanged;

  public PluginConfigurationService(
      StudioPluginRegistry registry,
      PluginConfigurationRepository configurations,
      PluginCredentialRepository credentials,
      PluginContextFactory contextFactory,
      PluginAgentDependencies agentDependencies,
      PlatformTransactionManager transactionManager,
      Runnable catalogChanged,
      Runnable credentialChanged) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.configurations = Objects.requireNonNull(configurations, "configurations");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
    this.agentDependencies = Objects.requireNonNull(agentDependencies, "agentDependencies");
    this.transactions = new TransactionTemplate(transactionManager);
    this.catalogChanged = Objects.requireNonNull(catalogChanged, "catalogChanged");
    this.credentialChanged = Objects.requireNonNull(credentialChanged, "credentialChanged");
  }

  /**
   * 保存普通配置与秘密补丁。
   *
   * @param expectedRevision 期望的当前配置修订；不匹配即版本冲突
   * @param secretPatch 秘密字段补丁；省略的声明字段按 KEEP 处理
   * @return 保存后的配置修订
   */
  public long configure(
      String pluginId,
      long expectedRevision,
      String valuesJson,
      Map<String, PluginSecretPatch> secretPatch) {
    StudioPlugin plugin = requirePlugin(pluginId);
    Map<String, PluginSecretPatch> patch = requirePatch(plugin, secretPatch);
    String resolved;
    try {
      resolved = plugin.configurationSchema().resolveValues(valuesJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, "valuesJson must match the plugin schema");
    }
    PluginConfigurationRow current =
        configurations.find(pluginId).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
    if (current.revision() != expectedRevision) {
      throw conflict(expectedRevision, current.revision());
    }
    Optional<PluginCredentialRow> existing = credentials.find(pluginId);
    CredentialChange change = credentialChange(plugin, current, resolved, patch, existing);
    verifyConfigures(plugin, new PluginConfig(expectedRevision + 1, resolved), change, existing);
    transactions.executeWithoutResult(
        status -> {
          agentDependencies.requireDeclaredAgents(pluginId, expectedRevision + 1, resolved);
          if (!configurations.update(pluginId, expectedRevision, resolved)) {
            throw conflict(
                expectedRevision, configurations.find(pluginId).orElseThrow().revision());
          }
          switch (change.action()) {
            case WRITE -> credentials.upsertConnected(
                pluginId, change.payloadJson(), null, null, null);
            case REMOVE -> credentials.delete(pluginId);
            case NONE -> {
              // 只改普通配置。
            }
          }
          configurations.notifyChanged(pluginId);
          afterCommit(change.action() != CredentialAction.NONE);
        });
    return expectedRevision + 1;
  }

  /** 登录完成：用交互材料替换凭据行。材料的 region 与时限由调用方校验。 */
  public void completeAuth(String pluginId, PluginCredentialMaterial material) {
    requirePlugin(pluginId);
    transactions.executeWithoutResult(
        status -> {
          credentials.upsertConnected(
              pluginId,
              material.payloadJson(),
              material.region(),
              material.expiresAt(),
              material.nextRefreshAt());
          configurations.notifyChanged(pluginId);
          afterCommit(true);
        });
  }

  /** 断连：删除凭据行；未连接时同样成功。 */
  public void disconnect(String pluginId) {
    requirePlugin(pluginId);
    transactions.executeWithoutResult(
        status -> {
          credentials.delete(pluginId);
          configurations.notifyChanged(pluginId);
          afterCommit(true);
        });
  }

  /** 由补丁与 context 字段变化决定本次对凭据行的动作。 */
  private static CredentialChange credentialChange(
      StudioPlugin plugin,
      PluginConfigurationRow current,
      String resolved,
      Map<String, PluginSecretPatch> patch,
      Optional<PluginCredentialRow> existing) {
    boolean touched =
        patch.values().stream()
            .anyMatch(operation -> operation.operation() != PluginSecretPatch.Operation.KEEP);
    boolean contextChanged =
        contextChanged(plugin.authenticationSpec(), current.valuesJson(), resolved);
    if (!touched && !contextChanged) {
      return CredentialChange.NONE;
    }
    ObjectNode payload = new ObjectNode(NODES);
    if (!contextChanged && existing.isPresent()) {
      payload = declaredPayload(plugin, existing.get().payloadJson());
    }
    for (Map.Entry<String, PluginSecretPatch> operation : patch.entrySet()) {
      switch (operation.getValue().operation()) {
        case SET -> payload.put(operation.getKey(), operation.getValue().value());
        case CLEAR -> payload.remove(operation.getKey());
        case KEEP -> {
          // 保留（context 变化时旧值已被丢弃）。
        }
      }
    }
    if (payload.isEmpty()) {
      return existing.isPresent() ? CredentialChange.REMOVE : CredentialChange.NONE;
    }
    String payloadJson =
        BoundedJsonWriter.write(payload, PluginCredentialMaterial.MAX_PAYLOAD_UTF8_BYTES);
    if (payloadJson == null) {
      throw new AiValidationException(
          RESOURCE, "secret patch payload must not exceed the credential budget");
    }
    return new CredentialChange(CredentialAction.WRITE, payloadJson);
  }

  private static boolean contextChanged(PluginAuthSpec authSpec, String before, String after) {
    if (authSpec.contextFields().isEmpty()) {
      return false;
    }
    JsonNode previous = JsonValues.readTree(before);
    JsonNode next = JsonValues.readTree(after);
    for (String field : authSpec.contextFields()) {
      if (!Objects.equals(previous.get(field), next.get(field))) {
        return true;
      }
    }
    return false;
  }

  /** 用候选配置与候选凭据 configure 一次目标插件；失败即拒绝保存。 */
  private void verifyConfigures(
      StudioPlugin plugin,
      PluginConfig config,
      CredentialChange change,
      Optional<PluginCredentialRow> existing) {
    String pluginId = plugin.descriptor().pluginId();
    PluginCredentialSnapshot candidate =
        switch (change.action()) {
          case WRITE -> new PluginCredentialSnapshot(pluginId, null, null, 0, change.payloadJson());
          case REMOVE -> null;
          case NONE -> existing
              .map(
                  row ->
                      new PluginCredentialSnapshot(
                          pluginId,
                          row.region(),
                          row.expiresAt(),
                          row.version(),
                          row.payloadJson()))
              .orElse(null);
        };
    try (PluginInstance ignored =
        plugin.configure(
            config, contextFactory.create(new FixedPluginCredentialAccess(candidate)))) {
      // configure 成功即可；实例立即关闭。
    } catch (RuntimeException error) {
      log.debug(
          "Plugin {} rejected the candidate configuration: {}",
          pluginId,
          error.getClass().getSimpleName());
      throw new AiValidationException(RESOURCE, "plugin rejected the configuration");
    }
  }

  /** 已有载荷限制到 schema 声明的秘密字段，避免把不透明内容带入静态补丁。 */
  private static ObjectNode declaredPayload(StudioPlugin plugin, String payloadJson) {
    JsonNode node = JsonValues.readTree(payloadJson);
    ObjectNode declared = new ObjectNode(NODES);
    for (String field : plugin.configurationSchema().secrets().keySet()) {
      JsonNode value = node.get(field);
      if (value != null) {
        declared.set(field, value);
      }
    }
    return declared;
  }

  private static Map<String, PluginSecretPatch> requirePatch(
      StudioPlugin plugin, Map<String, PluginSecretPatch> secretPatch) {
    Map<String, PluginSecretPatch> patch = new LinkedHashMap<>();
    if (secretPatch == null) {
      return patch;
    }
    boolean interactive = plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink;
    for (Map.Entry<String, PluginSecretPatch> entry : secretPatch.entrySet()) {
      if (!plugin.configurationSchema().secrets().containsKey(entry.getKey())) {
        // 字段名来自请求体，绝不回显。
        throw new AiValidationException(RESOURCE, "secret patch must name a declared secret field");
      }
      PluginSecretPatch operation = Objects.requireNonNull(entry.getValue(), "secretPatch value");
      if (interactive && operation.operation() != PluginSecretPatch.Operation.KEEP) {
        throw new AiValidationException(
            RESOURCE, "interactive credentials are replaced by login completion");
      }
      patch.put(entry.getKey(), operation);
    }
    return patch;
  }

  /** 提交后唤醒本节点目录刷新，凭据被改写时额外唤醒刷新调度。 */
  private void afterCommit(boolean credentialWritten) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            catalogChanged.run();
            if (credentialWritten) {
              credentialChanged.run();
            }
          }
        });
  }

  private StudioPlugin requirePlugin(String pluginId) {
    return registry.find(pluginId).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private static AiVersionConflictException conflict(long expected, long actual) {
    return new AiVersionConflictException(RESOURCE, "revision=" + expected, "revision=" + actual);
  }

  private enum CredentialAction {
    NONE,
    WRITE,
    REMOVE
  }

  private record CredentialChange(CredentialAction action, String payloadJson) {
    static final CredentialChange NONE = new CredentialChange(CredentialAction.NONE, null);
    static final CredentialChange REMOVE = new CredentialChange(CredentialAction.REMOVE, null);
  }
}
