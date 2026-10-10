package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;
import java.util.UUID;

/**
 * 安全边界上 append 的 branch settings 应用快照。
 *
 * <p>它把一批 queued SET_* 命令归约后的 {@link BranchSettings} 完整快照追加到历史，是 append-only 的 branch 事实：不打开 / 关闭
 * Turn、不调度模型、不产生任何消息。{@link EntryPath#baseSettings()} 与跳过尾部控制 Entry 的 classifier / materializer
 * 都必须把它视为最新生效快照；它绝不是人工空 turn，也不改写旧 TURN_START。
 *
 * <p>{@code ownerThreadId} 记录应用该快照的 Thread，使 Store 能像 {@link TurnStartPayload} 一样校验被消费 Command 的
 * appliedEntryId 归属。
 */
public record SettingsPayload(BranchSettings settings, UUID ownerThreadId) implements EntryPayload {

  public SettingsPayload {
    settings = Objects.requireNonNull(settings, "settings");
    ownerThreadId = Objects.requireNonNull(ownerThreadId, "ownerThreadId");
  }

  @Override
  public EntryType type() {
    return EntryType.SETTINGS;
  }
}
