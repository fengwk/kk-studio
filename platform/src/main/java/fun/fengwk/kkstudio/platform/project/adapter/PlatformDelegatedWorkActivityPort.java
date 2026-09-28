package fun.fengwk.kkstudio.platform.project.adapter;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.project.port.DelegatedWorkActivityPort;

import java.util.UUID;

/**
 * {@link DelegatedWorkActivityPort} 的 platform 宿主适配：把 Project 的安全静止点判定接到 Harness Runtime 的持久 Thread
 * 生命周期。
 *
 * <p>持久 {@code status} 本身就是永久执行子树的递归投影（IDLE 要求本地无工作且全部永久直接孩子 IDLE），因此非 IDLE 即代表本 Thread
 * 或其委派子树尚未静止，Run 不得收尾。不扫描任何委派任务仓库，也不引入任务状态冗余。
 */
@AllArgsConstructor
@Service
public class PlatformDelegatedWorkActivityPort implements DelegatedWorkActivityPort {

  private final ObjectProvider<HarnessRuntime> runtimes;

  @Override
  public boolean hasPendingDelegatedWork(UUID threadId) {
    return !requireRuntime().getThreadSnapshot(threadId).thread().status().isIdle();
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
