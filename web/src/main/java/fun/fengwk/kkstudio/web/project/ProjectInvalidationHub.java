package fun.fengwk.kkstudio.web.project;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Web/Project 模块内的 Project 变更信号集线器。
 *
 * <p>在项目或 Issue 状态发生变更（创建、更新、状态流转、归档、删除、输入追加）时发布事件，作为 WebSocket 浏览器事件层的唯一项目失效信号源。
 */
@AllArgsConstructor
@Component
public class ProjectInvalidationHub {

  private final ApplicationEventPublisher eventPublisher;

  public void publishChange(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");
    eventPublisher.publishEvent(new ProjectChangedEvent(this, projectId));
  }

  @Getter
  public static class ProjectChangedEvent extends ApplicationEvent {

    private final UUID projectId;

    public ProjectChangedEvent(Object source, UUID projectId) {
      super(source);
      this.projectId = Objects.requireNonNull(projectId, "projectId");
    }
  }
}
