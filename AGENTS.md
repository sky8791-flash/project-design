# AGENTS.md

Guidance for agents working in this repository. Where a rule below has a long backstory, the reasoning is in
git history and in comments next to the code, not repeated here — this file states the rule, not the trial.

## Repository layout

Two independent apps, no root build file — build each separately.

- `backend/` — Spring Boot 3.2.5, Java 21, Maven (`backend/pom.xml`, artifact `online-collab-doc`).
- `frontend/` — Vue 3 + Vite 8 SPA, TipTap 3 editor, `vue-router` (hash history). No TypeScript, no Pinia:
  state is per-view refs plus the singleton services.
- `在线协作文档编辑系统.md`, `系统模式结构图.md` — Chinese design docs. Their char-offset OT and CRDT
  sections are stale; read "Collaboration model" below before trusting them.

`backend/target/` and `frontend/dist/` are build output, untracked.

## Commands

Backend (from `backend/`):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # :8080, schema collabdoc_dev (the experiment DB)
mvn spring-boot:run                                  # default profile, schema collabdoc (real data)
mvn -o -DskipTests package
mvn test                                             # 60 tests on in-memory H2, no MySQL needed
```

- `mvn test` uses `application-test.yml` (H2 MySQL-mode, `create-drop`). First run needs network for surefire
  + `h2:2.3.232`; after that `-o` works. The H2 URL quotes a `JSON` column back and reserves `USER`;
  `NON_KEYWORDS=USER` is load-bearing. Never assert on the raw `command_params` string in a test — the MySQL
  wire shape is what matters.
- `CollabInvariantTest` / `DocumentSequencerTest` encode the design invariants: unique monotonic sequence,
  gapless replay log carrying submitted steps, no echo to a content frame's own session, rolled-back write
  fans out nothing, one observer per document.
- Never write a test that sleeps a fixed window then counts — it depends on what was loaded first. Wait on a
  predicate over the state under test. A log appender a test attaches can be dropped by Boot's Logback
  re-init around context load, so re-attach on every poll (`RedisBusModeTest`).

Frontend (from `frontend/`):

```bash
npm install
npm run dev          # :5173, proxies /api and /ws to :8080
npm run build        # the ONLY automated check over the whole frontend; run after any edit
npm run check:collab # convergence harness over real otClient.js — plain Node, no server, ~3s
```

There is no lint, no formatter, no general test runner. `npm run check:collab` drives `src/collab/otClient.js`
against an in-process sequencer implementing the documented server contract over eleven random configs (525
rounds) plus scripted orderings that used to be bugs. Its oracle is not "the tabs agree" but "each tab equals an
independent replay of the log" — from the newest checkpoint over the rows above it, cross-checked against a
replay of the never-pruned row history. A run counts as converged only if it needed **no** whole-document
refetch (a version gap closed over `/operations` is the protocol working and is reported separately). Exit 0 =
27 checks green; two defects are flagged `expectedToFail` and the build fails if one ever passes, so a marker
cannot outlive its fix. The fake sequencer mirrors `DocumentService.recordCheckpoint` rather than being
stricter than it.

Prerequisite: MySQL on `localhost:3306`, `root`, no password. `application.yml` → `collabdoc` (holds real
data); `application-dev.yml` → `collabdoc_dev` with `createDatabaseIfNotExist=true` — use `dev` for
experiments. `ddl-auto: update`, so the entities define the live schema. `backend/src/main/resources/schema.sql`
is a reference document — it does **not** run against MySQL but **does** run against embedded DBs, so the test
profile sets `spring.sql.init.mode: never`. Keep it aligned with the entities; it declares **no FOREIGN KEYs**
because the entities don't — referential integrity lives in the service layer (`AdminService.deleteUser`).

⚠️ `document_snapshot` and `operation_log` have unique `(document_id, version)` constraints; Hibernate cannot
add them to a table whose rows violate them, so pointing a profile at pre-P0 data fails at startup — dedupe or
drop first. (Verified read-only against `collabdoc_dev` 2026-09-23: those constraints, `document.revision`,
`document.content_format` and the `operation_log.command_params JSON` column all exist and match the entities;
invariant I5 is enforced by MySQL. Nothing outside `collabdoc_dev` was touched.)

Vite proxies `/api` and `/ws`, so the frontend uses relative URLs only — never hardcode `:8080` in `frontend/src/`.

Redis is **not** a prerequisite: `app.collab.bus` defaults to `memory`, nothing contacts Redis unless
`COLLAB_BUS=redis`. When set, also set `MANAGEMENT_HEALTH_REDIS_ENABLED=true` (the indicator is off by default
so a Redis-less single instance still reports `UP`).

## Architecture

Request path: `*Controller` → `service` → `repository` (Spring Data JPA) → MySQL.
Realtime: `DocumentWebSocketHandler` (`/ws/document/{documentId}?token=`) → `DocumentService`.

### Collaboration model

The server is a **sequencer, never a content interpreter.** Clients send ProseMirror step batches as opaque
JSON, the server orders them, clients do all applying.

`DocumentService.appendStepBatch` (the write path):
1. `findByIdForUpdate` takes a `PESSIMISTIC_WRITE` row lock — concurrent writers queue, `version + 1` cannot collide.
2. Authorization runs against the locked row (`requireWriteAccess`), closing the check-then-act window.
3. `baseVersion` must equal the locked version; a stale base is refused with `ConflictException(currentVersion)` → `REJECT`.
4. `casAdvanceVersion` / `casContent` are compare-and-set (`where version = :base`); zero rows also means conflict.
5. The ordered batch is stored in `operation_log` (`command_type = 'STEPS'`, params = step JSON); a
   `ContentAppliedEvent` is published.

**`document.version` is the sequence number and only ever increases** — `Document.version` has no setter;
undo/restore write old content forward under a new version.

The char-offset OT (`ot/*`) and `pattern/command` were deleted (authoritative content is TipTap document JSON,
in which offset OT is invalid, and the server never interprets content). **Do not reintroduce them** —
reasoning in `在线协作文档编辑系统.md`. `pattern/memento` and `pattern/observer` are the only pattern packages left.

### Client side (`frontend/src/collab/otClient.js`)

Convergence is a **rebase** ported from `prosemirror-collab`: lift our unacknowledged steps out of the
document using the inverse each step carries, apply incoming steps exactly as the log has them, then replay
ours on top through `mapping.slice(mapFrom)` with the mirror set. Every `pending` step stores its own inverse
(computed against `tr.docs[i]` at capture), which is why `addLocalSteps` takes the transaction, not its steps.
Mapping in both directions was tried and is not repairable — it double-shifts a batch and breaks same-position
ties in opposite orders; no trace of it is left.

- Each batch carries `inDocument`. A bootstrap parks batches whose steps are gone and `bootstrapNow` re-applies
  them onto the rebuilt document, re-deriving inverses, except those already folded. A parked batch is not
  liftable, so `rebaseOver` shifts its steps only over rows above the batch's `seen` watermark — revive them
  verbatim and they land at offsets from a document that no longer exists.
- Two cross-layer assumptions hold this up and neither is type-enforced: the unicast `ACK` leaves
  `WebSocketObserver` **before** the broadcast, and `catchUp` walks rows in increasing version. Break either
  and the lift sees steps it did not record.
- A step that can no longer be replayed on the way back in is dropped (as upstream). Incoming steps go through
  `tr.step`, so an unappliable **history row** still throws and reaches `haltAt` rather than being skipped.
- `DocEditor.vue`'s `onUpdate` submits the root transaction **and** TipTap's `appendedTransactions` (a change
  that never enters `pending` is content no peer receives). Appends of a *remote* transaction are deliberately
  not submitted, else trailing paragraphs multiply across the group — a judgement call about plugin-generated
  state, untested beyond the harness (which cannot produce those plugins).
- Commit bookkeeping is per batch, never per client: `acknowledge(version)` releases only the batch carrying
  that version. Frames run through one serialized promise chain (fan-out is after commit, so writers' frames
  arrive out of order); a version gap is closed by refetching `/operations?after=`. Any error rebuilds client
  state from the server — except an unappliable history row, which halts (below).
- Whole-document state is replaced only on `INIT`, `RESET`, and the rebuild any other error triggers, and always
  with `setContent(content, { emitUpdate: false })` — a plain `setContent` echoes the document back as a local step batch.

**Use `tr.step`, never `tr.addStep`.** `addStep` is `@internal` and its second parameter is the resulting
document; called with one argument it produces `doc = undefined` and ProseMirror then fails resolving the
selection — every remote path breaks. Public API: `tr.step` (throws `TransformError` if it won't apply),
`tr.maybeStep` (skips), `tr.addStep` (never).

**An unappliable history row halts the client, on purpose.** Any writer can commit step JSON no reader can
apply (the server never interprets content), and retrying just re-fails the same row forever. `catchUp` records
`haltedAt` and stops. While halted, `flush` / `handleSteps` / `handleAck` / `handleReject` / `uploadCheckpoint`
all refuse (a halted tab must not describe a document it cannot reproduce, and must not fold away rows it
skipped); `bootstrapNow` returns before reviving parked batches. `handleReset` is the exception — a reset
replaces the history that caused the halt, so it clears the flag, guarded by `frame.version < version` so a
late reset can't downgrade the version or lift a newer halt. Otherwise the halt is **sticky for the client's
life**; if the log is repaired underneath it, a reload is the way back. Rejected alternatives: falling back to
the last checkpoint throws away replayable history; making the server validate applicability drags content
interpretation into the sequencer — the one principle this design rests on.

Current known defects (open, `expectedToFail` in the harness): with block edits in the workload one random
config converges but two do not — a writer ends up off the log and halts refusing a whole-document
`replaceAround` row; the minimal scripted counterpart converges, so it lives in a longer chain, not in
`ReplaceAroundStep` mapping as such. Cause not established. Also unverified: undo across a peer edit (below),
and nothing here exercises the real HTTP or WebSocket layer.

### Observer wiring and the collab bus

Nothing sends frames through `WebSocketSessionManager` directly. `CollabBus` is the single entry point for every
outbound frame; `LocalDelivery` (implemented by `WebSocketSessionManager`) is the callback the active bus uses
to write into the sockets *this* process holds. Bus owns fan-out, session map owns delivery — that is what keeps
multi-instance out of the handlers.

- `LocalCollabBus` (`app.collab.bus=memory`, default) delivers straight to `LocalDelivery`.
- `RedisCollabBus` (`COLLAB_BUS=redis`) publishes a JSON `FrameEnvelope` on `collab:frames`; every node
  subscribes and delivers locally, and the publishing node takes the same path (one delivery path, so the
  origin-session exclusion lives in exactly one place). Membership: `collab:doc:{id}` → `{sessionId: nodeId}`
  plus a `collab:node:{nodeId}` lease a 10s heartbeat renews.
- The heartbeat renews the lease **first**, then makes one time-budgeted pass issuing at most one `HSET` and one
  varargs `HDEL` per document. Both details are load-bearing (a stalled Redis costs a read timeout per call, and
  a node with ~150 documents would otherwise blow the 30s lease). A close records its removal intent even on a
  successful `HDEL`, or a sweep that snapshotted beforehand would re-add a field no one else prunes. When the
  budget cuts the pass short, **the next tick starts where this one stopped** (visit order is rebuilt
  identically, so a fixed start would starve the tail forever, and after a lapsed lease only this sweep can
  restore the node's own members).
- `LocalDelivery.forEachLiveSession` reports **every** registered session with an `open` flag rather than
  filtering closed ones: registered-but-not-open is the only signal that finds a ghost socket whose
  `afterConnectionClosed` never ran, and the registry drops it as it reports it. (A half-open socket still
  reports `isOpen()` true and is re-asserted forever.)

`DocumentSubjectImpl` keeps one observer per document (attach on arrival, detach when the last session leaves).
`notifyAllObservers` also publishes when this node holds **no** viewer: a rename/restore arrives over HTTP on
whichever node the LB picked, and dropping it there would starve clients connected elsewhere.

`WebSocketObserver.update` unicasts the `ACK` **before** broadcasting — the unicast is the sender's permission to
release its next batch and cannot fail over the network, so it must never sit behind a call that can. A
**`CollabBus` implementation never throws** (its callers are post-commit fan-out and socket lifecycle, where an
exception costs a 5xx for a committed write or a healthy connection); `RedisCollabBus` degrades instead. Losing
a frame is recoverable because every one carries the version, and the client refetches the gap over HTTP.
`DocumentService` publishes `ContentAppliedEvent` rather than notifying observers; `notifyAllObservers` is a
`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` so nothing reaches clients before commit.
`afterCommit` runs on the committing thread and does not order frames across writers — hence the version in every frame.

The document id is a **canonical string of the numeric id** on every side of that wiring: the socket path
segment is free text, so `DocumentWebSocketHandler` normalizes `/ws/document/007` to `"7"` before keying the
participant, session map and observer. Keys derived any other way silently never match — the symptom is a tab
that stops syncing with no log line.

`WebSocketSessionManager.register` wraps sessions in `ConcurrentWebSocketSessionDecorator`; send through the
decorated session, never the raw one (concurrent broadcasts interleave). `deliver` catches `RuntimeException` as
well as `IOException` — the decorator throws unchecked when a slow client crosses its send/buffer limit, and an
escaping exception would skip every remaining session in the loop.

Multi-instance status: **code complete, not runtime-verified — but the mode boots.** Two nodes behind a broker
were never exercised because starting Docker Desktop on this machine makes the campus network refuse
authentication (it detects the `vEthernet (WSL)` adapter). `RedisBusModeTest` starts the whole context with
`app.collab.bus=redis` pointed at a dead port and asserts the redis bus is wired and every call degrades rather
than throws. That test found a real deployment bug: `RedisMessageListenerContainer` is a `SmartLifecycle` bean
with no auto-startup switch, so an unreachable Redis failed `start()` during refresh and **the app never came
up**. `CollabFrameListener` now owns the container and subscribes from a retrying virtual thread, so the node
comes up degraded, says so, and catches up when Redis answers (the log line distinguishes "couldn't reach the
network" from "container skipped `afterPropertiesSet()`"). Exactly **one** container may be adopted per
lifecycle — a second would hand every frame to the local sockets twice — so an attempt keeps its own only by
winning `compareAndSet(null, candidate)`, a superseded attempt releases its own, and a generation counter stops
an interrupted thread retrying behind the next `start()` (`stop()` interrupts rather than joins). The CAS and
release-after-adoption are reasoned, not exercised (they need a live Redis); the generation guard and interrupt
are covered by `CollabFrameListenerTest`. The window before the first subscription is a real limit: the node is
write-only, an idle reader can sit on stale content with a plausible `onlineCount`, and the usual gap-refetch
does not cover it — which is why the retry is loud and unbounded.

### WebSocket frames

Server → client: `INIT` (title, content, contentFormat, version, checkpointVersion, onlineCount, permission),
`STEPS`, `ACK` (with `checkpointRequested`), `REJECT`, `RESET`, `TITLE_UPDATE`, `CURSOR_UPDATE`,
`NOTIFICATION`, `USER_JOINED`, `USER_LEFT`. Client → server: `STEP_BATCH`, `CURSOR`. The document id always
comes from the connection path, never from a frame payload.

`INIT` returns the newest **checkpoint** content plus `checkpointVersion`, usually *below* `version`; a client
must then replay `GET /{id}/operations?after=checkpointVersion` (`DocumentClient.bootstrap`) — the only way a
joiner, resync or restart sees text committed as steps, since the server never interprets content.

Frames are serialized with the **Spring-managed** `ObjectMapper` (injected into `WebSocketSessionManager`). A
bare `new ObjectMapper()` drops `LocalDateTime` fields — `serialize()` returns null and the frame vanishes with
one log line. `USER_JOINED`, `USER_LEFT` and `STEPS` all carry `onlineCount`; a frame that omits it leaves the
badge stale, because `DocEditor.vue` only updates it when the field is present.

Handshake refusals: no valid `?token=` is refused with HTTP 401 **before** the upgrade (`JwtHandshakeInterceptor`),
so the browser never sees a close code. Once upgraded the handler uses `4001` (no access, or the account was
removed/disabled mid-session) and `4004` (malformed); the client treats 4xxx as terminal and does not retry.

### History and retention

`operation_log` is the replayable history; `document_snapshot` holds checkpoints. Every `CHECKPOINT_EVERY`
(200) committed versions the server asks the acknowledged client, via `checkpointRequested` on its `ACK`, to
upload full document JSON to `POST /api/documents/{id}/checkpoint`. Recording a checkpoint folds the log
(operations at or below it are deleted) and `MementoCaretaker` keeps at most 50 snapshots per document.
`restoreVersion` reads a snapshot and writes it forward as a new version, broadcasting `RESET`.

Editor undo/redo is TipTap/ProseMirror history, per user by construction — **not** automatically safe across a
peer edit. `prosemirror-history` can only rebase its own stored steps if told a rebase happened, so the collab
layer owes it two signals: a plugin declaring `historyPreserveItems` (`frontend/src/collab/collabHistory.js`,
registered in `DocEditor`), and `setMeta("rebased", n)` on the lifting transaction (n = number of lifted steps,
which must be the first n steps of the transform). The harness pins both the value and the mirror table.
**Still owed: a browser run of `undo` across a peer edit through the real TipTap editor** — a Node attempt did
not discriminate, so shipping it would have been a fake green.

## Auth & authorization

Stateless JWT bearer. `POST /api/users/login` / `/register` return `{ id, username, userCode, role, token,
expiresIn }`; everything else is authenticated.

- Identity comes from the **principal**, never from request data: controllers take `@AuthenticationPrincipal
  AuthUser caller`; there is no caller-assertable `?userId=` / body `userId` / `X-User-Id`. A body `userId`
  appears only where it names the *target* (who a doc is shared with, whose account an admin edits).
- `JwtAuthFilter` re-reads the account on **every** request, so disable/demote takes effect immediately; the
  token only proves who is calling. The role in a token is not the role in effect — the authority is rebuilt
  from the DB row, so a token claiming `ADMIN` gets 403 until the row says so. An unknown username still costs
  one bcrypt against a throwaway digest, so login timing does not leak existence.
- A browser cannot send an `Authorization` header on a WebSocket, so the socket token travels as `?token=`;
  `JwtHandshakeInterceptor` turns it into a session attribute before the handler runs (and sets `Cache-Control:
  no-store`). **Never enable `org.springframework.web.socket` at `DEBUG`** — handshake logging prints the full
  session URI, token included.
- Public: `/api/users/login`, `/api/users/register`, `/ws/**`, `/actuator/health`. Everything else requires
  auth; `/api/admin/**` also requires `ROLE_ADMIN` via `@PreAuthorize`.
- Passwords are BCrypt with transparent upgrade (a stored bare Base64 SHA-256 or `sha256:`-prefixed digest
  still authenticates and is rewritten `$2a$…` on success). `User.enabled` is enforced at login (403) and on
  every request (401). Admins cannot disable/demote/delete their own account. No code promotes anyone to
  `ADMIN`; the first admin must be set directly in the DB.
- `JwtService` fails startup if `app.jwt.secret` is under 32 chars; `application.yml` has a clearly-labelled
  dev fallback — set a real secret anywhere else. Tokens are not refreshed or individually revoked; TTL
  (`app.jwt.ttl`, 8h) plus the enabled/role re-check is the invalidation mechanism.

`READ_ONLY` is enforced server-side (writes, `STEP_BATCH` and rename throw `ForbiddenException`, which closes
the socket) and mirrored client-side (`INIT.permission` drives `editor.setEditable(false)` and the toolbar).

`AuthorizationMatrixTest` is the regression net for all of the above: it drives the **real filter chain** via
MockMvc, covering which routes are anonymous, that every caller-supplied identity field is decoration while the
principal decides, that disable/demote takes effect next request, and that the socket handshake refuses a
missing/garbage/disabled token. Add to it rather than re-verifying by hand.

Remaining known holes: no rate limiting/lockout on login; no CSRF concern only because the API is
token-in-header and fully JSON — do not add cookie/session auth without revisiting that.

## REST surface

- `/api/users` — `POST /register`, `POST /login` (both return a token), `GET /lookup?userCode=` (8-digit share handle, authenticated).
- `/api/documents` — `POST` (owner = caller), `GET /{id}`, `GET /search?keyword=`, `GET /me`, `GET /shared`
  (both `DocumentSummary`: no body; shared list carries `permission`), `PUT /{id}/title`, `PUT /{id}`
  (whole-document write, `409` on stale `version`), `POST /{id}/checkpoint`, `DELETE /{id}`,
  `GET /{id}/operations?after=`, `GET /{id}/history`, `POST /{id}/restore/{version}`, `POST /{id}/share`
  (body `userId` = target), `GET /{id}/shares`, `DELETE /{id}/share/{userId}`. None take a caller id.
- `/api/notifications` — `GET`, `GET /unread-count`, `POST /read`; always the caller's own inbox.
- `/api/admin/users` — list (paged, keyword), detail, `PUT /{id}/role`, `PUT /{id}/enabled`,
  `PUT /{id}/password` (returns a generated one-time password), `DELETE /{id}` (cascades documents, shares, logs).
- There are **no** `/undo` or `/redo` endpoints — the old ones were document-global and reverted other users' edits.

## Conventions

- Plain POJOs with hand-written getters/setters; Lombok is a declared dependency but unused — do not introduce
  annotations the codebase avoids. Java records are fine for private value types inside a class
  (`DocumentWebSocketHandler.Participant`).
- Status mapping lives in **one** place: `GlobalExceptionHandler` (`@RestControllerAdvice`) maps
  `InvalidCredentials`→401, `NotFound`→404, `Forbidden|AccessDenied`→403, `Conflict`→409 (+ current version),
  `IllegalArgument|IllegalState`/bad request params→400. No controller maps an exception to a status; leftover
  `try/catch` only converts a malformed field to `IllegalArgumentException`. Its catch-all `Exception` handler
  also maps Spring MVC's own signals (unknown path/wrong verb/`HttpMessageNotReadable`/etc. → 404/405/415, not
  500); `MethodArgumentTypeMismatchException` is deliberately in the 400 clause, not the catch-all.
  `@ExceptionHandler` can't list the `ErrorResponse` interface, so concrete types are enumerated and status read
  per exception; `badRequest` takes `Throwable` so the body keeps this project's shape, not Boot's.
- Services throw those exceptions with human-readable messages.
- Success/error strings are English on the backend, Chinese in the UI — keep that split.
- Constructor injection everywhere on the backend; no field `@Autowired`.
- `DocumentState` is the shared read model for state-changing document calls (carries `contentFormat`); extend
  it rather than inventing a response shape.
- Frontend state is per-view refs plus singletons (`services/api`, `services/websocket`,
  `services/notifications`). Routes are in `src/router/index.js` with **hash** history (`/#/doc/5` deep-links
  and survives a static host); `App.vue` gates on `currentUser`, not a navigation guard.
- `wsService.on(type, handler)` returns an unsubscribe fn and stores handlers per type as arrays — always
  unsubscribe on `onBeforeUnmount`, or a leaked handler from a previous document keeps receiving frames.

## Verifying changes

Start the backend with the `dev` profile and `npm run dev`, open the same document in two browser tabs as two
different users (share it `READ_WRITE` first). Watch for:

- Exactly one `STEPS` broadcast per accepted batch, and no `STEPS` returning to its own sender (DevTools → the
  socket's Messages tab, filter `STEPS`).
- `USER_LEFT` arrives **with** `onlineCount` and the number drops when a tab closes.
- Typing in both tabs at once: one batch acked, the other `REJECT`ed, both converge on the same text with no
  caret jumps. **Still untested: a second real editor peer** — everything so far was one browser tab plus one
  scripted writer, so nobody has watched two ProseMirror views land on the same positions.
- The retention loop: past `CHECKPOINT_EVERY` (200) is when the `folded` band first exists — fill to v199 with a
  scripted writer, open in the tab (replays 199 rows), type one char so the client's own ack carries
  `checkpointRequested` and it uploads. The batch must be recognised as **folded**, not re-applied and resent
  (verified 2026-09-23; mutation-checked — neutralising `folded` re-applied the batch *and* appended a duplicate
  row, i.e. permanent shared corruption, which is what makes this guard worth its complexity).
- `SELECT document_id, version, COUNT(*) FROM operation_log GROUP BY 1,2 HAVING COUNT(*) > 1` → empty; same for
  `document_snapshot`.
- Restarting the backend keeps history (`GET /{id}/history`); restoring a version makes `version` go up, never down.
- Undo stacks and the in-memory session registry are JVM state, so restarts reset them — which is why two
  instances need `COLLAB_BUS=redis`.

The socket invariants check without a browser: with `dev` running, register two users over REST, create a
document as one, share it `READ_WRITE` to the other, open two `WebSocket` clients (Node 21+ has the global; the
Qoder Node REPL sandbox does **not**, so run `node -e`) at `ws://localhost:8080/ws/document/{id}?token=`. One
`STEP_BATCH` from A should produce `ACK` for A, exactly one `STEPS` for B, no `STEPS` back to A, and
`USER_LEFT(1)` for A when B closes. For a single-tab drill (drive the editor from `evaluate_script`, token under
the `collab-token` localStorage key, register needs `email`): the oracle is the reload — what the tab shows
before `location.reload()` must equal what it shows after, because reload rebuilds from checkpoint +
`/operations?after=`. That proves the replay agrees with this client's view; it does **not** prove two clients
agree, and cannot see content this client dropped and then laundered into the log by uploading a checkpoint.
