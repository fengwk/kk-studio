package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.model.limits.ImageRequestLimits;
import fun.fengwk.kkstudio.harness.model.limits.RequestLimitsPresets;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinConfigSyncAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resolves the strictest known provider image-count limit for the selected model. */
@Component
public class ModelImageCountLimitResolver {

  private final ModelRepository modelRepository;
  private final ProviderRepository providerRepository;
  private final ModelGroupConfigCodec modelGroupConfigCodec;

  public ModelImageCountLimitResolver(
      ModelRepository modelRepository,
      ProviderRepository providerRepository,
      ModelGroupConfigCodec modelGroupConfigCodec) {
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.modelGroupConfigCodec =
        Objects.requireNonNull(modelGroupConfigCodec, "modelGroupConfigCodec");
  }

  /** Returns null when the execution or provider endpoint has no known protocol limit. */
  public Integer resolve(BranchSettings branchSettings) {
    ModelSelection selection = BuiltinConfigSyncAdapter.model(branchSettings.execution());
    if (selection == null) {
      return null;
    }
    Model model = modelRepository.getByName(selection.modelName());
    if (model == null) {
      return null;
    }
    if (model.kind() == ModelKind.MODEL) {
      return resolveModelLimit(model);
    }
    ModelGroupConfigDTO group = modelGroupConfigCodec.decode(model.groupConfigJson());
    List<Integer> limits = new ArrayList<>();
    for (ModelGroupMemberDTO member : group.getMembers()) {
      Model memberModel = modelRepository.getByName(member.getModelName());
      if (memberModel != null && memberModel.kind() == ModelKind.MODEL) {
        Integer limit = resolveModelLimit(memberModel);
        if (limit != null) {
          limits.add(limit);
        }
      }
    }
    return limits.stream().min(Integer::compareTo).orElse(null);
  }

  private Integer resolveModelLimit(Model model) {
    Provider provider = providerRepository.getByName(model.providerName());
    if (provider == null) {
      return null;
    }
    ImageRequestLimits limits =
        RequestLimitsPresets.resolve(provider.providerType(), provider.baseUrl()).image();
    return limits.maxCount();
  }
}
