package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.ModelSpec;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Model 可用池的判定：快照 Model 先进入池，文件条目覆盖同名并在此判定 Provider 依赖，最后消解组依赖并按 MODEL → GROUP 排序。
 *
 * <p>只做确定性判定，不写数据库；组只能引用池中的 MODEL，任一成员缺失或 Variant 未声明时移除该组，依赖它的 Agent 随池判定被 skip。
 */
final class ConfigSyncModelPlanning {

  private final ModelRuntimeConfigParser configParser;
  private final ModelGroupConfigCodec groupConfigCodec;

  ConfigSyncModelPlanning(
      ModelRuntimeConfigParser configParser, ModelGroupConfigCodec groupConfigCodec) {
    this.configParser = configParser;
    this.groupConfigCodec = groupConfigCodec;
  }

  ModelPlan plan(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      Set<String> availableProviders,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedModels = ConfigSyncSkippedNames.of(skipped, ConfigSyncKind.MODELS);
    Map<String, ModelAvailability> availableModels = new LinkedHashMap<>();
    for (var model : snapshot.models()) {
      String key = model.name();
      if (skippedModels.contains(key)) {
        continue;
      }
      if (model.kind() == ModelKind.MODEL) {
        if (!availableProviders.contains(model.providerName())) {
          continue;
        }
        // 快照中未被文件覆盖的 Model 以 DB config 为准；解码失败与写入路径一致地硬拒绝。
        availableModels.put(
            key,
            new ModelAvailability(
                key,
                ModelKind.MODEL,
                configParser.decode(model.configJson()),
                null,
                model.providerName()));
      } else {
        availableModels.put(
            key,
            new ModelAvailability(
                key,
                ModelKind.GROUP,
                null,
                groupConfigCodec.decode(model.groupConfigJson()),
                null));
      }
    }
    Map<String, ProviderIdentity> providerIdentities = new HashMap<>();
    for (Provider provider : snapshot.providers()) {
      providerIdentities.put(
          provider.name(), new ProviderIdentity(provider.providerType(), provider.baseUrl()));
    }
    Map<String, ProviderIdentity> changedProviders = new HashMap<>();
    for (ConfigSyncParser.ProviderSpec providerSpec : document.providers()) {
      ProviderIdentity target =
          new ProviderIdentity(providerSpec.providerType(), providerSpec.properties().getBaseUrl());
      ProviderIdentity current = providerIdentities.put(providerSpec.name(), target);
      if (current != null && !Objects.equals(current.endpoint(), target.endpoint())) {
        changedProviders.put(providerSpec.name(), target);
      }
    }
    Map<String, ModelSpec> candidateModels = new LinkedHashMap<>();
    for (ModelSpec spec : document.models()) {
      String key = spec.name();
      ModelKind kind = spec.kind();
      if (kind == ModelKind.MODEL) {
        if (!availableProviders.contains(spec.providerName())) {
          skipped.add(
              new ConfigSyncSkipped(
                  ConfigSyncKind.MODELS, key, "missing provider: " + spec.providerName()));
          skippedModels.add(key);
          availableModels.remove(key);
          continue;
        }
        ProviderIdentity providerIdentity = providerIdentities.get(spec.providerName());
        SingleModelCreateDTO model = (SingleModelCreateDTO) spec.properties();
        ModelService.validateVariantCapabilities(
            spec.providerName(),
            providerIdentity.providerType(),
            providerIdentity.endpoint(),
            model.getModelId(),
            spec.config());
      }
      // 文件内同名 Model 覆盖快照：预检查按导入后将生效的配置判定。
      availableModels.put(
          key, new ModelAvailability(key, kind, spec.config(), spec.group(), spec.providerName()));
      candidateModels.put(key, spec);
    }
    removeUnsatisfiedGroups(availableModels, skippedModels, skipped);
    List<ModelSpec> ordered = orderModels(candidateModels, availableModels, imported);
    validateRetainedModels(snapshot, changedProviders, ordered);
    return new ModelPlan(availableModels, ordered);
  }

