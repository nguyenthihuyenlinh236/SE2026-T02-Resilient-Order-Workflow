# VT-007 GREEN evidence

- Implementation commit: `dd2988173fbd39a0e3729be6f9ca11680a8737db`
- Focused command:
  `JAVA_HOME=/tmp/issue27-jdk17 PATH=/tmp/issue27-jdk17/bin:$PATH /tmp/apache-maven-3.9.11/bin/mvn -s /tmp/maven-settings.xml -B -Dmaven.repo.local=/workspace/.m2/repository -Dapi.version=1.44 -Ddocker.api.version=1.44 -pl order-service -Dtest=OrderDraftIntegrationTests test`
- Runtime: PostgreSQL 16 Testcontainers.
- Final focused result: **Tests run: 10, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS**.
- A first implementation run exposed an unsupported PostgreSQL `timestamptz` to `Instant` JDBC conversion. The mapper was corrected to read `OffsetDateTime` and convert with `toInstant()`; no test expectation was weakened.
- Scope diff at the implementation commit: four new production Java files, migration `V006`, and the demo Flyway-count expectation updated from 5 to 6; 255 insertions and 1 deletion.

## Full repository verification

- Command:
  `JAVA_HOME=/tmp/issue27-jdk17 PATH=/tmp/issue27-jdk17/bin:$PATH /tmp/apache-maven-3.9.11/bin/mvn -s /tmp/maven-settings.xml -B -Dmaven.repo.local=/workspace/.m2/repository -Dapi.version=1.44 -Ddocker.api.version=1.44 clean verify`
- Actual result: **BUILD SUCCESS**, all four reactor entries succeeded in 2 minutes 26 seconds.
- Surefire XML totals: **206 tests, 0 failures, 0 errors, 0 skipped**.
  - order-service: 49
  - inventory-service: 148
  - payment-service: 9
- Flyway applied V001 through V006 successfully in the Order Service test databases.
