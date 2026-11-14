package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import fun.fengwk.kkstudio.harness.model.ModelUsage;

/**
 * 冻结后的 Gemini 用量事实。
 *
 * @param usage 归一化后的 token 用量
 * @param rawUsageJson 最新 usage 快照的原始序列化
 * @param serviceTier 快照报告的 service tier，可为 null
 */
record GeminiUsageSnapshot(ModelUsage usage, String rawUsageJson, String serviceTier) {}
