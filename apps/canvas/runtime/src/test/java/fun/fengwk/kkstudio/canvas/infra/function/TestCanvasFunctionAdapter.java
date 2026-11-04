package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;

import java.util.List;
import java.util.UUID;

/** 目录冻结与围栏测试用的最小 adapter：只声明给定定义并统计 preflight，submit/execute 绝不被真实调用。 */
final class TestCanvasFunctionAdapter implements CanvasFunctionAdapter {

  private final List<CanvasFunctionDefinition> definitions;
  int preflightCalls;

  TestCanvasFunctionAdapter(CanvasFunctionDefinition... definitions) {
    this.definitions = List.of(definitions);
  }

  @Override
  public List<CanvasFunctionDefinition> functions() {
    return definitions;
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public String unavailableReason() {
    return null;
  }

  @Override
  public void preflight(CanvasFunctionFrozenRun run) {
    preflightCalls++;
  }

  @Override
  public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    throw new UnsupportedOperationException("test adapter does not submit");
  }

  @Override
  public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
    throw new UnsupportedOperationException("test adapter does not execute");
  }
}
