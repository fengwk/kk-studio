package fun.fengwk.kkstudio.harness.spi.compaction;

import fun.fengwk.kkstudio.harness.api.history.EntryPath;

import java.util.List;

/**
 * 请求输入 token 估算端口：按 root-to-head {@link EntryPath} 与调用方准备的 system 消息内容，返回这次请求进入 provider 上下文的输入
 * token 估算。
 *
 * <p>这是内核外部（平台的 Turn 解析）计算「剩余上下文」输出预算的窄契约：本接口只暴露 API 值类型，不泄漏 {@code runtime.compaction}
 * 的估算实现。实现由组合根提供，当前是 {@code
 * fun.fengwk.kkstudio.harness.runtime.compaction.TokenEstimator#estimateRequestTokens} 的
 * 方法引用，因此估算与正式发送同源、不存在第二份口径，也不依赖 {@code HarnessRuntime} 这类门面 bean——装配期没有循环。
 *
 * <p>实现必须 fail closed：路径与其引用的压缩事实不一致时抛出，绝不返回兜底常量或估算 0。
 */
@FunctionalInterface
public interface RequestTokenEstimator {

  /**
   * 估算一次普通请求的输入 token：按 provider 顺序排列的 system 消息内容与当前路径可见对话 projection 之和。
   *
   * @param path root-to-head 候选路径
   * @param systemMessages 本次请求发送的 baseline 和 system-change 消息内容
   * @return 输入 token 估算值，不包含输出预算
   */
  long estimateRequestTokens(EntryPath path, List<String> systemMessages);
}
