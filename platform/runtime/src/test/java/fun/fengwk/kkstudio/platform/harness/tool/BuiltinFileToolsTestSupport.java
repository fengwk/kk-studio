package fun.fengwk.kkstudio.platform.harness.tool;

import static org.mockito.Mockito.mock;

import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.spi.ResourceReadGateway;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.util.List;

/**
 * 测试用内置文件工具装配：与生产装配同构的 5 个 {@link FileTool}，Resource 读取网关用 Mockito 桩替换。
 *
 * <p>名称与顺序必须与 {@code BuiltinHarnessContributor} 冻结的清单一致，否则 Contributor 构造会拒绝。
 */
public final class BuiltinFileToolsTestSupport {

  private BuiltinFileToolsTestSupport() {}

  public static List<Tool> fileTools() {
    return fileTools(mock(ResourceReadGateway.class));
  }

  public static List<Tool> fileTools(ResourceReadGateway resourceReadGateway) {
    return List.of(
        new FileTool(
            "read",
            FileTool.Operation.READ,
            "read.md",
            EnvironmentCapabilityIds.FS_READ,
            ToolSideEffect.READ_ONLY,
            resourceReadGateway),
        new FileTool(
            "write",
            FileTool.Operation.WRITE,
            "write.md",
            EnvironmentCapabilityIds.FS_WRITE,
            ToolSideEffect.IDEMPOTENT,
            resourceReadGateway),
        new FileTool(
            "edit",
            FileTool.Operation.EDIT,
            "edit.md",
            EnvironmentCapabilityIds.FS_EDIT,
            ToolSideEffect.NON_IDEMPOTENT,
            resourceReadGateway),
        new FileTool(
            "grep",
            FileTool.Operation.GREP,
            "grep.md",
            EnvironmentCapabilityIds.FS_GREP,
            ToolSideEffect.READ_ONLY,
            resourceReadGateway),
        new FileTool(
            "find",
            FileTool.Operation.FIND,
            "find.md",
            EnvironmentCapabilityIds.FS_FIND,
            ToolSideEffect.READ_ONLY,
            resourceReadGateway));
  }
}
