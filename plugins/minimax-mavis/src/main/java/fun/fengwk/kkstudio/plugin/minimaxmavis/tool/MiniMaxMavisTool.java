package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceUnavailableException;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCapability;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCapabilityCatalog;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisClient;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCredential;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisException;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisRegion;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisCapabilityCache;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisCredentialPayload;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisPlugin;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MiniMaxMavisResourceAccess;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 MiniMax Mavis 能力对应的模型可见 Tool。
 *
 * <p>定义在启动期从静态资源冻结（名称、描述、schema、超时、side effect），启动不访问 Mavis；每次调用才解析凭据、对齐 capability catalog 并发起一次
 * JSON 请求。所有失败都收敛为确定性错误结果：
 *
 * <ul>
 *   <li>凭据不可用 → {@code MAVIS_CREDENTIAL_<REASON>}（不发送任何请求）；
 *   <li>catalog 缺少该 endpoint → {@code MAVIS_CAPABILITY_UNAVAILABLE}（不发送能力请求，也不动态改动 Tool 面）；
 *   <li>需要资源/媒体但没有绑定资源网关 → {@code MAVIS_RESOURCE_UNAVAILABLE}（非幂等生成在发送前失败，不消费额度）；
 *   <li>传输或业务失败 → 去敏后的有界错误文本，断连与超时一律报告为 uncertain failure，绝不重放。
 * </ul>
 *
 * <p>成功结果只保存稳定内容：纯文本能力返回有界 JSON，媒体能力把第三方临时地址暂存为 Blob 上传引用后再返回。
 */
@Slf4j
public final class MiniMaxMavisTool implements Tool {

  /** 无凭据行。 */
  private static final String CODE_NOT_CONNECTED = "MAVIS_CREDENTIAL_NOT_CONNECTED";

  /** 主密钥不可用或密文不可解。 */
  private static final String CODE_KEY_UNAVAILABLE = "MAVIS_CREDENTIAL_KEY_UNAVAILABLE";

  /** 刷新在途，稍后可重试。 */
  private static final String CODE_AUTH_REFRESHING = "MAVIS_CREDENTIAL_AUTH_REFRESHING";

  /** 需要重新登录。 */
  private static final String CODE_REAUTH_REQUIRED = "MAVIS_CREDENTIAL_REAUTH_REQUIRED";

  /** 刷新结果未知，不再使用该凭据。 */
  private static final String CODE_REFRESH_UNCERTAIN = "MAVIS_CREDENTIAL_REFRESH_UNCERTAIN";

  /** 凭据本地时限已过。 */
  private static final String CODE_EXPIRED = "MAVIS_CREDENTIAL_EXPIRED";

  /** Provider 目录不再提供该能力。 */
  private static final String CODE_CAPABILITY_UNAVAILABLE = "MAVIS_CAPABILITY_UNAVAILABLE";

  /** 本部署没有绑定 Plugin 资源网关，或资源/媒体无法在契约内取得。 */
  private static final String CODE_RESOURCE_UNAVAILABLE = "MAVIS_RESOURCE_UNAVAILABLE";

  /** 调用本身失败（HTTP、网络、协议或业务错误）；断连与超时属于不确定失败。 */
  private static final String CODE_CALL_FAILED = "MAVIS_CALL_FAILED";

  /** 响应过大，无法作为结果投影。 */
  private static final String CODE_RESPONSE_TOO_LARGE = "MAVIS_RESPONSE_TOO_LARGE";

  /** 结果是 JSON 内容时的 UTF-8 字节上限；超出即确定性失败，不做静默截断。 */
  private static final int MAX_JSON_RESULT_BYTES = 512 * 1024;

  /** 通用 renderer key：与 MCP 工具一致，插件工具没有专属前端渲染器。 */
  private static final String RENDERER_KEY = "tool";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final MavisCapability capability;
  private final ToolDescriptor descriptor;
  private final ToolSideEffect sideEffect;
  private final PluginCredentialStore credentialStore;
  private final MavisClient client;
  private final MiniMaxMavisCapabilityCache capabilityCache;
  private final MiniMaxMavisResourceAccess resourceAccess;
  private final ExecutorService executor;

