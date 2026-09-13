package fun.fengwk.kkstudio.platform.cloudfs.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.Objects;
import java.util.Set;

/**
 * Cloud File System 工具包 Contributor。
 *
 * <p>注册 5 个 internal 平台级文件工具：{@code cloud_read}、{@code cloud_write}、{@code cloud_edit}、{@code
 * cloud_find} 与 {@code cloud_grep}。
 */
public final class CloudHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId("cloud");
  public static final String NAME = "Cloud File System";
  public static final String VERSION = "1";

  public static final AgentToolId TOOL_ID_READ = new AgentToolId("cloud.read");
  public static final AgentToolId TOOL_ID_WRITE = new AgentToolId("cloud.write");
  public static final AgentToolId TOOL_ID_EDIT = new AgentToolId("cloud.edit");
  public static final AgentToolId TOOL_ID_FIND = new AgentToolId("cloud.find");
  public static final AgentToolId TOOL_ID_GREP = new AgentToolId("cloud.grep");

  private static final ContributorDescriptor DESCRIPTOR =
      new ContributorDescriptor(ID, NAME, VERSION, Set.of());

  private final Tool readTool;
  private final Tool writeTool;
  private final Tool editTool;
  private final Tool findTool;
  private final Tool grepTool;

  public CloudHarnessContributor(
      Tool readTool, Tool writeTool, Tool editTool, Tool findTool, Tool grepTool) {
    this.readTool = Objects.requireNonNull(readTool, "readTool");
    this.writeTool = Objects.requireNonNull(writeTool, "writeTool");
    this.editTool = Objects.requireNonNull(editTool, "editTool");
    this.findTool = Objects.requireNonNull(findTool, "findTool");
    this.grepTool = Objects.requireNonNull(grepTool, "grepTool");
  }

  @Override
  public ContributorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(HarnessRegistrar registrar) {
    Objects.requireNonNull(registrar, "registrar");

    registrar.registerTool("cloud.read", TOOL_ID_READ, readTool, ToolVisibility.INTERNAL, 0);
    registrar.registerTool("cloud.write", TOOL_ID_WRITE, writeTool, ToolVisibility.INTERNAL, 0);
    registrar.registerTool("cloud.edit", TOOL_ID_EDIT, editTool, ToolVisibility.INTERNAL, 0);
    registrar.registerTool("cloud.find", TOOL_ID_FIND, findTool, ToolVisibility.INTERNAL, 0);
    registrar.registerTool("cloud.grep", TOOL_ID_GREP, grepTool, ToolVisibility.INTERNAL, 0);
  }
}
