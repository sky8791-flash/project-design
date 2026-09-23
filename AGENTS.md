# AGENTS.md

Guidance for agents working in this repository.

## Repository layout

Two independent apps, no root build file — build each one separately.

- `backend/` — Spring Boot 3.2.5, Java 21, Maven (`backend/pom.xml`, artifact `online-collab-doc`).
- `frontend/` — Vue 3 + Vite 8 SPA, TipTap 3 editor, `vue-router` (hash history). No TypeScript, no
  Pinia: state is per-view refs plus the singleton services.
- `在线协作文档编辑系统.md`, `系统模式结构图.md` — design docs (Chinese). They still describe a
  char-offset OT write path and a CRDT roadmap that the code no longer follows; see
  "Collaboration model" below before trusting those sections.

Both `backend/target/` and `frontend/dist/` are build output and are untracked (root `.gitignore`).

## Commands

Backend (run from `backend/`):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # http://localhost:8080, schema in collabdoc_dev
mvn spring-boot:run                                  # default profile, schema in collabdoc
mvn -o -DskipTests package
mvn test                                             # 39 tests on in-memory H2, no MySQL needed
```

`CollabInvariantTest` and `DocumentSequencerTest` encode *why* the design is correct: the sequence is unique
and monotonic, the replay log is gapless and still carries the submitted steps, a content frame is never
echoed to its own session (asserted at the session map, where the exclusion actually happens, and by frame
`type` — an `equals` matcher could never match the presence-stamped copy the observer broadcasts), a rolled
back write fans out nothing, and one attached observer per document wins over the no-viewer fallback.
`rolledBackWriteFansOutNothing` is paired with `committedWriteFansOutExactlyOnce` so the former cannot pass
because nothing ever publishes. The exclusion test was checked by mutation: neutralising
`WebSocketSessionManager`'s `continue` turns exactly that one test red.

`mvn test` uses `src/test/resources/application-test.yml` (H2 in MySQL mode, `create-drop`). First run
needs network for the surefire provider and the pinned `h2:2.3.232`; after that `-o` works. Two H2 quirks
are baked into that URL: it reads a `JSON` column back **quoted** — so never assert on the raw
`command_params` string in a test, the MySQL shape (`JSON_TYPE = OBJECT`, parsed by the client) is what
matters and is checked at the wire level — and it keeps `USER` as a reserved word, which `NON_KEYWORDS=USER`
releases; without it the `user` table is never created and every test that touches an account fails on DDL.

Frontend (run from `frontend/`):

```bash
npm install
npm run dev          # http://localhost:5173, proxies /api and /ws to :8080
npm run build && npm run preview
```

There is no lint, formatter or test-runner script configured. `npm run build` is the only automated
check that covers the frontend, so run it after any edit.

Prerequisite: MySQL on `localhost:3306` with `root` and no password. `application.yml` targets
database `collabdoc`; `application-dev.yml` overrides only the JDBC URL to `collabdoc_dev` with
`createDatabaseIfNotExist=true` and is the safe choice for experiments — `collabdoc` holds real
data. `spring.jpa.hibernate.ddl-auto: update` means the entities define the live schema;
`backend/src/main/resources/schema.sql` is a reference document — it does **not** run against MySQL
(`spring.sql.init.mode` defaults to `embedded`) but it **does** run against an embedded database, so the
test profile sets `spring.sql.init.mode: never` explicitly. Keep the file aligned with the entities — in
particular it declares **no `FOREIGN KEY`s**, because the entities don't: referential integrity lives in the
service layer (`AdminService.deleteUser` removes the user's documents, shares and logs itself).

⚠️ `document_snapshot` and `operation_log` carry unique `(document_id, version)` constraints. Hibernate
cannot add those indexes to a table whose existing rows violate them, so pointing a profile at a
database written by the pre-P0 code fails at startup — dedupe or drop the old rows first.

Vite proxies `/api` and `/ws`, so the frontend uses relative URLs only; do not hardcode `:8080` in
`frontend/src/`.

Redis is **not** a prerequisite: `app.collab.bus` defaults to `memory`, and nothing contacts Redis unless
`COLLAB_BUS=redis`. When it is set, `REDIS_HOST`/`REDIS_PORT`/`SPRING_DATA_REDIS_PASSWORD` are read from
the environment, and `MANAGEMENT_HEALTH_REDIS_ENABLED=true` should follow — the Redis health indicator is
off by default so a single-instance deployment without Redis still reports `UP`.

## Architecture

Request path: `*Controller` → `service` → `repository` (Spring Data JPA) → MySQL.
Realtime path: `DocumentWebSocketHandler` (`/ws/document/{documentId}?token=`) → `DocumentService`.

### Collaboration model

The server is a **sequencer**, not a content interpreter. It never parses document content: clients
send ProseMirror step batches as opaque JSON, the server orders them, and clients do all applying.

`DocumentService.appendStepBatch` is the write path:

1. `DocumentRepository.findByIdForUpdate` takes a `PESSIMISTIC_WRITE` row lock, so concurrent writers
   queue and `version + 1` cannot collide.
2. Authorization runs against the locked row (`requireWriteAccess`), closing the old controller-level
   check-then-act window.
3. `baseVersion` must equal the locked version. Equality is required, not assumed: a stale base is
   refused with `ConflictException(currentVersion)`, which the handler turns into a `REJECT` frame.
4. `casAdvanceVersion` / `casContent` are compare-and-set updates (`where version = :base`); zero rows
   affected also means conflict.
5. The ordered batch is stored in `operation_log` (`command_type = 'STEPS'`, params hold the step JSON)
   and a `ContentAppliedEvent` is published.

**`document.version` is the sequence number and only ever increases** — `Document.version` has no
setter, and undo/restore write old content forward under a new version.

`pattern/memento` and `pattern/observer` are the only pattern packages left. The char-offset OT
(`ot/OperationalTransform`, `ot/OTOperation`) and `pattern/command` were deleted: authoritative content is
TipTap document JSON, in which offset OT is not valid, and the server never interprets content — so a
command object had nothing to act on. Do not reintroduce them; the reasoning is written up in
`在线协作文档编辑系统.md` ("为什么不用字符偏移 OT 处理富文本").

### Client side (`frontend/src/collab/otClient.js`)

Convergence lives here. At most one batch is outstanding; later local steps fold into the unsent
batch through a `Mapping`. `integrate()` maps incoming steps through the local steps that are applied
but unacknowledged (so they land in this document) and maps the pending batches through the incoming
steps (so they remain sendable against the server's order) — both directions are required, and
skipping the first is the classic lost-update/`TransformError` bug. Frames are handled through one
serialized promise chain because the server fans frames out after commit, so two writers' frames can
arrive out of order; a version gap is closed by refetching `GET /api/documents/{id}/operations?after=`.
Any error in that chain falls back to `resync()`.

Whole-document state is only ever replaced on `INIT`, `RESET` and resync, and always with
`setContent(content, { emitUpdate: false })` — a plain `setContent` would echo the whole document back
as a local step batch.

### Observer wiring and the collab bus

Nothing sends frames through `WebSocketSessionManager` directly. `CollabBus` is the single entry point for
every outbound frame, and `LocalDelivery` (implemented by `WebSocketSessionManager`) is the callback the
active bus uses to write into the sockets *this* process holds. That direction — bus owns fan-out, session
map owns delivery — is what keeps multi-instance support from being a special case in the handlers.

- `LocalCollabBus` (`app.collab.bus=memory`, the default) delivers straight to `LocalDelivery`.
- `RedisCollabBus` (`COLLAB_BUS=redis`) publishes a JSON `FrameEnvelope` on `collab:frames`; every node
  subscribes and delivers locally, and the publishing node takes the same path rather than shortcutting —
  one delivery path, so the origin-session exclusion lives in exactly one place. Membership is
  `collab:doc:{documentId}` → `{sessionId: nodeId}` plus a `collab:node:{nodeId}` lease that a 10s
  `@Scheduled` heartbeat renews. The tick **adds** this node's live sessions, then **drains** a queue of
  closings, then renews the lease — each step independently guarded. That order is load-bearing: closing
  records the removal intent even when its `HDEL` succeeded, because a sweep that snapshotted the session
  beforehand would otherwise re-add it, and a field owned by a live node is never pruned by anyone.
  Renewing the lease last and unconditionally matters too — gating it on "every document succeeded" let one
  poisoned key cost the node its lease and made peers prune every document it holds.

`DocumentSubjectImpl` keeps one observer per document, attached when a session arrives and detached when the
last one leaves. `notifyAllObservers` also publishes when this node holds **no** viewer: a rename or a
restore arrives over HTTP on whichever node the load balancer picked, and dropping it there would starve the
clients that are connected somewhere else.

`WebSocketObserver.update` unicasts the `ACK` **before** broadcasting: the unicast is the sender's permission
to release its next batch and it cannot fail over the network, so it must never sit behind a call that can.
A **`CollabBus` implementation never throws** — its callers are post-commit fan-out and socket lifecycle
paths, where an exception costs a 5xx for a write that already committed, or closes a healthy connection;
`RedisCollabBus` therefore degrades instead (local `onlineCount` fallback, logged-and-dropped publish).
Losing a frame is recoverable because every one carries the version, and the client refetches the gap over
HTTP. `DocumentService` publishes `ContentAppliedEvent` instead of notifying observers directly;
`DocumentSubjectImpl.notifyAllObservers` is a `@TransactionalEventListener(AFTER_COMMIT,
fallbackExecution = true)`, so nothing reaches clients before commit. `afterCommit` runs on the committing
thread and does not order frames across writers — hence the version in every frame.

The document id is a **canonical string of the numeric id** on every side of that wiring: the socket path
segment is free text, so `DocumentWebSocketHandler` normalizes `/ws/document/007` to `"7"` before keying the
participant, the session map and the observer. Keys derived any other way silently never match, and the
symptom is a tab that stops syncing with no log line.

`WebSocketSessionManager.register` wraps sessions in `ConcurrentWebSocketSessionDecorator`; send through the
registered/decorated session, never the raw one, or concurrent broadcasts interleave. `deliver` catches
`RuntimeException` as well as `IOException`, because the decorator throws unchecked when one slow client
crosses the send time or buffer limit and an escaping exception would skip every remaining session in the
loop.

Multi-instance status: **code complete, not runtime-verified.** Two nodes behind a broker were never
exercised, because starting Docker Desktop on this machine makes the campus network refuse authentication
(it detects the `vEthernet (WSL)` adapter). Everything above is verified only as far as the `memory` bus
and unit tests go.

### WebSocket frames

Server → client: `INIT` (title, content, contentFormat, version, checkpointVersion, onlineCount,
permission), `STEPS`, `ACK` (with `checkpointRequested`), `REJECT`, `RESET`, `TITLE_UPDATE`,
`CURSOR_UPDATE`, `NOTIFICATION`, `USER_JOINED`, `USER_LEFT`. Client → server: `STEP_BATCH`, `CURSOR`.
The document id always comes from the connection path, never from a frame payload.

`INIT` returns the newest **checkpoint** content plus `checkpointVersion`, which is usually *below*
`version`. A client must then replay `GET /{id}/operations?after=checkpointVersion` — that is
`DocumentClient.bootstrap`, and it is the only way a mid-session joiner, a resync or a restart sees text
that was committed as steps. The server never interprets content, so it cannot answer this question by
itself.

Frames are serialized with the **Spring-managed** `ObjectMapper` (injected into
`WebSocketSessionManager`). A bare `new ObjectMapper()` silently drops `LocalDateTime` fields —
`serialize()` returns null and the frame vanishes with one log line.

`USER_JOINED`, `USER_LEFT` and `STEPS` all carry `onlineCount`; a frame that omits it leaves the client
showing the previous number, because `DocEditor.vue` only updates the badge when the field is present.

Handshake refusals: no valid `?token=` is refused with HTTP 401 **before** the upgrade by
`JwtHandshakeInterceptor`, so the browser never sees a close code for it. Once upgraded, the handler uses
close code `4001` (no access, or the account stopped existing/was disabled mid-session) and `4004`
(malformed); the client treats 4xxx as terminal and does not retry.

### History and retention

`operation_log` is the replayable history; `document_snapshot` holds checkpoints. Every
`CHECKPOINT_EVERY` (200) committed versions the server asks the acknowledged client, via
`checkpointRequested` on its `ACK`, to upload its full document JSON to
`POST /api/documents/{id}/checkpoint`. Recording a checkpoint folds the log: the operations at or
below it are deleted, and `MementoCaretaker` keeps at most 50 snapshots per document. `restoreVersion`
reads a snapshot and writes it forward as a new version, broadcasting `RESET`.

Editor-side undo/redo is TipTap/ProseMirror history (`editor.chain().undo()`), which is per user by
construction and unaffected by other people's edits.

## Auth & authorization

Spring Security with stateless JWT bearer tokens. `POST /api/users/login` and `/register` return
`{ id, username, userCode, role, token, expiresIn }`; everything else is authenticated.

- Identity comes from the **principal**, never from request data: controllers take
  `@AuthenticationPrincipal AuthUser caller`, and there is no `?userId=` / body `userId` / `X-User-Id`
  path left for a caller to assert their own identity. A body `userId` still appears where it names the
  *target* of an action (who a document is shared with, whose account an admin edits).
- `JwtAuthFilter` re-reads the account on **every** request, so disabling or demoting a user takes effect
  immediately instead of at token expiry; the token only proves who is calling. An unknown username still
  costs one bcrypt comparison against a stored throwaway digest, so login timing does not leak existence.
- A browser cannot send an `Authorization` header on a WebSocket, so the socket token travels as
  `?token=` and `JwtHandshakeInterceptor` turns it into a session attribute before the handler runs. No
  valid token ⇒ the upgrade is refused with 401. `?userId=` on a socket URL now means nothing. The
  interceptor sets `Cache-Control: no-store` on the handshake for the same reason. **Never enable
  `org.springframework.web.socket` at `DEBUG`** — its handshake logging prints the full session URI,
  token included; `application.yml` says so where the level is set.
- The role in a token is not the role in effect: `JwtAuthFilter` rebuilds the authority from the database
  row on every request, so a correctly signed token that *claims* `ADMIN` in the payload still gets 403
  until the row says `ADMIN`.
- Public: `/api/users/login`, `/api/users/register`, `/ws/**`, `/actuator/health`. Everything else
  requires authentication; `/api/admin/**` additionally requires `ROLE_ADMIN` via `@PreAuthorize`.
- Passwords are BCrypt with transparent upgrade: a stored bare Base64 SHA-256 digest (or the older
  `sha256:` prefix) still authenticates and is rewritten as `$2a$…` on that successful login.
- `User.enabled` is enforced at login (403) and on every authenticated request (401). Admins cannot
  disable, demote or delete their own account — `AdminService` refuses, because the token would stop
  working mid-session with no way back in.
- No code promotes anyone to `ADMIN`; the first admin must be set directly in the database.
- `JwtService` fails startup if `app.jwt.secret` is under 32 characters. `application.yml` reads
  `${JWT_SECRET:…}` with a clearly-labelled development fallback — set a real secret anywhere else.
- Tokens are not refreshed or individually revoked; TTL (`app.jwt.ttl`, default 8h) plus the
  enabled/role re-check is the invalidation mechanism.

`READ_ONLY` is enforced server-side (writes, `STEP_BATCH` and rename throw `ForbiddenException`, which
closes the socket) and mirrored client-side: `INIT.permission` drives `editor.setEditable(false)` and the
toolbar disables.

`AuthorizationMatrixTest` is the regression net for all of the above: it drives the **real filter chain**
through MockMvc, so it covers things a service unit test cannot — which routes are anonymous, that every
caller-supplied identity field (`?userId=`, a path id, a body `userId` naming the caller) is decoration
while the principal decides, that disabling or demoting a row takes effect on the next request, and that
the socket handshake refuses a missing/garbage/disabled token. Add to it rather than re-verifying by hand.

Remaining known holes: no rate limiting or lockout on `/api/users/login`, and there is no CSRF concern
only because the API is token-in-header and fully JSON — do not add cookie/session auth without revisiting
that. **One open P0 defect**: an out-of-order pair of frames — the remote `STEPS` for a concurrent write
arriving *before* our own `ACK` — wedges the tab. Reproduced in a real browser by holding the outbound
`STEP_BATCH` (patch `WebSocket.prototype.send` in the page) while a second client commits: the local text
disappears, the status line reads `Cannot read properties of undefined (reading 'resolve')`, thrown from
`applySteps` via `view.dispatch`, and the local batch never lands. What is ruled out: it is not a
hand-written step shape (the payload was copied from a genuine client batch), and it is not replaying our
own row in `catchUp` (that double-application was fixed — `catchUp` now stops at `throughVersion` — and
the crash survives). The remaining suspect is `integrate()`'s mapping when `outstanding` is dropped by a
`REJECT` while a gap refetch has already advanced `version`. Start by reproducing it in Node against
`@tiptap/pm` with the two captured payloads, not in the browser.

## REST surface

- `/api/users` — `POST /register`, `POST /login` (both return a token), `GET /lookup?userCode=`
  (8-digit share handle, authenticated).
- `/api/documents` — `POST` (create; the owner is the caller), `GET /{id}`, `GET /search?keyword=`,
  `GET /me` and `GET /shared` (both `DocumentSummary`: no body, shared list carries `permission`),
  `PUT /{id}/title` (rename), `PUT /{id}` (whole-document write, `409` on a stale `version`),
  `POST /{id}/checkpoint`, `DELETE /{id}`, `GET /{id}/operations?after=`, `GET /{id}/history`,
  `POST /{id}/restore/{version}`, `POST /{id}/share` (body `userId` = target),
  `GET /{id}/shares` (`DocumentShareView`, includes `username`), `DELETE /{id}/share/{userId}`.
  None of these take a caller id any more — `GET /user/{userId}` and `/shared/{userId}` were replaced by
  `/me` and `/shared`.
- `/api/notifications` — `GET`, `GET /unread-count`, `POST /read`; always the caller's own inbox.
- `/api/admin/users` — list (paged, keyword), detail, `PUT /{id}/role`, `PUT /{id}/enabled`,
  `PUT /{id}/password` (returns a generated one-time password), `DELETE /{id}` (cascades the user's
  documents, shares and operation logs).
- There are **no** `/undo` or `/redo` endpoints; the old ones were document-global and reverted other
  users' edits.

## Conventions

- Plain POJOs with hand-written getters/setters; Lombok is a declared dependency but unused — do not
  introduce annotations the rest of the codebase avoids. Java records are acceptable for private
  value types inside a class (see `DocumentWebSocketHandler.Participant`).
- Status mapping lives in **one** place: `controller/GlobalExceptionHandler` is a `@RestControllerAdvice`
  covering `InvalidCredentials` → 401, `NotFound` → 404, `Forbidden|AccessDenied` → 403, `Conflict` → 409
  (+ the current version), `IllegalArgument|IllegalState` and bad request params → 400. No controller maps
  an exception to an HTTP status; the few remaining `try/catch` blocks only convert a malformed field into
  `IllegalArgumentException`. Do not re-scope the mapping per controller.
  Its catch-all `Exception` handler is why that class also maps Spring MVC's own signals
  (`NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, `HttpMessageNotReadableException`)
  — without those, an unknown path or a wrong verb becomes a 500 instead of 404/405.
  `MethodArgumentTypeMismatchException` is deliberately in the 400 clause above, not here.
  `@ExceptionHandler` cannot list the `ErrorResponse` interface (not a `Throwable`), so the concrete types
  are enumerated and the status is read per exception. `badRequest` takes `Throwable` because two of its
  types are checked; narrowing it to `RuntimeException` still returns 400 but reverts the body to Boot's
  shape, which is what `springOwnRoutingErrorsKeepTheirStatus…` fails on.
- Services throw the exceptions above with human-readable messages.
- Success/error strings are English on the backend, Chinese in the UI. Keep that split.
- Constructor injection everywhere on the backend; no field `@Autowired`.
- `DocumentState` is the shared read model for state-changing document calls (it now also carries
  `contentFormat`); extend it rather than inventing a new response shape.
- Frontend state is per-view refs plus singletons (`services/api`, `services/websocket`,
  `services/notifications`). Routes are declared in `src/router/index.js` with **hash** history, so
  `/#/doc/5` is deep-linkable and survives a static host without an SPA rewrite; `App.vue` still gates on
  `currentUser` rather than on a navigation guard.
- `wsService.on(type, handler)` returns an unsubscribe function, and handlers are stored per type as
  arrays — always unsubscribe on `onBeforeUnmount`, because a leaked handler from a previous document
  would keep receiving frames.

## Verifying changes

Start the backend with the `dev` profile and `npm run dev`, then open the same document in two browser
tabs as two different users (share it `READ_WRITE` first). Things worth watching:

- Exactly one `STEPS` broadcast per accepted batch, and no `STEPS` frame returning to its own sender
  (DevTools → the socket's Messages tab, filter on `STEPS`).
- `USER_LEFT` arrives **with** `onlineCount` and the number goes down when a tab closes.
- Typing in both tabs simultaneously: one batch is acked, the other receives `REJECT`, and both tabs
  converge on the same text with no caret jumps. **This one currently fails** — see the open P0 defect
  above; do not mark a change to `otClient.js` verified until this passes with the frames delivered in
  both orders.
- `SELECT document_id, version, COUNT(*) FROM operation_log GROUP BY 1,2 HAVING COUNT(*) > 1` → empty,
  same for `document_snapshot`.
- Restarting the backend keeps history (`GET /{id}/history`), and restoring a version makes `version`
  go up, never down.
- OT history and undo stacks are JVM state, so restarts reset them. So is the session registry on the
  default `memory` bus — which is exactly why two instances need `COLLAB_BUS=redis`.

The socket invariants are also checkable without a browser: with the `dev` profile running, register two
users over REST, create a document as one, `POST /{id}/share` it `READ_WRITE` to the other, then open two
`WebSocket` clients (Node 21+ has the global; the Qoder Node REPL sandbox does **not**, so run `node -e`)
at `ws://localhost:8080/ws/document/{id}?token=…`. One `STEP_BATCH` from A should produce `ACK` for A,
exactly one `STEPS` for B, nothing of type `STEPS` back to A, and a `USER_LEFT(1)` for A when B closes.