  public MiniMaxMavisTool(
      MavisCapability capability,
      MavisToolDefinition definition,
      PluginCredentialStore credentialStore,
      MavisClient client,
      MiniMaxMavisCapabilityCache capabilityCache,
      MiniMaxMavisResourceAccess resourceAccess,
      ExecutorService executor) {
    this.capability = Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(definition, "definition");
    this.sideEffect = sideEffectOf(capability);
    this.descriptor =
        new ToolDescriptor(
            definition.name(),
            definition.description(),
            RENDERER_KEY,
            definition.inputSchema(),
            sideEffect,
            capability.requestTimeout());
    this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
    this.client = Objects.requireNonNull(client, "client");
    this.capabilityCache = Objects.requireNonNull(capabilityCache, "capabilityCache");
    this.resourceAccess = Objects.requireNonNull(resourceAccess, "resourceAccess");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /** 能力到 side effect 的固定映射：搜索、提取、理解、ASR、音色列表与视频查询是只读；生成类是非幂等，断连或超时不得自动重放。 */
  public static ToolSideEffect sideEffectOf(MavisCapability capability) {
    return switch (capability) {
      case WEB_SEARCH,
          EXTRACT_WEB,
          IMAGE_SEARCH,
          REVERSE_IMAGE,
          UNDERSTAND_IMAGE,
          UNDERSTAND_AUDIO,
          UNDERSTAND_VIDEO,
          ASR,
          LIST_VOICES,
          QUERY_VIDEO -> ToolSideEffect.READ_ONLY;
      case TTS, TTS_BATCH, GENERATE_IMAGE, GENERATE_MUSIC, SUBMIT_VIDEO -> ToolSideEffect
          .NON_IDEMPOTENT;
    };
  }

  /** 该能力的响应是否承载媒体：生成类与下载/查询类都会把第三方临时地址暂存为 Blob 引用，纯文本能力直接返回有界 JSON。 */
  public static boolean stagesMedia(MavisCapability capability) {
    return switch (capability) {
      case TTS,
          TTS_BATCH,
          GENERATE_IMAGE,
          GENERATE_MUSIC,
          IMAGE_SEARCH,
          REVERSE_IMAGE,
          QUERY_VIDEO -> true;
      default -> false;
    };
  }

  /** 该能力是否必须在发送前就要求资源网关存在：非幂等生成调用一旦发出就可能消费额度，因此宁可确定性失败也不在拿到结果后才失败。 */
  public static boolean requiresGatewayBeforeSend(MavisCapability capability) {
    return switch (capability) {
      case TTS, TTS_BATCH, GENERATE_IMAGE, GENERATE_MUSIC -> true;
      default -> false;
    };
  }

  public MavisCapability capability() {
    return capability;
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolRequirements requirements() {
    // 15 个工具都只依赖 Plugin 自己的远端能力，不绑定任何 Environment。
    return ToolRequirements.none();
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    String toolCallId = request.call().id();

    JsonNode arguments;
    try {
      arguments = MAPPER.readTree(request.call().argumentsJson());
      if (arguments == null || !arguments.isObject()) {
        throw new IllegalArgumentException("arguments must be a JSON object");
      }
      if (requiresGatewayBeforeSend(capability) && !resourceAccess.gatewayBound()) {
        return failed(
            listener,
            toolCallId,
            CODE_RESOURCE_UNAVAILABLE,
            "this deployment cannot stage MiniMax Mavis media output");
      }
      // 资源鉴权身份只来自本次 invocation context：没有它就绝不发送引用会话资源的请求。
      arguments = resourceAccess.resolveInputs(threadId(request), arguments);
    } catch (PluginResourceUnavailableException error) {
      return failed(listener, toolCallId, CODE_RESOURCE_UNAVAILABLE, error.getMessage());
    } catch (JsonProcessingException | IllegalArgumentException error) {
      return failed(listener, toolCallId, CODE_CALL_FAILED, "invalid tool arguments");
    }

    JsonNode resolved = arguments;
    AtomicBoolean completed = new AtomicBoolean(false);
    Future<?> worker;
    try {
      worker = submit(resolved, toolCallId, listener, completed);
    } catch (RejectedExecutionException rejected) {
      // 并发上限已满时既没有发出请求也没有产生副作用，因此是确定性的、可安全重试的失败。
      return failed(
          listener, toolCallId, CODE_CALL_FAILED, "MiniMax Mavis call could not be scheduled");
    }
    return new ToolExecutionHandle() {
      @Override
      public void cancel() {
        worker.cancel(true);
      }

      @Override
      public boolean isCancelled() {
        return worker.isCancelled();
      }
    };
  }

  private Future<?> submit(
      JsonNode resolved,
      String toolCallId,
      ToolExecutionListener listener,
      AtomicBoolean completed) {
    return executor.submit(
        () -> {
          try {
            ToolResult result = invoke(resolved, toolCallId);
            if (completed.compareAndSet(false, true)) {
              listener.onComplete(result);
            }
          } catch (PluginCredentialUnavailableException error) {
            if (completed.compareAndSet(false, true)) {
              listener.onComplete(
                  errorResult(
                      toolCallId, codeFor(error.reason()), credentialDetail(error.reason())));
            }
          } catch (PluginResourceUnavailableException error) {
            if (completed.compareAndSet(false, true)) {
              listener.onComplete(
                  errorResult(toolCallId, CODE_RESOURCE_UNAVAILABLE, error.getMessage()));
            }
          } catch (MavisException error) {
            if (completed.compareAndSet(false, true)) {
              listener.onComplete(errorResult(toolCallId, CODE_CALL_FAILED, error.getMessage()));
            }
          } catch (RuntimeException error) {
            log.debug("MiniMax Mavis tool call failed unexpectedly", error);
            if (completed.compareAndSet(false, true)) {
              listener.onComplete(
                  errorResult(toolCallId, CODE_CALL_FAILED, "MiniMax Mavis call failed"));
            }
          }
        });
  }

  /** 调用上下文里的 Harness Thread id；框架直调（无 context）时为空。 */
  private static UUID threadId(ToolExecutionRequest request) {
    ToolExecutionContext context = request.context();
    return context == null ? null : context.threadId();
  }

  private ToolResult invoke(JsonNode arguments, String toolCallId) {
    PluginCredentialSnapshot snapshot = credentialStore.resolve(MiniMaxMavisPlugin.PLUGIN_ID);
    MavisCredential credential = toCredential(snapshot);

    MavisCapabilityCatalog catalog = capabilityCache.catalog(credential);
    if (!catalog.supports(capability)) {
      return errorResult(
          toolCallId,
          CODE_CAPABILITY_UNAVAILABLE,
          "the MiniMax Mavis gateway no longer offers " + capability.endpoint());
    }

    JsonNode payload = client.invoke(credential, capability, arguments);
    if (stagesMedia(capability)) {
      List<ResourceRef> staged = resourceAccess.stageOutputs(payload, capability, toolCallId);
      if (!staged.isEmpty()) {
        return stagedResult(toolCallId, staged);
      }
    }
    return jsonResult(toolCallId, payload);
  }

  private MavisCredential toCredential(PluginCredentialSnapshot snapshot) {
    MiniMaxMavisCredentialPayload payload =
        MiniMaxMavisCredentialPayload.parse(snapshot.payloadJson());
    return new MavisCredential(
        payload.accessToken(),
        MavisRegion.parse(snapshot.region()),
        snapshot.expiresAt(),
        payload.clientUuid());
  }

  private static ToolResult stagedResult(String toolCallId, List<ResourceRef> staged) {
    List<ResultContent> contents = new ArrayList<>(staged.size());
    for (ResourceRef ref : staged) {
      contents.add(new ResourceResultContent(ref));
    }
    return new ToolResult(toolCallId, contents, false, "{\"mediaCount\":" + staged.size() + "}");
  }

  private static ToolResult jsonResult(String toolCallId, JsonNode payload) {
    String json = payload.toString();
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_RESULT_BYTES) {
      return errorResult(
          toolCallId,
          CODE_RESPONSE_TOO_LARGE,
          "MiniMax Mavis response exceeds the tool result budget");
    }
    return new ToolResult(toolCallId, List.of(new JsonResultContent(json)), false, "{}");
  }

  private static ToolResult errorResult(String toolCallId, String code, String detail) {
    String message = detail == null || detail.isBlank() ? code : code + ": " + detail;
    return ToolResult.error(toolCallId, message);
  }

  /** 凭据不可用时同时完成回调与错误返回（同步路径），避免调用方在启动期等待。 */
  private ToolExecutionHandle failed(
      ToolExecutionListener listener, String toolCallId, String code, String detail) {
    listener.onComplete(errorResult(toolCallId, code, detail));
    return new ToolExecutionHandle() {
      @Override
      public void cancel() {
        // 同步终结的调用无需取消。
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }

  private static String codeFor(PluginCredentialUnavailableReason reason) {
    return switch (reason) {
      case NOT_CONNECTED -> CODE_NOT_CONNECTED;
      case KEY_UNAVAILABLE -> CODE_KEY_UNAVAILABLE;
      case AUTH_REFRESHING -> CODE_AUTH_REFRESHING;
      case REAUTH_REQUIRED -> CODE_REAUTH_REQUIRED;
      case REFRESH_UNCERTAIN -> CODE_REFRESH_UNCERTAIN;
      case EXPIRED -> CODE_EXPIRED;
    };
  }

  private static String credentialDetail(PluginCredentialUnavailableReason reason) {
    return switch (reason) {
      case NOT_CONNECTED -> "the MiniMax Mavis plugin is not connected";
      case KEY_UNAVAILABLE -> "this deployment cannot read MiniMax Mavis credentials";
      case AUTH_REFRESHING -> "MiniMax Mavis credentials are being refreshed; retry shortly";
      case REAUTH_REQUIRED -> "MiniMax Mavis credentials were rejected; sign in again";
      case REFRESH_UNCERTAIN -> "MiniMax Mavis credential refresh outcome is unknown; sign in again";
      case EXPIRED -> "MiniMax Mavis credentials expired; sign in again";
    };
  }
}
