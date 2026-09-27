package fun.fengwk.kkstudio.platform.project.adapter;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskActivity;
import fun.fengwk.kkstudio.project.port.DelegatedWorkActivityPort;

import java.util.UUID;

/** 将平台持久委派任务活动查询接入 Project 的安全静止点判定。 */
@AllArgsConstructor
@Service
public class PlatformDelegatedWorkActivityPort implements DelegatedWorkActivityPort {

  private final SubagentTaskActivity activity;

  @Override
  public boolean hasPendingDelegatedWork(UUID threadId) {
    return activity.hasPendingDelegatedWork(threadId);
  }
}
