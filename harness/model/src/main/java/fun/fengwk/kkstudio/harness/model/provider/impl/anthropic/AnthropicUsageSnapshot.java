package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import fun.fengwk.kkstudio.harness.model.ModelUsage;

/**
 * 冻结后的 Anthropic 用量事实。
 *
 * @param usage 规范化后的 token 用量
 * @param rawUsageJson 上游 usage 段的终态序列化
 */
record AnthropicUsageSnapshot(ModelUsage usage, String rawUsageJson) {}