  /** 只排除实际写入计划中的模型；跳过的 YAML 条目不改变数据库中的模型。 */
  private void validateRetainedModels(
      ConfigSyncSnapshot snapshot,
      Map<String, ProviderIdentity> changedProviders,
      List<ModelSpec> ordered) {
    if (changedProviders.isEmpty()) {
      return;
    }
    Set<String> plannedNames = ordered.stream().map(ModelSpec::name).collect(Collectors.toSet());
    for (var model : snapshot.models()) {
      if (model.kind() != ModelKind.MODEL || plannedNames.contains(model.name())) {
        continue;
      }
      ProviderIdentity target = changedProviders.get(model.providerName());
      if (target != null) {
        ModelService.validateVariantCapabilities(
            model.providerName(),
            target.providerType(),
            target.endpoint(),
            model.modelId(),
            configParser.decode(model.configJson()));
      }
    }
  }

  /** 组只能引用可用的 MODEL；任一成员缺失或 Variant 未声明时移除该组，依赖它的 Agent 随池判定被 skip。 */
  private static void removeUnsatisfiedGroups(
      Map<String, ModelAvailability> availableModels,
      Set<String> skippedModels,
      List<ConfigSyncSkipped> skipped) {
    boolean modelPoolChanged = true;
    while (modelPoolChanged) {
      modelPoolChanged = false;
      for (Map.Entry<String, ModelAvailability> entry : availableModels.entrySet()) {
        String reason = groupUnsatisfiedReason(entry.getValue(), availableModels);
        if (reason == null) {
          continue;
        }
        skipped.add(new ConfigSyncSkipped(ConfigSyncKind.MODELS, entry.getKey(), reason));
        skippedModels.add(entry.getKey());
        availableModels.remove(entry.getKey());
        modelPoolChanged = true;
        break;
      }
    }
  }

  /** 写入顺序先 MODEL 后 GROUP：同一次导入中的组必须能看到已插入的成员行。 */
  private static List<ModelSpec> orderModels(
      Map<String, ModelSpec> candidateModels,
      Map<String, ModelAvailability> availableModels,
      List<ConfigSyncRef> imported) {
    List<ModelSpec> models = new ArrayList<>();
    for (ModelKind kind : List.of(ModelKind.MODEL, ModelKind.GROUP)) {
      for (ModelSpec spec : candidateModels.values()) {
        if (!availableModels.containsKey(spec.name()) || spec.kind() != kind) {
          continue;
        }
        models.add(spec);
        imported.add(ConfigSyncRefs.model(spec.name()));
      }
    }
    return models;
  }

  /** 非组的可用性恒为满足；组要求每个成员都是当前池中的 MODEL 且 Variant 已声明。 */
  private static String groupUnsatisfiedReason(
      ModelAvailability model, Map<String, ModelAvailability> availableModels) {
    if (model.kind() == ModelKind.MODEL) {
      return null;
    }
    for (var member : model.group().getMembers()) {
      ModelAvailability target = availableModels.get(member.getModelName());
      if (target == null) {
        return "missing group member: " + member.getModelName();
      }
      if (target.kind() != ModelKind.MODEL) {
        return "group member is not a model: " + member.getModelName();
      }
      if (member.getVariant() != null && !member.getVariant().isBlank()) {
        try {
          ModelDefaultVariantResolver.resolveConfiguredVariant(
              member.getModelName(), member.getVariant(), target.config());
        } catch (IllegalArgumentException error) {
          return "group member references unknown variant: " + member.getModelName();
        }
      }
    }
    return null;
  }

  /** 一类 Model 在导入后将生效的形态；{@code group} 仅对 GROUP 有值，{@code config} 仅对 MODEL 有值。 */
  record ModelAvailability(
      String name,
      ModelKind kind,
      ModelConfigDTO config,
      ModelGroupConfigDTO group,
      String providerName) {}

  record ModelPlan(Map<String, ModelAvailability> available, List<ModelSpec> ordered) {}

  private record ProviderIdentity(ProviderType providerType, String endpoint) {
    private ProviderIdentity {
      endpoint = endpoint == null || endpoint.isBlank() ? null : endpoint.strip();
    }
  }
}
