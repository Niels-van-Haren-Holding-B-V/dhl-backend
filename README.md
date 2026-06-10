# dhl-backend

Kotlin + Spring Boot 4 backend for the locker integration demo: the courier
BFF (`/api`), the simulated Locker API of the "other team" (`/locker-api`),
and all shared infrastructure (k3s, Keycloak, Postgres, Redpanda) under
`/infra`.

- Spec & conventions: [CLAUDE.md](CLAUDE.md)
- Infra & runbooks: [infra/README.md](infra/README.md)
- OpenAPI: `/v3/api-docs` (the frontend generates its client from this)

```bash
docker compose -f infra/docker-compose.yml up -d
./gradlew bootRun        # http://localhost:12080
./gradlew test           # unit + Testcontainers integration tests (needs Docker)
```
