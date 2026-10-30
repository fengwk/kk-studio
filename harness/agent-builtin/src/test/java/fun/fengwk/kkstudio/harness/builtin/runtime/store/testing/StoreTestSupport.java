package fun.fengwk.kkstudio.harness.builtin.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.ASK_USER_QUESTIONNAIRE;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.askUserBinding;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.hostBinding;

import fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builtin model invocation 测试 fixture；neutral store fixtures 由 runtime test-jar 提供。 */
public final class StoreTestSupport {

  private StoreTestSupport() {}

  /** READY（非 terminal）或 CANCELLED（terminal，可携带 resultEntryId）的 model invocation。 */
  public static ModelInvocation modelInvocation(
      UUID id,
      UUID threadId,
      UUID turnStartEntryId,
      UUID requestHeadEntryId,
      ModelInvocationStatus status,
      UUID resultEntryId,
      Instant createdAt) {
    if (status != ModelInvocationStatus.READY && status != ModelInvocationStatus.CANCELLED) {
      throw new IllegalArgumentException("fixture supports READY and CANCELLED only");
    }
    ModelInvocationError error = status == ModelInvocationStatus.CANCELLED ? modelError() : null;
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        requestHeadEntryId,
        modelRequest(),
        status,
        0,
        null,
        null,
        error,
        resultEntryId,
        List.of(),
        createdAt,
        createdAt);
  }

  public static ModelRequestSpec modelRequest() {
    ProviderRequest provider = providerRequest();
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        provider.model(),
        provider.variant(),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        provider.cacheControl());
  }

  /** SUCCEEDED live-attach 的机械请求：单一 bash binding（renderer 由 binding 派生，与 assistant ToolCall 全等）。 */
  public static ModelRequestSpec succeededRequest() {
    ModelRequestSpec base = modelRequest();
    return new ModelRequestSpec(
        base.providerType(),
        base.providerConnectionGenerationId(),
        base.model(),
        base.variant(),
        1024,
        "Test system instruction.",
        List.of(hostBinding()),
        List.of(),
        base.cacheControl());
  }

  /** 只有 {@code ask_user} binding 的 frozen request：assistant ToolCall 必须与该 binding 全等。 */
  public static ModelRequestSpec askUserRequest() {
    ModelRequestSpec base = modelRequest();
    return new ModelRequestSpec(
        base.providerType(),
        base.providerConnectionGenerationId(),
        base.model(),
        base.variant(),
        1024,
        "Test system instruction.",
        List.of(askUserBinding()),
        List.of(),
        base.cacheControl());
  }

  /** 单个 {@code ask_user} 调用的 provider response（参数即冻结问卷原文）。 */
  public static ProviderResponse askUserResponse(String toolCallId) {
    return new ProviderResponse(
        "assistant reply",
        "",
        List.of(new ProviderToolCall(toolCallId, "ask_user", ASK_USER_QUESTIONNAIRE)),
        GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L),
        "req-1",
        null,
        "{}");
  }

  /** SUCCEEDED assistant payload：由同一 request/response 经 mapper 机械派生（strict attach 校验要求全等）。 */
  public static MessagePayload mappedAssistant(
      ModelRequestSpec requestSpec, ProviderResponse response) {
    return mappedAssistant(requestSpec, response, T2);
  }

  public static MessagePayload mappedAssistant(
      ModelRequestSpec requestSpec, ProviderResponse response, Instant requestedAt) {
    return new BuiltinHistoryPayloadMapper()
        .assistantPayload(
            response, requestSpec.toolBindings(), actualSelection(requestSpec), requestedAt);
  }

  private static ModelInvocationError modelError() {
    return new ModelInvocationError(ProviderErrorKind.TRANSIENT, "model boom");
  }

  private static ProviderRequest providerRequest() {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ModelSelection actualSelection(ModelRequestSpec requestSpec) {
    if (requestSpec.groupExecution() == null) {
      return ModelSelection.model(requestSpec.model().modelName(), requestSpec.variant().id());
    }
    var member = requestSpec.groupExecution().members().getFirst();
    return ModelSelection.model(member.model().modelName(), member.variant().id());
  }
}
