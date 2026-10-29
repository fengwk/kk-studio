package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.Objects;
import java.util.UUID;

/**
 * Driver 在调用方事务内解析的 Turn 计划。
 *
 * <p>Runtime 负责插入 candidate Entry、推进 Thread 与提交命令；{@link Accepted#install} 由 Driver 在同一事务内创建 私有
 * generation 状态并请求 Model Work。Runtime 不读取或强转该私有状态。
 */
public sealed interface TurnResolution
    permits TurnResolution.Rejected, TurnResolution.Compaction, TurnResolution.Accepted {

  /** 确定性拒绝：Runtime 写入 AssistantError + FAILED TURN_END，不创建 Driver 状态。 */
  record Rejected(AssistantError error) implements TurnResolution {
    public Rejected {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 压缩 Turn：Runtime 使用中立计划创建子执行并冻结 TURN_START。 */
  record Compaction(CompactionResolution resolution) implements TurnResolution {
    public Compaction {
      resolution = Objects.requireNonNull(resolution, "resolution");
    }
  }

  /** 普通生成 Turn：Runtime 冻结能力并调用 {@link #install} 提交 Driver 私有状态。 */
  non-sealed interface Accepted extends TurnResolution {

    Integer contextWindow();

    Integer maxOutputTokens();

    TurnCapabilities capabilities();

    /** 实际执行的模型选择；与设置一致时 Runtime 不写 fallback。 */
    ModelSelection executedSelection();

    /**
     * 在 Runtime 已插入 TURN_START / 输入 Entry 并推进 Thread 后，由 Driver 创建私有 generation 状态与 MODEL Work。
     *
     * <p>必须使用调用方事务；不得开启新事务或执行外部 I/O。
     */
    void install(
        HarnessStore.Transaction tx, UUID threadId, UUID turnStartEntryId, UUID requestHeadEntryId);
  }
}
