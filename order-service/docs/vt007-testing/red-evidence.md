# VT-007 RED evidence

- Test-only commit: `d012471295b47bc7e7f4f5c52fc6cc28e0a14c65`
- Command:
  `JAVA_HOME=/tmp/issue27-jdk17 PATH=/tmp/issue27-jdk17/bin:$PATH /tmp/apache-maven-3.9.11/bin/mvn -s /tmp/maven-settings.xml -B -Dmaven.repo.local=/workspace/.m2/repository -Dapi.version=1.44 -Ddocker.api.version=1.44 -pl order-service -Dtest=OrderDraftIntegrationTests test`
- Runtime: PostgreSQL 16 Testcontainers.
- Actual result: **Tests run: 10, Failures: 10, Errors: 0, Skipped: 0**.
- Expected RED cause: requests to `PUT/GET /api/v1/order-drafts/{draftId}` cannot be routed because the endpoint has not been implemented.
- Repository evidence: Surefire records `NoResourceFoundException: No static resource api/v1/order-drafts/...`; the existing global handler converts this missing-route exception to HTTP 500, so assertions expecting the specified 2xx/4xx outcomes fail.
- This is an executable behavioral RED, not a compilation failure: the application context, Flyway and PostgreSQL container all started, and all 10 test methods ran.

Raw machine-readable output remains available locally at
`order-service/target/surefire-reports/TEST-com.example.orderworkflow.order.OrderDraftIntegrationTests.xml` until the next clean build.
