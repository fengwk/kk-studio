package fun.fengwk.kkstudio.web;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.UUID;

/** Test-only account fixture shared by PostgreSQL-backed web tests. */
public final class WebAccountTestFixture {
  public static final String ADMIN_USERNAME = "kkstudio";
  public static final String ADMIN_PASSWORD = "kkstudio";

  private static final String ADMIN_ID = "00000000-0000-0000-0000-000000000001";
  private static final String ADMIN_PASSWORD_HASH =
      "{pbkdf2}473d0449576a25f739f57765f5b5d4ddd346ec503ad602aeab8da49810d4c61f460d54a08e070b1552a60ccab0285830";

  private WebAccountTestFixture() {}

  static void seed(DataSource dataSource) {
    new JdbcTemplate(dataSource)
        .update(
            "insert into studio_user(id, username, password_hash, role) values (?, ?, ?, 'ADMIN')",
            UUID.fromString(ADMIN_ID),
            ADMIN_USERNAME,
            ADMIN_PASSWORD_HASH);
  }
}
