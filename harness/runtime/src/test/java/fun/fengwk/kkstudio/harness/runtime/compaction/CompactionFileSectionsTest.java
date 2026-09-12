package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CompactionFileSections 契约：从被摘要范围重算 read/write/edit 文件清单，modified 覆盖 read、字典序去重、HISTORY 阶段 只覆盖到
 * turnPrefixStartEntryId；引用缺失/顺序非法 fail closed。
 */
class CompactionFileSectionsTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final Instant BASE = Instant.ofEpochSecond(1000L);
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"));

  @Test
  void extractsReadWriteAndEditWithModifiedWinningOverRead() {
    // 第一个 turn：read z.txt、read a.txt、write a.txt（modified 覆盖 read）、edit b.txt；cut=TE。
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("read", "z.txt", "read", "a.txt", "write", "a.txt", "edit", "b.txt");
    path.toolResults();
    long te1 = path.turnEnd(ts1);
    path.turnStart(); // 当前压缩 turn

    String sections = CompactionFileSections.sections(path.path(), id(te1));

    assertEquals(
        "<read-files>\nz.txt\n</read-files>\n\n<modified-files>\na.txt\nb.txt\n</modified-files>",
        sections);
  }

  @Test
  void ignoresNonFileToolCallsAndScansOnlyAssistantMessages() {
    // bash 调用无 path 参数被忽略；TOOL result 消息不产生文件条目。
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("bash", "no-path", "read", "kept.txt");
    path.toolResults();
    long te1 = path.turnEnd(ts1);
    path.turnStart();

    assertEquals(
        "<read-files>\nkept.txt\n</read-files>",
        CompactionFileSections.sections(path.path(), id(te1)));
  }

  @Test
  void finalSectionsScanCumulativelyThroughCut() {
    // HISTORY partial 不生成清单；最终 complete 结果从 ROOT 累计到 cut，包含 history 与 prefix。
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("read", "history.txt");
    path.toolResults();
    path.turnEnd(ts1);
    long ts2 = path.turnStart();
    path.user("u2");
    path.assistant("read", "prefix.txt");
    path.toolResults();
    long te2 = path.turnEnd(ts2);
    path.turnStart(); // 当前压缩 turn

    assertEquals(
        "<read-files>\nhistory.txt\nprefix.txt\n</read-files>",
        CompactionFileSections.sections(path.path(), id(te2)));
  }

  @Test
  void missingReferencesFailClosed() {
    // cut 引用不在当前路径 -> 分支损坏。
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("read", "a.txt");
    path.toolResults();
    long te1 = path.turnEnd(ts1);
    path.turnStart();

    IllegalStateException cutError =
        assertThrows(
            IllegalStateException.class,
            () -> CompactionFileSections.sections(path.path(), id(999L)));
    assertTrue(cutError.getMessage().contains(id(999L).toString()), cutError.getMessage());
  }

  @Test
  void cumulativeFromBranchStartIncludesEarlierFileOpsBeforePreviousCompaction() {
    // 已完成一次压缩后，下一次压缩仍从分支起点累计文件操作。
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("read", "before.txt");
    path.toolResults();
    long te1 = path.turnEnd(ts1);
    path.compactionComplete(id(te1)); // 第一次 FULL 压缩
    long ts2 = path.turnStart();
    path.user("u2");
    long asst2 = path.assistant("read", "new.txt");
    path.toolResults();
    long te2 = path.turnEnd(ts2);
    path.turnStart(); // 当前压缩 turn

    assertEquals(
        "<read-files>\nbefore.txt\nnew.txt\n</read-files>",
        CompactionFileSections.sections(path.path(), id(te2)));
  }

  @Test
  void laterWriteOverridesReadFromBeforePreviousCompaction() {
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("read", "a.txt");
    path.toolResults();
    long te1 = path.turnEnd(ts1);
    path.compactionComplete(id(te1));
    long ts2 = path.turnStart();
    path.user("u2");
    path.assistant("write", "a.txt");
    path.toolResults();
    long te2 = path.turnEnd(ts2);
    path.turnStart();

    assertEquals(
        "<modified-files>\na.txt\n</modified-files>",
        CompactionFileSections.sections(path.path(), id(te2)));
  }

  @Test
  void blankPathArgumentsAreIgnored() {
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("read", "kept.txt", "read", "  ", "read", "</read-files>");
    path.toolResults();
    long te1 = path.turnEnd(ts1);
    path.turnStart();

    assertEquals(
        "<read-files>\nkept.txt\n</read-files>",
        CompactionFileSections.sections(path.path(), id(te1)));
  }

  @Test
  void reservedSectionsAreRemovedAndMalformedTagsFailClosed() {
    assertEquals(
        "summary",
        CompactionFileSections.stripReservedSections(
            "summary\n\n<read-files>\na.txt\n</read-files>\n\n"
                + "<modified-files>\nb.txt\n</modified-files>"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CompactionFileSections.stripReservedSections("summary\n<read-files>\na.txt"));
  }

  @Test
  void appendAddsSectionsOrReturnsSummaryUnchanged() {
    PathBuilder path = new PathBuilder();
    path.root();
    long ts1 = path.turnStart();
    path.user("u");
    path.assistant("read", "a.txt");
    path.toolResults();
    long te1 = path.turnEnd(ts1);
    path.turnStart();

    assertEquals(
        "summary\n\n<read-files>\na.txt\n</read-files>",
        CompactionFileSections.append(path.path(), id(te1), "summary"));

    // 范围内没有任何文件工具调用 -> 原样返回，不产生 section。
    PathBuilder clean = new PathBuilder();
    clean.root();
    long cleanTs = clean.turnStart();
    clean.user("u");
    clean.assistant("bash", "no-path");
    clean.toolResults();
    long cleanTe = clean.turnEnd(cleanTs);
    clean.turnStart();
    assertEquals("summary", CompactionFileSections.append(clean.path(), id(cleanTe), "summary"));
  }

  /** 自包含合法路径构造器：id 顺序、parent 链、TURN 语法。 */
  private static final class PathBuilder {
    private final List<Entry> entries = new ArrayList<>();
    private long nextId = 1L;

    PathBuilder root() {
      entries.add(new Entry(id(nextId++), SESSION_ID, null, new RootPayload(SETTINGS), BASE));
      return this;
    }

    long turnStart() {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              SESSION_ID,
              parentId(),
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
              BASE));
      return cur;
    }

    long user(String text) {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              SESSION_ID,
              parentId(),
              new MessagePayload(
                  new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))),
                  null,
                  null),
              BASE));
      return cur;
    }

    /** ASSISTANT 携带成对 (toolName, path) 的工具调用（bash 无 path 也会按此构造）。 */
    long assistant(String... namePathPairs) {
      long cur = nextId++;
      List<AgentMessageContent> contents = new ArrayList<>();
      for (int i = 0; i < namePathPairs.length; i += 2) {
        contents.add(
            new ToolCallMessageContent(
                "call-" + i,
                namePathPairs[i],
                namePathPairs[i],
                "{\"path\":\"" + namePathPairs[i + 1] + "\"}"));
      }
      contents.add(new TextMessageContent("assistant reply"));
      entries.add(
          new Entry(
              id(cur),
              SESSION_ID,
              parentId(),
              new MessagePayload(
                  new AgentMessage(AgentMessageRole.ASSISTANT, contents),
                  new AssistantMessageMetadata(
                      GenerationStopReason.COMPLETE,
                      new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
                      new ModelCost(
                          "USD",
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO)),
                  null),
              BASE));
      return cur;
    }

    /** 为最后一个（assistant）Entry 的每个 call 追加严格 callIndex 前缀的 TOOL result。 */
    PathBuilder toolResults() {
      UUID assistantEntryId = entries.get(entries.size() - 1).id();
      int callIndex = 0;
      for (AgentMessageContent content :
          ((MessagePayload) entries.get(entries.size() - 1).payload()).message().contents()) {
        if (!(content instanceof ToolCallMessageContent call)) {
          continue;
        }
        entries.add(
            new Entry(
                id(nextId++),
                SESSION_ID,
                parentId(),
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.TOOL,
                        List.of(
                            new ToolResultMessageContent(
                                call.toolCallId(),
                                call.toolName(),
                                call.rendererKey(),
                                List.of(new TextMessageContent("ok")),
                                false,
                                "{}"))),
                    null,
                    new ToolResultMetadata(
                        assistantEntryId,
                        call.toolCallId(),
                        callIndex++,
                        ToolResultStatus.SUCCEEDED,
                        false,
                        null)),
                BASE));
      }
      return this;
    }

    long turnEnd(long turnStartEntryId) {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              SESSION_ID,
              parentId(),
              new TurnEndPayload(id(turnStartEntryId), TurnEndOutcome.COMPLETED, false, null, null),
              BASE));
      return cur;
    }

    /** 完成压缩 turn：[TURN_START(COMPACTION), COMPACTION payload, TURN_END]。 */
    long compactionComplete(UUID cutEntryId) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              SESSION_ID,
              parentId(),
              new TurnStartPayload(
                  TurnStartReason.COMPACTION,
                  SETTINGS,
                  OWNER_THREAD_ID,
                  100_000,
                  16_384,
                  new CompactionStart(
                      CompactionPhase.FULL,
                      CompactionTrigger.THRESHOLD,
                      SETTINGS.model(),
                      cutEntryId,
                      null,
                      null)),
              BASE));
      entries.add(
          new Entry(id(nextId++), SESSION_ID, parentId(), new CompactionPayload("summary"), BASE));
      long endId = nextId++;
      entries.add(
          new Entry(
              id(endId),
              SESSION_ID,
              parentId(),
              new TurnEndPayload(id(startId), TurnEndOutcome.COMPLETED, false, null, null),
              BASE));
      return endId;
    }

    EntryPath path() {
      return new EntryPath(List.copyOf(entries));
    }

    private UUID parentId() {
      return entries.get(entries.size() - 1).id();
    }
  }

  private static final UUID SESSION_ID = id(100L);
}
