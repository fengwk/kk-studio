package fun.fengwk.kkstudio.project.notification;

import fun.fengwk.kkstudio.share.notification.NotificationCodecs;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import java.util.UUID;

/** Statically declared notification topics for the Project domain. */
public final class ProjectNotifications {

  public static final NotificationTopic<UUID> ISSUE_CHANGED =
      new NotificationTopic<>("project.issue.changed", NotificationCodecs.UUID_CODEC, true);

  public static final NotificationTopic<UUID> WORK_DUE =
      new NotificationTopic<>("project.issue.work.due", NotificationCodecs.UUID_CODEC, true);

  private ProjectNotifications() {}
}
