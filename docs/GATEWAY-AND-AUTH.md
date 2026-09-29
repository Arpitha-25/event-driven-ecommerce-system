# API Gateway and JWT Authentication

Clients now reach the whole system through **one entry point**, the API gateway on port 8000. Access is controlled with **JWT access tokens** issued by a new **identity-service**.

---

## 1. How it fits together

```text
                        Client
                          │  Authorization: Bearer <JWT>
                          ▼
      ┌──────────────── API Gateway :8000 ────────────────┐        ┌──── Identity Service :8084 ────┐
      │ 1. X-Correlation-ID (kept or generated)           │ /auth/**│ register · login               │
      │ 2. verify the JWT: RS256 signature, expiry,       │────────►│ BCrypt password hashes         │
      │    issuer (public keys from identity's JWKS)      │◄────────│ signs tokens with its RSA      │
      │ 3. access rules by path, method and role          │  JWKS   │ private key; publishes the     │
      │ 4. drop client X-User-*; add X-User-Id / -Email / │         │ public key                     │
      │    -Roles from the verified token                 │         │ own database (users, Flyway)   │
      └────┬─────────────────────┬───────────────────┬────┘         └────────────────────────────────┘
           ▼                     ▼                   ▼
    order-service :8080   product-service :8082  inventory-service :8083
```

### Access rules (in `api-gateway/.../security/SecurityConfig.java`)

| Request | Who |
|---------|-----|
| `POST /api/v1/auth/register`, `POST /api/v1/auth/login` | anyone |
| `GET /api/v1/products/**` | anyone |
| `GET /api/v1/auth/me`, `GET /api/v1/inventory/**`, everything under `/api/v1/orders/**` | any logged-in user |
| `POST/PUT/DELETE /api/v1/products/**` and `/api/v1/inventory/**` | **ADMIN** only |
| anything else | nobody (`denyAll`) |

- A missing or invalid token gets **401**, and an insufficient role gets **403**. Both come back as JSON in the same shape as the services' `ApiError` (`{"success":false,"errorCode":"UNAUTHORIZED",…}`) and include the correlation ID.
- Rejected requests **never reach a service**.
- `/actuator/health` and `/actuator/info` on the gateway are public.

---

## 2. identity-service

| Endpoint | What it does |
|----------|--------------|
| `POST /api/v1/auth/register` | Creates a **USER** account: `{email, password, fullName}` → 201. The email is case-insensitive and must be unique (409 otherwise). The password must be 8 to 72 characters. |
| `POST /api/v1/auth/login` | `{email, password}` → `{accessToken, tokenType: "Bearer", expiresIn: 3600}` |
| `GET /api/v1/auth/me` | The caller's account; needs a token |
| `GET /.well-known/jwks.json` | The **public** signing key, for the gateway |

**Design decisions:**

| Decision | Why |
|----------|-----|
| **RS256 (asymmetric) instead of a shared secret** | Only identity-service holds the private key. The gateway, or any future service, verifies tokens with the public key, so none of them could forge a token if compromised. |
| **Public key published as a JWKS**, with a `kid` in every token | The gateway fetches the keys by itself, and a key can be rotated by publishing a new one. |
| **Token claims:** `sub` (user id), `email`, `roles`, `iss`, `iat`, `exp` (60 minutes) | The gateway needs nothing else, so it never calls identity-service per request. |
| **BCrypt password hashes; passwords limited to 72 characters** | BCrypt only uses the first 72 bytes, so longer passwords would silently be cut short. |
| **Same answer, and similar time, for "no such email" and "wrong password"** | When the email is unknown, login still checks the password against a dummy hash. Neither the message nor the response time reveals which emails are registered. |
| **Registration always creates USER.** The ADMIN account comes from configuration (`IDENTITY_ADMIN_EMAIL` / `IDENTITY_ADMIN_PASSWORD`) and is created at startup if missing. | Nobody can register themselves as an admin. |
| **Signing key from PEM files** (`JWT_PRIVATE_KEY_LOCATION`, `JWT_PUBLIC_KEY_LOCATION`), **or generated at startup** if none is configured | Generated is fine for development: tokens simply stop working when identity-service restarts. Production should mount a real key. |
| **Own database (`ecommerce_identity_db`), schema by Flyway** (`users`, unique email) | Same pattern as the other services. |

## 3. api-gateway

