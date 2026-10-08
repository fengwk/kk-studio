package fun.fengwk.kkstudio.platform.notification;

import fun.fengwk.kkstudio.share.notification.NotificationCodecs;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import java.util.UUID;

/** Platform 子域拥有的固定通知 Topic 声明。 */
public final class PlatformNotifications {

  public static final NotificationTopic<Long> SETTINGS_CHANGED =
      new NotificationTopic<>("system.settings.changed", NotificationCodecs.VERSION, true);

  public static final NotificationTopic<String> SKILL_PACKAGE_CHANGED =
      new NotificationTopic<>("skill.package.changed", NotificationCodecs.NAME, true);

  public static final NotificationTopic<UUID> ENVIRONMENT_CHANGED =
      new NotificationTopic<>("environment.changed", NotificationCodecs.UUID_CODEC, true);

  private PlatformNotifications() {}
}
