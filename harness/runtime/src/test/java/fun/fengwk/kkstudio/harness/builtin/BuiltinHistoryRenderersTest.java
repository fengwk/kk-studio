package fun.fengwk.kkstudio.harness.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.goal.GetGoalTool;
import fun.fengwk.kkstudio.harness.builtin.goal.UpdateGoalTool;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.util.List;
import java.util.Optional;

/**
 * {@link BuiltinHistoryRenderers} 及其在 5 个内建工具中绑定的历史动作渲染器单元测试。
 *
 * <p>验证 task、get_goal、update_goal 的最小语义动作提取、省略执行控制参数、 缺失核心字段与畸形输入的确定性安全回退，以及 5
 * 个内建工具均暴露渲染器且为确定性纯函数。Goal 正文由用户维护，因此没有 create_goal 渲染器。
 */
class BuiltinHistoryRenderersTest {

  private static ToolHistoryRenderRequest request(String toolName, String argumentsJson) {
    return new ToolHistoryRenderRequest(new ToolCall("call-1", toolName, argumentsJson), null);
  }

  private static ToolHistoryRenderRequest malformedRequest(String toolName, String argumentsJson) {
    ToolCall call = mock(ToolCall.class);
    when(call.id()).thenReturn("call-1");
    when(call.toolName()).thenReturn(toolName);
    when(call.argumentsJson()).thenReturn(argumentsJson);
    return new ToolHistoryRenderRequest(call, null);
  }

  /**
   * 验证 task 历史动作渲染器提取 subagent_type 渲染为 "delegate to subagent " + type， 省略
   * maxTurns、session_id、prompt 等执行细节，且在 subagent_type 缺失、空白、非文本或 JSON 畸形时安全返回 Optional.empty()。
   */
  @Test
  void taskRendersSubagentTypeAndOmitsExecutionDetails() {
    ToolHistoryRenderer renderer = BuiltinHistoryRenderers.task();

    // 正常渲染
    assertEquals(
        Optional.of("delegate to subagent explorer"),
        renderer.render(request("task", "{\"subagent_type\":\"explorer\"}")));

    // 省略 prompt、maxTurns、session_id 等执行细节
    assertEquals(
        Optional.of("delegate to subagent coder"),
        renderer.render(
            request(
                "task",
                "{\"subagent_type\":\"coder\",\"prompt\":\"fix bug\",\"maxTurns\":5,\"session_id\":\"00000000-0000-0000-0000-000000000000\"}")));

    // subagent_type 缺失、空白、非文本
    assertEquals(Optional.empty(), renderer.render(request("task", "{}")));
    assertEquals(Optional.empty(), renderer.render(request("task", "{\"subagent_type\":\"   \"}")));
    assertEquals(Optional.empty(), renderer.render(request("task", "{\"subagent_type\":false}")));

    // 畸形 JSON 与非对象
    assertEquals(Optional.empty(), renderer.render(malformedRequest("task", "{bad json")));
    assertEquals(Optional.empty(), renderer.render(malformedRequest("task", "\"just-string\"")));
  }

  /**
   * 验证 getGoal 历史动作渲染器恒定返回 "read the current goal"，与 arguments 内容无关 （包含空参数、多余参数、非对象或畸形 JSON 均恒定返回）。
   */
  @Test
  void getGoalAlwaysReturnsConstantActionRegardlessOfArguments() {
    ToolHistoryRenderer renderer = BuiltinHistoryRenderers.getGoal();

    // 空参数
    assertEquals(Optional.of("read the current goal"), renderer.render(request("get_goal", "{}")));

    // 包含任意多余参数
    assertEquals(
        Optional.of("read the current goal"),
        renderer.render(request("get_goal", "{\"unrelated\":\"value\"}")));

    // 畸形 JSON
    assertEquals(
        Optional.of("read the current goal"),
        renderer.render(malformedRequest("get_goal", "{malformed json")));

    // 非对象
    assertEquals(
        Optional.of("read the current goal"),
        renderer.render(malformedRequest("get_goal", "[1, 2, 3]")));
  }

  /**
   * 验证 updateGoal 历史动作渲染器提取 status 渲染为 "mark the goal " + status， 若 reason 存在且非空则渲染为 "mark the goal
   * " + status + ": " + reason； 在 status 缺失、空白或 JSON 畸形时安全返回 Optional.empty()。
   */
  @Test
  void updateGoalRendersStatusAndOptionalReason() {
    ToolHistoryRenderer renderer = BuiltinHistoryRenderers.updateGoal();

    // 仅 status
    assertEquals(
        Optional.of("mark the goal completed"),
        renderer.render(request("update_goal", "{\"status\":\"completed\"}")));

    // status + reason
    assertEquals(
        Optional.of("mark the goal blocked: waiting for review"),
        renderer.render(
            request("update_goal", "{\"status\":\"blocked\",\"reason\":\"waiting for review\"}")));

    // reason 为空白或非文本时忽略 reason
    assertEquals(
        Optional.of("mark the goal completed"),
        renderer.render(request("update_goal", "{\"status\":\"completed\",\"reason\":\"   \"}")));
    assertEquals(
        Optional.of("mark the goal completed"),
        renderer.render(request("update_goal", "{\"status\":\"completed\",\"reason\":123}")));
    assertEquals(
        Optional.of("mark the goal completed"),
        renderer.render(request("update_goal", "{\"status\":\"completed\",\"reason\":null}")));

    // status 缺失（即便有 reason）
    assertEquals(Optional.empty(), renderer.render(request("update_goal", "{}")));
    assertEquals(
        Optional.empty(), renderer.render(request("update_goal", "{\"reason\":\"some reason\"}")));

    // status 为空白或非文本
    assertEquals(
        Optional.empty(),
        renderer.render(request("update_goal", "{\"status\":\"   \",\"reason\":\"ok\"}")));
    assertEquals(
        Optional.empty(),
        renderer.render(request("update_goal", "{\"status\":true,\"reason\":\"ok\"}")));

    // 畸形 JSON 与非对象
    assertEquals(Optional.empty(), renderer.render(malformedRequest("update_goal", "{broken")));
    assertEquals(Optional.empty(), renderer.render(malformedRequest("update_goal", "[100]")));
  }