- **Spring Cloud Gateway (reactive).** Routes: `/api/v1/auth/**` → identity, `/orders/**` → order, `/products/**` → product, `/inventory/**` → inventory. The addresses are set by environment variables, so the same image works in compose and in tests.
- **Spring Security resource server.** `NimbusReactiveJwtDecoder` with the JWKS URL plus issuer validation. The `roles` claim is mapped to `ROLE_*` authorities.
- **`UserHeadersFilter`.** Removes **every** `X-User-*` header the client sent, then adds `X-User-Id`, `X-User-Email` and `X-User-Roles` from the verified token. A service can therefore trust these headers, as long as it's only reachable through the gateway.
- **`CorrelationIdFilter`.** Keeps the client's `X-Correlation-ID` or generates one, forwards it, and returns it on every response, including 401 and 403.
- **Timeouts** to downstream services: 2 s to connect, 15 s to respond.
- **No dependency on `common-module`.** The gateway is reactive (WebFlux), and common-module brings in the servlet stack.

## 4. Docker

- **New images:** `identity-service/Dockerfile` and `api-gateway/Dockerfile`, built the same way as the others (multi-stage, non-root JRE).
- **New containers** in `docker-compose.yml`: `api-gateway` (:8000) and `identity-service` (:8084, with a development-only admin account).
- **New `db-init` service.** `docker/postgres/init.sql` creates the service databases, but PostgreSQL only runs it when the data volume is **new**, so an existing volume would never get `ecommerce_identity_db`. The script now only creates missing databases (`\gexec`), and `db-init` runs it on every `docker compose up` before any service starts. On this machine's existing volume it created exactly one database, the identity one.
- The services' own ports are still published for development tools and scripts. In a real deployment only the gateway would be exposed.

---

## 5. Tests

