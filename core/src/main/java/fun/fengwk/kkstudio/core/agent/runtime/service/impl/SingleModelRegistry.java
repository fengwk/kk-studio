package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;

final class SingleModelRegistry implements ModelRegistry {

  private final ModelInfo modelInfo;

  SingleModelRegistry(ModelInfo modelInfo) {
    this.modelInfo = modelInfo;
  }

  @Override
  public void registerModel(ModelInfo modelInfo) {
    throw new UnsupportedOperationException("registerModel is not supported");
  }

  @Override
  public ModelInfo getModel(String provider, String model) {
    if (modelInfo == null) {
      return null;
    }
    if (!modelInfo.getProvider().equals(provider)) {
      return null;
    }
    return modelInfo.getName().equals(model) ? modelInfo : null;
  }
}
