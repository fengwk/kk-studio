# Environment one-line install backend

## Commit

Branch: `worktree/environment-one-line-backend`. SHA is reported by `git rev-parse HEAD` after this commit.

## Validation

JDK 21:

`mvn -pl platform,web -am test -Dtest=EnvironmentInstallCodesTest,EnvironmentInstallCommandsTest,EnvironmentServiceImplTest,StudioEnvironmentControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.check.skip=true -Dcheckstyle.skip=true`

Result: BUILD SUCCESS.

- platform: Tests run 42, failures 0, errors 0, skipped 1
- web: Tests run 17, failures 0, errors 0, skipped 0

JaCoCo line coverage from `platform/target/site/jacoco/jacoco.xml`:

- `EnvironmentInstallCommands`: 48/53 = 90.6%
- `EnvironmentInstallCodes`: 26/28 = 92.9%

## Limits

- The Windows pwsh execution test is skipped because `pwsh` is not installed. Windows command structure, ACL order, quoting, and cleanup markers are asserted without executing PowerShell.
- No full-repo `mvn verify` was run.