### identity-service: `IdentityServiceIntegrationTest` (11 tests, real PostgreSQL via Testcontainers)
- Registration stores a **BCrypt hash**, never the password. The same email in another letter case is rejected (409). Invalid input returns field errors (400).
- **The issued token is verified exactly as the gateway will verify it:** its `kid` is looked up in the published JWKS, the RS256 signature checks out against that public key, and `sub`, `email`, `roles`, `iss` and a 3600 s lifetime are correct.
- A wrong password and an unknown email return the **same** code and message. Login ignores email case and surrounding spaces.
- `/me` rejects: no token, garbage, a token **signed by another key**, an **expired** token, and a token from **another issuer** (the last two signed with the service's real key, so only the claims are wrong).
- The JWKS contains `n` and `e` and **none** of the private parts (`d`, `p`, `q`, `dp`, `dq`, `qi`).
- The admin from configuration is created once, with role ADMIN.

### api-gateway: `GatewaySecurityIntegrationTest` (18 tests)
A MockWebServer plays both identity-service (serving a JWKS) and the downstream services, and records every request the gateway lets through. So each test checks the response **and** whether anything was forwarded.
- **Rejected with 401, never forwarded:**
  - no token
  - expired token
  - wrong issuer
  - token signed by another key
  - token signed by another key **but claiming the real key's `kid`**
  - valid token with **claims edited** (USER → ADMIN)
  - unsigned token (`alg: none`)
  - garbage
- **Access rules:**
  - anonymous product reads are forwarded
  - USER product writes get 403
  - ADMIN POST, PUT and DELETE are forwarded
  - inventory reads need a login, and changes need ADMIN
  - register and login are public, but `/me` needs a token
  - unknown routes are 401 for anonymous callers and 403 for authenticated ones
- **Headers:**
  - a verified request arrives with `X-User-Id`, `-Email` and `-Roles` from the token
  - client-supplied `X-User-*` headers are replaced, or removed for anonymous requests
  - the correlation ID is generated, kept, forwarded, and present on 401 bodies

### System test: `GatewaySystemTest` (real images in Docker)
It runs in the same shared environment as `OrderFlowSystemTest`: PostgreSQL, Kafka and all five service images, started once.
- **Full customer journey through the gateway only:**
  1. the admin logs in and creates a product and its stock
  2. a customer registers, logs in, calls `/me` and places an order with an `X-Correlation-ID`
  3. the order becomes **CONFIRMED** through the Kafka saga behind the gateway, and stock shows 2 reserved, 3 available
  4. the correlation ID arrived all the way into the order's outbox event
- **Anonymous callers** can browse products and get 401 on everything else.
- **Customers** get 403 on product and stock changes, and nothing was created.
- **Forged and tampered tokens** are rejected by the real gateway.
- **A wrong password** returns identity-service's 401 through the gateway.

*(Results: see section 7.)*

### End-to-end script: `scripts/test-gateway-auth.ps1` (21 checks against the running compose stack)
It checks accounts and tokens, the access rules, bad tokens, and the order saga through the gateway, including the correlation ID reaching the Kafka event.

---

## 6. Problems found by running it

1. **Every forwarded request returned 500.** The first gateway test run failed 8 of 18 tests: all rejections worked, but everything that should reach a service failed. The filter that strips `X-User-*` headers edited the request headers in place, and at that point in the filter chain they're a **read-only view** (`UnsupportedOperationException`, then `ReadOnlyHttpHeaders.set`). The filter now builds a fresh copy of the headers and wraps the request with it.
2. **The correlation ID came back twice.** The end-to-end script showed `X-Correlation-ID: probe-123` **twice** in responses, because the gateway sets it and the services echo it back, while the gateway's own test backend didn't echo it. The gateway now sets the header when the response is committed, which overwrites the service's copy. The test backend now echoes the header like the real services, and without the fix that test fails with ``expected [trace-123] but was [trace-123, trace-123]``.
3. **The identity database was missing from existing volumes.** `init.sql` only runs for a brand-new volume. This was fixed with the create-if-missing script and the `db-init` service.
4. **A system-test bug.** The check that a forbidden product wasn't created read `sku` from the product list, but the list returns summaries without a SKU (a `NullPointerException` in the test). It now checks by a unique product name, and first asserts that the list isn't empty, so the check can't pass vacuously.
5. **A script and a test weren't repeatable.** Product names must be unique, and the second run of the end-to-end script reused a fixed name (409, then everything after it failed). The script and the system test now use a unique name per run, and the script passed twice in a row.
6. **The PC ran out of disk space mid-way.** Drive C: filled up, and Docker Desktop restarted by itself, stopping PostgreSQL uncleanly. PostgreSQL's crash recovery replayed its write-ahead log cleanly, and every table and row count checked out afterwards. To keep going: Docker's build cache was cleared (with approval), Maven's temp files were redirected to D:, and built service jars were removed from the Maven cache on C:.

---

## 7. Results so far

| Check | Result |
|-------|--------|
| `mvn clean install` (all modules: unit + Testcontainers integration tests) | **98 tests**, 0 failures, 0 skipped (69 existing + 11 identity + 18 gateway) |
| `docker compose up` with gateway and identity (existing volume) | All services UP; `db-init` created `ecommerce_identity_db` and nothing else; Flyway built the `users` table; the admin was created; 0 errors in the gateway and identity logs |
| System tests (all five real images, PostgreSQL and Kafka in Docker) | **12/12**: `GatewaySystemTest` 5/5 and `OrderFlowSystemTest` 7/7 on the shared environment; 0 ERROR lines in any service's log |
| `scripts/test-gateway-auth.ps1` | 21/21, twice in a row |
| `test-order-saga.ps1` / `test-reliable-consumers.ps1` / `test-outbox-kafka-outage.ps1` (regression) | 19/19 · 17/17 · 9/9 |

---

## 8. Trying it

```bash
docker compose up -d --build
```
```powershell
# register, log in, place an order, all through the gateway
Invoke-RestMethod -Method Post http://localhost:8000/api/v1/auth/register -ContentType application/json -Body '{"email":"me@example.com","password":"my-password-1"}'
$token = (Invoke-RestMethod -Method Post http://localhost:8000/api/v1/auth/login -ContentType application/json -Body '{"email":"me@example.com","password":"my-password-1"}').data.accessToken
Invoke-RestMethod http://localhost:8000/api/v1/auth/me -Headers @{ Authorization = "Bearer $token" }

# admin (development account from docker-compose.yml)
$admin = (Invoke-RestMethod -Method Post http://localhost:8000/api/v1/auth/login -ContentType application/json -Body '{"email":"admin@ecommerce.local","password":"admin12345"}').data.accessToken

powershell -ExecutionPolicy Bypass -File scripts\test-gateway-auth.ps1
```

**Not included:**
- refresh tokens and logout: access tokens simply expire after 60 minutes
- limits on repeated login attempts
- HTTPS termination

These would be the next steps for a production deployment.
