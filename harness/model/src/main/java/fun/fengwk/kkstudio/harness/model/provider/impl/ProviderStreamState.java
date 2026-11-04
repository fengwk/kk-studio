package fun.fengwk.kkstudio.harness.model.provider.impl;

import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;

/**
 * 单次 Provider 流的中立终止原因和服务档位。
 *
 * <p>子类各自负责解释协议帧及终止证据；两个字段只保存已归一化的响应事实。
 */
public abstract class ProviderStreamState {

  protected GenerationStopReason stopReason;
  protected String serviceTier;
}
