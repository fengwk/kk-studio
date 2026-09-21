package fun.fengwk.kkstudio.platform.environment.skill;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonWorkdirSyntax;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Environment Skill Package 的异步收敛编排器。
 *
 * <p>触发源只有两个：READY 会话事件（该 Environment 全量同步）与 PostgreSQL {@code skill_package_changed} 通知（单 Package
 * 增量同步，通知重连时全量对账）。编排器不缓存连接事实：每个 Package 同步前都重新读取 {@code environment_connection} 行，因此 owner + lease
 * token + 未过期 READY 三者共同围栏每一次状态与事件写回， 迟到的回调一律 fail-closed。
 *
 * <p>同一 Environment 的同步在进程内串行，不同 Environment 互不阻塞。单个 Package 失败只记录该 Package 的失败投影与事件， 既不影响其它
 * Package，也绝不覆盖上一次成功安装的事实。
 *
 * <p>调用完全复用 Environment server core 的 capability 传输契约：每次调用使用新的 UUID，结果不确定（send-uncertain） 绝不重放。
 */
@Slf4j
public class EnvironmentSkillSyncOrchestrator {

  /** PostgreSQL 通知 channel：与 schema 的 {@code skill_package_changed} 触发器一致。 */
  public static final String CHANNEL = "skill_package_changed";

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
  private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-f]{40}$|^[0-9a-f]{64}$");

  /** skill.sync 成功结果的全部字段：多一个、少一个都视为协议违约。 */
  private static final Set<String> RESULT_FIELDS =
      Set.of("packageName", "installedCommit", "localPath");

  private static final String SEND_FAILURE = "skill package sync could not be sent";
  private static final String RESULT_FAILURE = "skill package sync failed";

  private final EnvironmentRegistry environmentRegistry;
  private final SkillCatalogQueryService skillCatalogQueryService;
  private final EnvironmentCapabilityTransport capabilityTransport;
  private final ExecutorService executor;
  private final Clock clock;
  private final Map<EnvironmentId, ReentrantLock> environmentLocks = new ConcurrentHashMap<>();

  public EnvironmentSkillSyncOrchestrator(
      EnvironmentRegistry environmentRegistry,
      SkillCatalogQueryService skillCatalogQueryService,
      EnvironmentCapabilityTransport capabilityTransport,
      ExecutorService executor,
      Clock clock) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.skillCatalogQueryService =
        Objects.requireNonNull(skillCatalogQueryService, "skillCatalogQueryService");
    this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * READY 会话事件：把全局 catalog 的全部 Skill Package 依次同步到该 Environment。
   *
   * <p>立即返回，绝不在会话核心的状态推进路径上执行任何发送或数据库 IO。
   */
  public void onEnvironmentReady(EnvironmentId environmentId) {
    if (environmentId == null) {
      return;
    }
    submit(environmentId, () -> syncEnvironment(environmentId));
  }

  /**
   * Package 变更通知：把该 Package 同步到本节点当前持有的全部 READY Environment。
   *
   * <p>Package 已从 catalog 消失时不做任何写：残留投影不参与任何路径选择，最近一次 READY 的全量同步会重建投影。
   */
  public void onPackageChanged(String packageName) {
    if (packageName == null || packageName.isBlank()) {
      return;
    }
    for (EnvironmentConnection connection : readyOwnedEnvironments()) {
      EnvironmentId environmentId = connection.environmentId();
      submit(environmentId, () -> syncPackage(environmentId, packageName));
    }
  }

  /** 通知重连对账：对本节点当前持有的全部 READY Environment 各做一次全量同步。 */
  public void reconcileReadyEnvironments() {
    for (EnvironmentConnection connection : readyOwnedEnvironments()) {
      EnvironmentId environmentId = connection.environmentId();
      submit(environmentId, () -> syncEnvironment(environmentId));
    }
  }

  private void submit(EnvironmentId environmentId, Runnable task) {
    try {
      executor.execute(
          () -> {
            ReentrantLock lock =
                environmentLocks.computeIfAbsent(environmentId, key -> new ReentrantLock());
            lock.lock();
            try {
              task.run();
            } finally {
              lock.unlock();
            }
          });
    } catch (RejectedExecutionException error) {
      log.warn("environment skill sync was rejected: environmentId={}", environmentId);
    }
  }

  private List<EnvironmentConnection> readyOwnedEnvironments() {
    try {
      return environmentRegistry.listReadyOwnedByNode();
    } catch (RuntimeException error) {
      log.warn("environment skill sync could not list ready owned environments", error);
      return List.of();
    }
  }

  /** 全量同步：按 catalog 顺序逐包同步，单包失败只影响该包。 */
  private void syncEnvironment(EnvironmentId environmentId) {
    List<String> packageNames;
    try {
      packageNames =
          skillCatalogQueryService.listPackages().stream()
              .map(SkillPackage::getPackageName)
              .toList();
    } catch (RuntimeException error) {
      log.warn("environment skill sync could not list skill packages", error);
      return;
    }
    for (String packageName : packageNames) {
      try {
        syncPackage(environmentId, packageName);
      } catch (RuntimeException error) {
        // 单包的意外失败绝不中断其它包的收敛。
        log.warn("environment skill sync aborted for one package: environmentId={}", environmentId);
      }
    }
  }

  /** 同步单个 Package：读取当前行 → 记录 STARTED 事件 → 发送调用 → 在同一围栏下写回投影与终态事件。 */
  private void syncPackage(EnvironmentId environmentId, String packageName) {
    SkillPackage skillPackage = skillCatalogQueryService.getPackage(packageName);
    if (skillPackage == null) {
      return;
    }
    EnvironmentConnection connection = environmentRegistry.find(environmentId).orElse(null);
    if (connection == null || connection.status() != LiveEnvironmentStatus.READY) {
      return;
    }
    UUID leaseToken = connection.leaseToken();
    if (!recordEvent(
        environmentId,
        leaseToken,
        EnvironmentEvent.LEVEL_INFO,
        EnvironmentEvent.TYPE_SKILL_SYNC_STARTED,
        startedMessage(packageName))) {
      return;
    }
    EnvironmentSkillState previous = connection.skillStateOf(packageName).orElse(null);
    SyncOutcome outcome = invoke(connection, skillPackage);
    EnvironmentSkillState entry;
    EnvironmentEvent terminalEvent;
    if (outcome.succeeded()) {
      entry =
          EnvironmentSkillState.installed(
              packageName, outcome.installedCommit(), outcome.localPath());
      terminalEvent =
          new EnvironmentEvent(
              clock.instant(),
              EnvironmentEvent.LEVEL_INFO,
              EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED,
              terminalMessage(EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED, packageName));
    } else {
      entry = EnvironmentSkillState.failed(packageName, previous, outcome.failureSummary());
      terminalEvent =
          new EnvironmentEvent(
              clock.instant(),
              EnvironmentEvent.LEVEL_ERROR,
              EnvironmentEvent.TYPE_SKILL_SYNC_FAILED,
              terminalMessage(EnvironmentEvent.TYPE_SKILL_SYNC_FAILED, packageName));
    }
    try {
      environmentRegistry.replaceSkillState(
          environmentId, leaseToken, mergedSkillState(connection, entry), terminalEvent);
    } catch (RuntimeException error) {
      log.warn(
          "environment skill sync could not record the result: environmentId={}", environmentId);
    }
  }

  /** 以本次同步开始时读到的投影为基线合并该 Package：其它 Package 的条目原样保留。 */
  private static List<EnvironmentSkillState> mergedSkillState(
      EnvironmentConnection connection, EnvironmentSkillState entry) {
    Map<String, EnvironmentSkillState> merged = new LinkedHashMap<>();
    for (EnvironmentSkillState state : connection.skillState()) {
      merged.put(state.packageName(), state);
    }
    merged.put(entry.packageName(), entry);
    return List.copyOf(new ArrayList<>(merged.values()));
  }

  private boolean recordEvent(
      EnvironmentId environmentId, UUID leaseToken, String level, String type, String message) {
    try {
      boolean recorded =
          environmentRegistry.recordSkillEvent(
              environmentId,
              leaseToken,
              new EnvironmentEvent(clock.instant(), level, type, message));
      if (!recorded) {
        log.debug("environment skill sync fence lost: environmentId={}", environmentId);
      }
      return recorded;
    } catch (RuntimeException error) {
      log.warn("environment skill sync could not record an event: environmentId={}", environmentId);
      return false;
    }
  }

  /** 发起一次 skill.sync 调用并等待唯一终态，并在同一处按本次请求事实校验结果。 */
  private SyncOutcome invoke(EnvironmentConnection connection, SkillPackage skillPackage) {
    EnvironmentId environmentId = connection.environmentId();
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SYNC);
    EnvironmentCapabilityExecutionRequest request;
    try {
      request =
          new EnvironmentCapabilityExecutionRequest(
              descriptor,
              new EnvironmentCapabilityCall(
                  UUID.randomUUID().toString(), argumentsJson(skillPackage)),
              descriptor.defaultTimeout());
    } catch (RuntimeException error) {
      return SyncOutcome.failure(RESULT_FAILURE);
    }
    CompletableFuture<EnvironmentCapabilityResult> terminal = new CompletableFuture<>();
    EnvironmentCapabilityExecutionHandle handle;
    try {
      handle =
          capabilityTransport.invoke(
              environmentId,
              request,
              new EnvironmentCapabilityExecutionListener() {

                @Override
                public void onComplete(EnvironmentCapabilityResult result) {
                  terminal.complete(result);
                }

                @Override
                public void onError(Throwable error) {
                  terminal.completeExceptionally(error);
                }
              });
    } catch (RuntimeException error) {
      // 发送前失败：调用肯定未执行，或结果不确定而不可重放。
      return SyncOutcome.failure(SEND_FAILURE);
    }
    try {
      return interpret(
          terminal.get(descriptor.defaultTimeout().toMillis(), TimeUnit.MILLISECONDS),
          skillPackage,
          connection.daemonCapabilities().environment().operatingSystem());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      handle.cancel();
      return SyncOutcome.failure(RESULT_FAILURE);
    } catch (ExecutionException | TimeoutException | RuntimeException error) {
      handle.cancel();
      return SyncOutcome.failure(RESULT_FAILURE);
    }
  }

  /** 只接受成功结果中的精确安装事实；错误结果只保留 Daemon 的稳定错误码。 */
  private static SyncOutcome interpret(
      EnvironmentCapabilityResult result,
      SkillPackage skillPackage,
      DaemonOperatingSystem operatingSystem) {
    if (result.error()) {
      return SyncOutcome.failure(failureSummary(result.detailsJson()));
    }
    for (ResultContent content : result.contents()) {
      if (content instanceof JsonResultContent json) {
        return parseOutcome(
            json.json(),
            skillPackage.getPackageName(),
            skillPackage.getCurrentCommit(),
            operatingSystem);
      }
    }
    return SyncOutcome.failure(RESULT_FAILURE);
  }

  /**
   * 严格解析 skill.sync 终态 JSON。
   *
   * <p>成功结果必须恰好是 {@code packageName}、{@code installedCommit}、{@code localPath} 三个字段的 object：Package
   * 名与 request 一致，commit 与 request 的 currentCommit 一致且是 canonical 形状，localPath 是目标 Daemon OS 上的显式
   * 绝对路径且最后一段正是该 Package 的安装根目录。缺失、多余、未知或形状不符的值一律收敛为固定的去敏失败摘要，绝不把 Daemon 的自由文本带入投影。
   */
  private static SyncOutcome parseOutcome(
      String json,
      String packageName,
      String expectedCommit,
      DaemonOperatingSystem operatingSystem) {
    JsonNode node;
    try {
      node = JSON.readTree(json);
    } catch (JsonProcessingException error) {
      return SyncOutcome.failure(RESULT_FAILURE);
    }
    if (!node.isObject() || node.size() != RESULT_FIELDS.size()) {
      return SyncOutcome.failure(RESULT_FAILURE);
    }
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    if (!names.equals(RESULT_FIELDS)) {
      return SyncOutcome.failure(RESULT_FAILURE);
    }
    JsonNode installedCommit = node.get("installedCommit");
    JsonNode reportedPackageName = node.get("packageName");
    JsonNode localPath = node.get("localPath");
    if (!reportedPackageName.isTextual() || !packageName.equals(reportedPackageName.textValue())) {
      return SyncOutcome.failure(RESULT_FAILURE);
    }
    if (!installedCommit.isTextual()
        || !COMMIT_PATTERN.matcher(installedCommit.textValue()).matches()
        || !expectedCommit.equals(installedCommit.textValue())) {
      return SyncOutcome.failure(RESULT_FAILURE);
    }
    if (!localPath.isTextual()
        || !isPackageRoot(localPath.textValue(), packageName, operatingSystem)) {
      return SyncOutcome.failure(RESULT_FAILURE);
    }
    // localPath 按 Daemon 原生文本保留：目标 OS 的路径分隔符不在 Platform 侧改写。
    return new SyncOutcome(installedCommit.textValue(), localPath.textValue(), null);
  }

  /**
   * localPath 必须按目标 Daemon OS 的冻结形态是显式绝对路径，且最后一段就是该 Package 的安装根目录。
   *
   * <p>词法校验复用 workdir 的同一冻结形状：Platform 不解析远端路径，也不做 {@code ~} 或环境变量展开。
   */
  private static boolean isPackageRoot(
      String localPath, String packageName, DaemonOperatingSystem operatingSystem) {
    String unified;
    try {
      unified = DaemonWorkdirSyntax.requireAbsolute(localPath, operatingSystem);
    } catch (IllegalArgumentException error) {
      return false;
    }
    int separator = unified.lastIndexOf('/');
    return separator >= 0 && unified.substring(separator + 1).equals(packageName);
  }

  /** 稳定错误码是 Daemon 唯一的诊断事实：只接受受限语法，绝不复述任何自由文本。 */
  private static String failureSummary(String detailsJson) {
    JsonNode code;
    try {
      code = JSON.readTree(detailsJson).path("code");
    } catch (JsonProcessingException error) {
      return RESULT_FAILURE;
    }
    if (!code.isTextual() || !ERROR_CODE_PATTERN.matcher(code.textValue()).matches()) {
      return RESULT_FAILURE;
    }
    return RESULT_FAILURE + ": " + code.textValue();
  }

  private static String argumentsJson(SkillPackage skillPackage) {
    ObjectNode node = JSON.createObjectNode();
    node.put("packageName", skillPackage.getPackageName());
    node.put("repositoryUrl", skillPackage.getRepositoryUrl());
    node.put("branch", skillPackage.getBranch());
    node.put("targetCommit", skillPackage.getCurrentCommit());
    try {
      return JSON.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode skill.sync arguments", error);
    }
  }

  private static String startedMessage(String packageName) {
    return "skill package sync started: " + packageName;
  }

  private static String terminalMessage(String type, String packageName) {
    return type.endsWith("SUCCEEDED")
        ? "skill package sync succeeded: " + packageName
        : "skill package sync failed: " + packageName;
  }

  /** 单次同步的收敛结果：成功时携带精确提交与本地稳定路径，否则只携带去敏摘要。 */
  private record SyncOutcome(String installedCommit, String localPath, String failure) {

    static SyncOutcome failure(String failure) {
      return new SyncOutcome(null, null, failure);
    }

    boolean succeeded() {
      return failure == null;
    }

    String failureSummary() {
      return failure == null ? RESULT_FAILURE : failure;
    }
  }
}
