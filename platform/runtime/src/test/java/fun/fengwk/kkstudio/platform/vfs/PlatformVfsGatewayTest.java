package fun.fengwk.kkstudio.platform.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.harness.read.PlatformReadException;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader.ResourceRead;
import fun.fengwk.kkstudio.platform.harness.read.PlatformSkillContentReader;
import fun.fengwk.kkstudio.platform.harness.read.PlatformSkillContentReader.PackageSummary;
import fun.fengwk.kkstudio.platform.storage.error.SessionFileConflictException;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link PlatformVfsGateway} 的命名空间、只读前缀与访问范围契约。 */
class PlatformVfsGatewayTest {

  private static final UUID ROOT_SESSION = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID OTHER_SESSION = UUID.fromString("99999999-8888-7777-6666-555555555555");
  private static final UUID BLOB_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
  private static final UUID THREAD_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID ROOT_THREAD = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

  /** 子 Agent 线程：独立 Session，但执行根仍是当前对话的根线程。 */
  private static final UUID SUBAGENT_THREAD =
      UUID.fromString("cccccccc-dddd-eeee-ffff-000000000000");

  private static final EnvironmentCapabilityDescriptor FS_READ =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
  private static final EnvironmentCapabilityDescriptor FS_WRITE =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_WRITE);
  private static final EnvironmentCapabilityDescriptor FS_EDIT =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT);
  private static final EnvironmentCapabilityDescriptor FS_FIND =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_FIND);
  private static final EnvironmentCapabilityDescriptor FS_GREP =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_GREP);

  private PlatformSkillContentReader skillReader;
  private PlatformResourceContentReader resourceReader;
  private SessionFileStore fileStore;
  private StorageBlobManager blobManager;
  private ThreadMembershipValidator threadMembership;
  private PlatformVfsGateway gateway;

  @BeforeEach
  void setUp() {
    skillReader = mock(PlatformSkillContentReader.class);
    resourceReader = mock(PlatformResourceContentReader.class);
    fileStore = mock(SessionFileStore.class);
    blobManager = mock(StorageBlobManager.class);
    threadMembership = mock(ThreadMembershipValidator.class);
    gateway =
        new PlatformVfsGateway(
            skillReader, resourceReader, fileStore, blobManager, threadMembership);
  }

  /** 只读命名空间的写入与编辑在任何存储调用之前拒绝，文案与 PLAN 3.1 一致。 */
  @Test
  void writesToReadOnlyNamespacesFailBeforeAnyStorageSideEffect() throws Exception {
    String skillPath = "vfs:/skills/dev-tools/review/SKILL.md";
    ToolResult write =
        gateway.write(
            request(FS_WRITE, "write", "{\"path\":\"" + skillPath + "\",\"content\":\"x\"}"),
            partial -> {});
    assertTrue(write.error());
    assertEquals(
        "Cannot write "
            + skillPath
            + ": vfs:/skills is read-only. The tool was not executed. Write under vfs:/sessions/"
            + ROOT_SESSION
            + "/ instead.",
        text(write));

    String resourcePath = "vfs:/resources/" + BLOB_ID;
    ToolResult edit =
        gateway.edit(
            request(
                FS_EDIT,
                "edit",
                "{\"path\":\"" + resourcePath + "\",\"old_string\":\"a\",\"new_string\":\"b\"}"),
            partial -> {});
    assertTrue(edit.error());
    assertTrue(text(edit).contains("vfs:/resources is read-only"), text(edit));
    assertTrue(text(edit).contains("The tool was not executed."), text(edit));
    verify(fileStore, never()).write(any(), any(), any(), any());
  }

  /** 其他会话 ID 与不存在的路径同样返回「不存在」，不暴露该会话是否存在。 */
  @Test
  void otherSessionsAreIndistinguishableFromMissingPaths() throws Exception {
    ToolResult result =
        gateway.read(
            request(FS_READ, "read", "{\"path\":\"vfs:/sessions/" + OTHER_SESSION + "/a.txt\"}"),
            partial -> {});
    assertTrue(result.error());
    assertTrue(text(result).contains("No such file or directory"), text(result));
    verify(fileStore, never()).read(any(), any());
  }

  /** read vfs:/sessions/ 只列出当前对话这一个目录，并标出可写。 */
  @Test
  void sessionsRootListsOnlyTheCurrentConversation() throws Exception {
    ToolResult result =
        gateway.read(request(FS_READ, "read", "{\"path\":\"vfs:/sessions/\"}"), partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains(ROOT_SESSION + "/ (read-write)"), text(result));
    assertFalse(text(result).contains(OTHER_SESSION.toString()), text(result));
  }

  /** threads/<线程 ID> 只接受执行根等于当前执行根的线程；不属于本对话时按不存在处理。 */
  @Test
  void threadScopedPathsRequireAThreadOfTheCurrentConversation() throws Exception {
    String path = "vfs:/sessions/" + ROOT_SESSION + "/threads/" + THREAD_ID + "/MEMORY.md";
    when(threadMembership.isThreadInExecutionRoot(ROOT_THREAD, THREAD_ID)).thenReturn(false);
    ToolResult rejected =
        gateway.read(request(FS_READ, "read", "{\"path\":\"" + path + "\"}"), p -> {});
    assertTrue(rejected.error());
    assertTrue(text(rejected).contains("No such file or directory"), text(rejected));

    when(threadMembership.isThreadInExecutionRoot(ROOT_THREAD, THREAD_ID)).thenReturn(true);
    when(fileStore.find(ROOT_SESSION, "threads/" + THREAD_ID + "/MEMORY.md"))
        .thenReturn(Optional.empty());
    when(fileStore.list(eq(ROOT_SESSION), eq("threads/" + THREAD_ID + "/MEMORY.md/")))
        .thenReturn(List.of());
    ToolResult allowed =
        gateway.read(request(FS_READ, "read", "{\"path\":\"" + path + "\"}"), p -> {});
    assertTrue(allowed.error());
    assertTrue(text(allowed).contains("No such file or directory"), text(allowed));
  }

  /** 会话文件读取复用共享文本窗口，路径显示为 vfs: 坐标。 */
  @Test
  void sessionFileReadReturnsTheSharedTextWindow() throws Exception {
    when(fileStore.find(ROOT_SESSION, "notes.txt"))
        .thenReturn(
            Optional.of(new SessionFile(ROOT_SESSION, "notes.txt", BLOB_ID, 3, Instant.now())));
    when(fileStore.read(ROOT_SESSION, "notes.txt"))
        .thenReturn(Optional.of("hello\n".getBytes(StandardCharsets.UTF_8)));

    ToolResult result =
        gateway.read(
            request(FS_READ, "read", "{\"path\":\"vfs:/sessions/" + ROOT_SESSION + "/notes.txt\"}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("1|hello"), text(result));
    assertTrue(text(result).contains("vfs:/sessions/" + ROOT_SESSION + "/notes.txt"), text(result));
  }

  /** 目录读取返回直属子目录与文件的大小/revision。 */
  @Test
  void sessionDirectoryListingShowsChildrenAndRevisions() throws Exception {
    when(fileStore.list(ROOT_SESSION, ""))
        .thenReturn(
            List.of(
                new SessionFile(ROOT_SESSION, "MEMORY.md", BLOB_ID, 2, Instant.now()),
                new SessionFile(ROOT_SESSION, "tmp/scratch.txt", BLOB_ID, 1, Instant.now())));

    ToolResult result =
        gateway.read(
            request(FS_READ, "read", "{\"path\":\"vfs:/sessions/" + ROOT_SESSION + "\"}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("MEMORY.md"), text(result));
    assertTrue(text(result).contains("tmp/"), text(result));
    assertTrue(text(result).contains("revision 2"), text(result));
  }

  /** 资源读取委托给会话授权的资源读取器，并使用执行根会话。 */
  @Test
  void resourceReadDelegatesToTheSessionResourceReader() throws Exception {
    when(resourceReader.readResource(
            eq(ROOT_SESSION), any(), eq(BLOB_ID), eq(null), eq(null), eq(null), any(), any()))
        .thenReturn(new ResourceRead.Text("resource text"));

    ToolResult result =
        gateway.read(
            request(FS_READ, "read", "{\"path\":\"vfs:/resources/" + BLOB_ID + "\"}"),
            partial -> {});
    assertFalse(result.error());
    assertEquals("resource text", text(result));
  }

  /** 发布技能读取委托给现有技能读取器。 */
  @Test
  void skillReadDelegatesToThePublishedSkillReader() throws Exception {
    when(skillReader.readSkillFile("dev-tools", "review", "SKILL.md"))
        .thenReturn("skill\n".getBytes(StandardCharsets.UTF_8));
    ToolResult result =
        gateway.read(
            request(FS_READ, "read", "{\"path\":\"vfs:/skills/dev-tools/review/SKILL.md\"}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("1|skill"), text(result));
  }

  /** 技能读取与其它文本读取共用 offset/limit 窗口：只返回请求行段，读取器只被调用一次。 */
  @Test
  void skillReadAppliesOffsetAndLimitWindow() throws Exception {
    when(skillReader.readSkillFile("dev-tools", "review", "SKILL.md"))
        .thenReturn("a\nb\nc\n".getBytes(StandardCharsets.UTF_8));

    ToolResult result =
        gateway.read(
            request(
                FS_READ,
                "read",
                "{\"path\":\"vfs:/skills/dev-tools/review/SKILL.md\",\"offset\":2,\"limit\":1}"),
            partial -> {});

    assertFalse(result.error(), text(result));
    assertTrue(text(result).contains("2|b"), text(result));
    assertFalse(text(result).contains("1|a"), text(result));
    assertFalse(text(result).contains("3|c"), text(result));
    verify(skillReader).readSkillFile("dev-tools", "review", "SKILL.md");
  }

  /** edit 用读到的 revision 做条件更新：并发冲突是确定失败，不是不确定执行。 */
  @Test
  void editConflictIsReportedAsFailedNotUncertain() throws Exception {
    when(fileStore.find(ROOT_SESSION, "notes.txt"))
        .thenReturn(
            Optional.of(new SessionFile(ROOT_SESSION, "notes.txt", BLOB_ID, 3, Instant.now())));
    when(fileStore.read(ROOT_SESSION, "notes.txt"))
        .thenReturn(Optional.of("hello\n".getBytes(StandardCharsets.UTF_8)));
    when(fileStore.write(eq(ROOT_SESSION), eq("notes.txt"), any(), eq(3)))
        .thenThrow(new SessionFileConflictException("revision conflict"));

    ToolResult result =
        gateway.edit(
            request(
                FS_EDIT,
                "edit",
                "{\"path\":\"vfs:/sessions/"
                    + ROOT_SESSION
                    + "/notes.txt\",\"old_string\":\"hello\",\"new_string\":\"bye\"}"),
            partial -> {});
    assertTrue(result.error());
    assertTrue(text(result).contains("revision conflict"), text(result));
    assertTrue(text(result).contains("The tool ran but failed"), text(result));
  }

  /** 存储限额错误原样呈现为模型可读的失败，不返回堆栈。 */
  @Test
  void storageLimitFailuresSurfaceAsToolErrors() throws Exception {
    when(fileStore.write(eq(ROOT_SESSION), eq("big.txt"), any(), any()))
        .thenThrow(new IllegalStateException("session file limit reached: 2000 files"));
    ToolResult result =
        gateway.write(
            request(
                FS_WRITE,
                "write",
                "{\"path\":\"vfs:/sessions/" + ROOT_SESSION + "/big.txt\",\"content\":\"x\"}"),
            partial -> {});
    assertTrue(result.error());
    assertTrue(text(result).contains("session file limit reached: 2000 files"), text(result));
  }

  /** find 只匹配路径：把会话文件路径按 glob 过滤后返回。 */
  @Test
  void findMatchesPathsOnly() throws Exception {
    when(fileStore.list(ROOT_SESSION, ""))
        .thenReturn(
            List.of(
                new SessionFile(ROOT_SESSION, "src/Main.java", BLOB_ID, 1, Instant.now()),
                new SessionFile(ROOT_SESSION, "README.md", BLOB_ID, 1, Instant.now())));

    ToolResult result =
        gateway.find(
            request(
                FS_FIND,
                "find",
                "{\"path\":\"vfs:/sessions/" + ROOT_SESSION + "\",\"pattern\":\"**/*.java\"}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("src/Main.java"), text(result));
    assertFalse(text(result).contains("README.md"), text(result));
  }

  /** grep 预算命中时明确说明检索未完成，而不是把部分结果当作完整结果。 */
  @Test
  void grepReportsIncompleteWhenTheBudgetIsHit() throws Exception {
    when(fileStore.list(ROOT_SESSION, "")).thenReturn(List.of());
    ToolResult result =
        gateway.grep(
            request(
                FS_GREP,
                "grep",
                "{\"path\":\"vfs:/sessions/" + ROOT_SESSION + "/\",\"pattern\":\"needle\"}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("No matches for needle"), text(result));
  }

  @Test
  void requestsWithoutExecutionContextFailDeterministically() throws Exception {
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            descriptor(FS_READ, "read"),
            new ToolCall("c1", "read", "{\"path\":\"vfs:/sessions/\"}"),
            Duration.ZERO);
    ToolResult result = gateway.read(request, partial -> {});
    assertTrue(result.error());
    assertTrue(text(result).contains("execution context"), text(result));
    verify(fileStore, never()).list(any(), any());
  }

  /** 子 Agent 线程在独立 Session 中运行，但执行根相同，因此根会话目录下的 threads/<子线程>/ 可用。 */
  @Test
  void subagentThreadsShareTheExecutionRootAndStayAccessible() throws Exception {
    String path = "vfs:/sessions/" + ROOT_SESSION + "/threads/" + SUBAGENT_THREAD + "/MEMORY.md";
    when(threadMembership.isThreadInExecutionRoot(ROOT_THREAD, SUBAGENT_THREAD)).thenReturn(true);
    when(fileStore.find(ROOT_SESSION, "threads/" + SUBAGENT_THREAD + "/MEMORY.md"))
        .thenReturn(
            Optional.of(
                new SessionFile(
                    ROOT_SESSION,
                    "threads/" + SUBAGENT_THREAD + "/MEMORY.md",
                    BLOB_ID,
                    1,
                    Instant.now())));
    when(fileStore.read(ROOT_SESSION, "threads/" + SUBAGENT_THREAD + "/MEMORY.md"))
        .thenReturn(Optional.of("memory\n".getBytes(StandardCharsets.UTF_8)));

    ToolResult result =
        gateway.read(request(FS_READ, "read", "{\"path\":\"" + path + "\"}"), partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("1|memory"), text(result));
  }

  /** vfs:/skills 的只读目录层级：包、技能都来自服务端清单，并标注 read-only。 */
  @Test
  void skillsDirectoriesListPublishedPackagesAndSkillsAsReadOnly() throws Exception {
    when(skillReader.listPackages())
        .thenReturn(
            List.of(
                new PackageSummary(
                    "dev-tools",
                    "development helpers",
                    List.of(new SkillManifestEntry("review", "review code"))),
                new PackageSummary("empty-tools", null, List.of())));

    ToolResult root =
        gateway.read(request(FS_READ, "read", "{\"path\":\"vfs:/skills/\"}"), partial -> {});
    assertFalse(root.error());
    assertTrue(text(root).contains("dev-tools/ (read-only)"), text(root));
    assertTrue(text(root).contains("empty-tools/ (read-only)"), text(root));
    assertTrue(text(root).contains("development helpers"), text(root));

    when(skillReader.listSkills("dev-tools"))
        .thenReturn(List.of(new SkillManifestEntry("review", "review code")));
    ToolResult packageDir =
        gateway.read(
            request(FS_READ, "read", "{\"path\":\"vfs:/skills/dev-tools/\"}"), partial -> {});
    assertFalse(packageDir.error());
    assertTrue(text(packageDir).contains("review/ (read-only)"), text(packageDir));
    verify(skillReader).listSkills("dev-tools");
  }

  /** 技能目录没有服务端归档索引：明确说明不可列出文件，而不是返回伪造清单。 */
  @Test
  void skillDirectoryExplainsThatFileListingIsUnavailableWithoutAnArchiveIndex() throws Exception {
    ToolResult result =
        gateway.read(
            request(FS_READ, "read", "{\"path\":\"vfs:/skills/dev-tools/review/\"}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("kind: directory (read-only)"), text(result));
    assertTrue(text(result).contains("no server-side archive index"), text(result));
    verify(skillReader, never()).readSkillFile(any(), any(), any());
  }

  /** 未知包的技能查询给出确定性错误，不抛异常。 */
  @Test
  void unknownSkillPackageIsReportedAsAnError() throws Exception {
    when(skillReader.listSkills("nope"))
        .thenThrow(new PlatformReadException("skill package not found: nope"));
    ToolResult result =
        gateway.read(request(FS_READ, "read", "{\"path\":\"vfs:/skills/nope/\"}"), partial -> {});
    assertTrue(result.error());
    assertTrue(text(result).contains("skill package not found: nope"), text(result));
  }

  /** find 请求超过硬上限时按上限截断，并在结果中说明实际使用的上限。 */
  @Test
  void findCapsTheRequestedLimitAtTheHardMaximum() throws Exception {
    when(fileStore.list(ROOT_SESSION, ""))
        .thenReturn(List.of(new SessionFile(ROOT_SESSION, "a.txt", BLOB_ID, 1, Instant.now())));
    ToolResult result =
        gateway.find(
            request(
                FS_FIND,
                "find",
                "{\"path\":\"vfs:/sessions/"
                    + ROOT_SESSION
                    + "\",\"pattern\":\"**/*.txt\",\"limit\":999999}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(
        text(result).contains("Requested limit 999999 exceeds the maximum of 5000"), text(result));
    assertTrue(text(result).contains("5000"), text(result));
  }

  /** grep 同时受结果数与输出字节上限约束：超出字节上限时截断并说明省略了多少行。 */
  @Test
  void grepTruncatesTheResponseAtTheByteLimit() throws Exception {
    StringBuilder content = new StringBuilder();
    for (int line = 0; line < 1000; line++) {
      content.append("needle ").append("x".repeat(400)).append('\n');
    }
    when(fileStore.list(ROOT_SESSION, ""))
        .thenReturn(List.of(new SessionFile(ROOT_SESSION, "big.txt", BLOB_ID, 1, Instant.now())));
    when(fileStore.read(ROOT_SESSION, "big.txt"))
        .thenReturn(Optional.of(content.toString().getBytes(StandardCharsets.UTF_8)));

    ToolResult result =
        gateway.grep(
            request(
                FS_GREP,
                "grep",
                "{\"path\":\"vfs:/sessions/"
                    + ROOT_SESSION
                    + "/\",\"pattern\":\"needle\",\"limit\":1000}"),
            partial -> {});
    assertFalse(result.error());
    assertTrue(text(result).contains("[TRUNCATED:"), text(result));
    assertTrue(
        text(result).getBytes(StandardCharsets.UTF_8).length
            <= PlatformVfsGateway.GREP_MAX_OUTPUT_BYTES + 1024,
        "grep response must stay near the output byte cap");
  }

  /** 审批预览由 VFS 生成：diff 展示真实变化，expectedFile 固定当前内容快照。 */
  @Test
  void editPreviewProducesADiffAndSnapshot() throws Exception {
    when(fileStore.find(ROOT_SESSION, "notes.txt"))
        .thenReturn(
            Optional.of(new SessionFile(ROOT_SESSION, "notes.txt", BLOB_ID, 3, Instant.now())));
    when(fileStore.read(ROOT_SESSION, "notes.txt"))
        .thenReturn(Optional.of("hello\nworld\n".getBytes(StandardCharsets.UTF_8)));

    ToolInvocationRequest invocation =
        invocationRequest(
            FS_EDIT,
            "edit",
            "{\"path\":\"vfs:/sessions/"
                + ROOT_SESSION
                + "/notes.txt\",\"old_string\":\"hello\",\"new_string\":\"bye\"}");
    ToolEditReview review = gateway.prepare(invocation, ROOT_SESSION, ROOT_THREAD);
    assertTrue(review.diff().contains("-hello"), review.diff());
    assertTrue(review.diff().contains("+bye"), review.diff());
    assertEquals("vfs:/sessions/" + ROOT_SESSION + "/notes.txt", review.expectedFile().path());
    assertEquals(
        sha256("hello\nworld\n".getBytes(StandardCharsets.UTF_8)), review.expectedFile().sha256());
  }

  /** 审批后文件发生变化时拒绝执行，要求重新审查。 */
  @Test
  void editPreviewSnapshotMismatchIsRejected() throws Exception {
    when(fileStore.read(ROOT_SESSION, "notes.txt"))
        .thenReturn(Optional.of("changed\n".getBytes(StandardCharsets.UTF_8)));
    var expectedFile =
        new ExpectedFile(
            "vfs:/sessions/" + ROOT_SESSION + "/notes.txt",
            sha256("original\n".getBytes(StandardCharsets.UTF_8)));
    var rejection =
        assertThrows(
            VfsEditRejectedException.class,
            () -> gateway.verifySnapshot(ROOT_SESSION, ROOT_THREAD, expectedFile));
    assertTrue(
        rejection.getMessage().contains("changed after this edit was approved"),
        rejection.getMessage());
    assertTrue(
        rejection.getMessage().contains("The tool was not executed."), rejection.getMessage());
  }

  private static ToolInvocationRequest invocationRequest(
      EnvironmentCapabilityDescriptor descriptor, String toolName, String argumentsJson) {
    return new ToolInvocationRequest(
        new ToolCall("c1", toolName, argumentsJson),
        new ToolBinding(
            new AgentToolDefinition(descriptor(descriptor, toolName), ToolVisibility.SELECTABLE),
            new ContributorBinding("test", toolName, List.of()),
            EnvironmentSupport.NONE,
            null,
            null));
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ToolExecutionRequest request(
      EnvironmentCapabilityDescriptor descriptor, String toolName, String argumentsJson) {
    BranchView branch = mock(BranchView.class);
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ROOT_SESSION,
            ROOT_THREAD,
            ROOT_SESSION,
            UUID.randomUUID(),
            Instant.now(),
            TurnMediaCapabilities.NONE,
            branch,
            Optional.empty());
    return new ToolExecutionRequest(
        descriptor(descriptor, toolName),
        new ToolCall("c1", toolName, argumentsJson),
        Duration.ofSeconds(15),
        context);
  }

  private static ToolDescriptor descriptor(
      EnvironmentCapabilityDescriptor capability, String toolName) {
    return new ToolDescriptor(
        toolName,
        toolName + " description",
        toolName,
        capability.inputSchema(),
        ToolSideEffect.READ_ONLY,
        capability.defaultTimeout());
  }

  private static String text(ToolResult result) {
    return ((TextResultContent) result.contents().get(0)).text();
  }
}
