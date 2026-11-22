package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.infra.platform.PlatformCanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasStore;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次启动要冻结进 {@code stateJson} 的输入清单与输出计划。
 *
 * <p>启动事务把这些事实连同目录租用一起冻结：之后 Worker 只读持久 state，不再按名称重新解析当前目录，因此目录漂移不会改变已接受任务的输入与输出形状。
 */
final class CanvasFunctionRunPlan {

  private final PostgresqlCanvasStore canvasStore;
  private final PostgresqlCanvasResourceRepository resourceRepository;
  private final PlatformCanvasFunctionBlobAccess blobAccess;

  CanvasFunctionRunPlan(
      PostgresqlCanvasStore canvasStore,
      PostgresqlCanvasResourceRepository resourceRepository,
      PlatformCanvasFunctionBlobAccess blobAccess) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.blobAccess = Objects.requireNonNull(blobAccess, "blobAccess");
  }

  /** 冻结并校验本次运行使用的输入资源清单。 */
  List<CanvasFunctionFrozenReference> freezeManifest(
      UUID canvasId, String functionName, JsonObject args, CanvasFunctionReferencePolicy policy) {
    List<CanvasFunctionFrozenReference> manifest = new ArrayList<>();
    for (CanvasResourceReference reference : new CanvasFunction(functionName, args).references()) {
      manifest.add(freezeReference(canvasId, reference));
    }
    validateReferencePolicy(manifest, policy);
    return List.copyOf(manifest);
  }

  private CanvasFunctionFrozenReference freezeReference(
      UUID canvasId, CanvasResourceReference reference) {
    if (canvasStore.findNode(canvasId, reference.nodeId()).isEmpty()) {
      throw DomainException.validation(
          "function.args", "referenced source node must belong to the same canvas");
    }
    List<CanvasResource> resources =
        resourceRepository.findByOwnerNode(canvasId, reference.nodeId());
    if (reference.index() >= resources.size()) {
      throw DomainException.validation(
          "function.args", "reference index is outside source node resources");
    }
    CanvasResource resource = resources.get(reference.index());
    if (resource.blobId() == null) {
      throw DomainException.validation(
          "function.args", "referenced resource must be a Storage blob");
    }
    BlobFacts blob = blobAccess.findFacts(resource.blobId()).orElse(null);
    if (blob == null) {
      throw DomainException.validation(
          "function.args", "referenced blob is missing: " + resource.blobId());
    }
    return new CanvasFunctionFrozenReference(
        reference.nodeId(),
        reference.index(),
        resource.id(),
        resource.blobId(),
        CanvasResourceKind.fromMediaType(blob.mediaType()),
        resource.name(),
        blob.mediaType(),
        blob.sizeBytes(),
        blob.width(),
        blob.height(),
        blob.durationMs());
  }

  private static void validateReferencePolicy(
      List<CanvasFunctionFrozenReference> manifest, CanvasFunctionReferencePolicy policy) {
    if (manifest.size() > policy.maxReferences()) {
      throw DomainException.validation(
          "function.args", "reference manifest exceeds maxReferences=" + policy.maxReferences());
    }
    Map<CanvasResourceKind, Integer> counts = new EnumMap<>(CanvasResourceKind.class);
    for (CanvasFunctionFrozenReference reference : manifest) {
      if (!policy.allowedKinds().contains(reference.kind())) {
        throw DomainException.validation(
            "function.args", "reference kind is not allowed by function: " + reference.kind());
      }
      int count = counts.merge(reference.kind(), 1, Integer::sum);
      if (count > policy.maxFor(reference.kind())) {
        throw DomainException.validation(
            "function.args", "reference kind exceeds function limit: " + reference.kind());
      }
    }
  }

  /**
   * 冻结输出计划：按定义顺序为每个槽位预分配 Resource ID 与资源名。
   *
   * <p>计划先落库（stateJson），因此任何 Materialize 都只能写入计划内的槽位；名称为空的槽位由节点名与类型推导，解析后必须互不重名。
   *
   * <p>同 kind 的多个无名槽位已由 {@link CanvasFunctionDefinition}
   * 的构造拒绝；本处兜底的是依节点名才成立的撞名（显式名与推导名相同），按请求校验错误返回 400。
   */
  static List<CanvasFunctionFrozenOutput> freezeOutputs(
      String nodeName, CanvasFunctionDefinition definition) {
    List<CanvasFunctionOutputSpec> specs = definition.outputs();
    List<CanvasFunctionFrozenOutput> outputs = new ArrayList<>(specs.size());
    Set<String> names = new LinkedHashSet<>();
    for (int index = 0; index < specs.size(); index++) {
      String name = specs.get(index).resolveName(nodeName);
      if (!names.add(name)) {
        throw DomainException.validation(
            "function.outputs", "function output names must be unique within a run: " + name);
      }
      outputs.add(
          new CanvasFunctionFrozenOutput(UUID.randomUUID(), index, specs.get(index).kind(), name));
    }
    return List.copyOf(outputs);
  }

  /** pin 引用真实 Run 行，因此只能在 Run 行写入之后创建。 */
  static List<CanvasFunctionResourcePin> inputPins(
      UUID canvasId, UUID nodeId, UUID requestId, List<CanvasFunctionFrozenReference> manifest) {
    List<CanvasFunctionResourcePin> refs = new ArrayList<>(manifest.size());
    for (CanvasFunctionFrozenReference reference : manifest) {
      refs.add(
          new CanvasFunctionResourcePin(
              canvasId,
              nodeId,
              requestId,
              reference.resourceId(),
              CanvasFunctionResourcePin.Role.INPUT));
    }
    return List.copyOf(refs);
  }
}