  /**
   * 验证全部 5 个内建工具（EnvironmentCapabilityTool、ReadTool、GetGoalTool、UpdateGoalTool、TaskTool） 均通过
   * historyRenderer() 暴露渲染器，且该渲染器为确定性纯函数：同一合法输入连续两次调用返回相同非空结果，同一非法输入连续两次调用返回相同 empty。
   */
  @Test
  void allBuiltinToolsExposeHistoryRendererAsDeterministicPureFunction() {
    // 构造 1: EnvironmentCapabilityTool
    EnvironmentCapabilityDescriptor fsReadDesc =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
    ToolDescriptor envToolDesc =
        new ToolDescriptor(
            "read",
            "read file",
            "read",
            fsReadDesc.inputSchema(),
            ToolSideEffect.READ_ONLY,
            fsReadDesc.defaultTimeout());
    Tool envTool = new EnvironmentCapabilityTool(envToolDesc, fsReadDesc);

    // 构造 2: ReadTool
    Tool readTool = new ReadTool((request, listener) -> null);

    // 构造 3: GetGoalTool
    Tool getGoalTool = new GetGoalTool();

    // 构造 4: UpdateGoalTool
    Tool updateGoalTool = new UpdateGoalTool();

    // 构造 5: TaskTool（依赖轻量接口，使用 mock 构造）
    Tool taskTool = new TaskTool(mock(SubagentRunner.class));

    List<Tool> tools = List.of(envTool, readTool, getGoalTool, updateGoalTool, taskTool);

    // 断言所有 5 个工具均暴露 historyRenderer
    for (Tool tool : tools) {
      assertTrue(
          tool.historyRenderer().isPresent(),
          "Tool " + tool.descriptor().name() + " must expose a historyRenderer");
    }

    // 验证各工具暴露的渲染器为确定性纯函数：连续两次调用产生相同输出
    ToolHistoryRenderer envRenderer = envTool.historyRenderer().orElseThrow();
    ToolHistoryRenderRequest envReq = request("fs_read", "{\"path\":\"src/Test.java\"}");
    Optional<String> envFirst = envRenderer.render(envReq);
    Optional<String> envSecond = envRenderer.render(envReq);
    assertEquals(Optional.of("read src/Test.java"), envFirst);
    assertEquals(envFirst, envSecond);

    ToolHistoryRenderer getGoalRenderer = getGoalTool.historyRenderer().orElseThrow();
    ToolHistoryRenderRequest getGoalReq = request("get_goal", "{}");
    Optional<String> ggFirst = getGoalRenderer.render(getGoalReq);
    Optional<String> ggSecond = getGoalRenderer.render(getGoalReq);
    assertEquals(Optional.of("read the current goal"), ggFirst);
    assertEquals(ggFirst, ggSecond);

    ToolHistoryRenderer updateGoalRenderer = updateGoalTool.historyRenderer().orElseThrow();
    ToolHistoryRenderRequest updateGoalReq =
        request("update_goal", "{\"status\":\"completed\",\"reason\":\"done\"}");
    Optional<String> ugFirst = updateGoalRenderer.render(updateGoalReq);
    Optional<String> ugSecond = updateGoalRenderer.render(updateGoalReq);
    assertEquals(Optional.of("mark the goal completed: done"), ugFirst);
    assertEquals(ugFirst, ugSecond);

    ToolHistoryRenderer readRenderer = readTool.historyRenderer().orElseThrow();
    ToolHistoryRenderRequest readReq = request("read", "{\"path\":\"src/Test.java\"}");
    Optional<String> readFirst = readRenderer.render(readReq);
    Optional<String> readSecond = readRenderer.render(readReq);
    assertEquals(Optional.of("read src/Test.java"), readFirst);
    assertEquals(readFirst, readSecond);

    ToolHistoryRenderer taskRenderer = taskTool.historyRenderer().orElseThrow();
    ToolHistoryRenderRequest taskReq = request("task", "{\"subagent_type\":\"coder\"}");
    Optional<String> taskFirst = taskRenderer.render(taskReq);
    Optional<String> taskSecond = taskRenderer.render(taskReq);
    assertEquals(Optional.of("delegate to subagent coder"), taskFirst);
    assertEquals(taskFirst, taskSecond);

    // 验证安全回退的确定性：对非 get_goal 工具连续调用无效请求两次，均稳定返回 Optional.empty()
    ToolHistoryRenderRequest emptyReq = request("dummy", "{}");
    List<ToolHistoryRenderer> fallibleRenderers =
        List.of(envRenderer, readRenderer, updateGoalRenderer, taskRenderer);
    for (ToolHistoryRenderer renderer : fallibleRenderers) {
      Optional<String> f1 = renderer.render(emptyReq);
      Optional<String> f2 = renderer.render(emptyReq);
      assertEquals(Optional.empty(), f1);
      assertEquals(f1, f2);
    }
  }
}
