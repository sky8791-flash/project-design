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
mvn test                                             # 60 tests on in-memory H2, no MySQL needed
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

A test that sleeps a fixed window and then counts is a test that depends on which classes were loaded before
it: `CollabFrameListenerTest`'s first version was green inside `mvn test` and red on
`mvn -Dtest=CollabFrameListenerTest`, because the first attempt costs a few hundred cold milliseconds and only
fits a 150 ms window once some earlier `@SpringBootTest` has warmed Spring Data Redis, Lettuce and Mockito.
Wait on a predicate over the state under test (`await(() -> ..., "...")` there) rather than on elapsed time,
and mutate the branch before believing a thread-safety test is green. A log appender a test attaches at class
start can also be dropped under it — Boot re-initialises Logback around the context load, and the reset takes
the appender with it, so `RedisBusModeTest` re-attaches on every poll.

Frontend (run from `frontend/`):

```bash
npm install
npm run dev          # http://localhost:5173, proxies /api and /ws to :8080
npm run build && npm run preview
npm run check:collab # convergence harness over the real otClient.js — plain Node, no server, ~3s
```

There is no lint or formatter, and no general test runner: `npm run build` is the only automated check over the
whole frontend and must be run after any edit. `scripts/collab-convergence.mjs` is the exception — it imports
`src/collab/otClient.js` directly, drives it against an in-process sequencer that implements the documented
server contract, and asserts convergence over eight random configurations (405 rounds) plus
the scripted orderings that used to be bugs. Its oracle is not "the tabs agree with each other" but "each tab
equals an independent replay of the operation log", and a run only counts as converged when it needed **no**
whole-document refetch — otherwise the recovery path passed and the protocol did not. Nothing runs it
automatically. Exit 0 means all 21 checks are green; the file also carries a known-defect mechanism — a
scenario flagged `expectedToFail` fails the build if it ever passes, so a marker cannot outlive its fix.

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
Checked read-only against `collabdoc_dev` on 2026-09-23: `uk_oplog_doc_version`, `uk_snapshot_doc_version` and
`uk_doc_user` all exist, `document` has only its primary key (no secondary index by design), and
`document.revision`/`document.content_format`/`operation_log.command_params JSON` are present — the live
database, the entities and `schema.sql` agree, so invariant I5 is enforced by MySQL and not merely unobserved
to be violated. Nothing outside `collabdoc_dev` was touched; `collabdoc` and `dabashou` hold real data.

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

Convergence lives here, and it is a **rebase**, ported from `prosemirror-collab`: lift our unacknowledged steps
out of the document using the inverse each one carries, apply the incoming steps exactly as the log has them,
then replay ours on top through `mapping.slice(mapFrom)` with the mirror set. Every step in `pending` therefore
stores its own inverse, computed against `tr.docs[i]` at capture time — which is why `addLocalSteps` takes the
transaction, not its steps.

Mapping in both directions is what that replaced, and it was not repairable by tuning: a batch's steps are
successive relative to one another, so one accumulated mapping shifts them twice. Two writers at one position
reached the same version with `abXY` on one tab and `abYX` on the other (log `X@3` then `Y@4`), and a folded
multi-step batch could submit a step that does not apply to the document the server sequenced it against —
which makes the operation log itself unreplayable for every reader, the same terminal condition as a
deliberately poisoned row, produced by honest clients with no attacker. An intermediate attempt that kept the
double mapping and forced `assoc = -1` for incoming insertions fixed the visible tie and quietly widened peer
deletes and marks across the local user's uncommitted character instead; that is also why upstream's
`ReplaceStep.MAP_BIAS` is gated on `from == to`. No trace of that approach is left in the code.

Each batch still carries `inDocument`: a bootstrap parks the batches whose steps are no longer in the document
and `bootstrapNow` re-applies them onto the rebuilt one, re-deriving their inverses, except those the
checkpoint has already folded. A parked batch is not liftable, so `rebaseOver` shifts its steps over every
replayed row that sits above its own `base` instead — revive them verbatim and they land at offsets from a
document that no longer exists, which is then submitted and becomes everyone's history. A batch therefore
carries a `seen` watermark — the log version its steps are already relative to — and parked steps are shifted
only over rows above that watermark, because a rebuild replays rows a rebase had already folded in and shifting
them twice walks the batch off the end of the document (which throws on revive and halts the tab over perfectly
good history).
Two cross-layer assumptions hold that up, and neither is enforced by a type: the unicast `ACK` leaves
`WebSocketObserver` **before** the broadcast, and `catchUp` walks rows in increasing version, so no incoming
step is ever computed against content this client has committed but not yet acknowledged. Break either one and
the lift starts seeing steps it did not record.
A step that can no longer be replayed on the way back in is dropped, as upstream does; the
incoming steps go through `tr.step`, so an unappliable *history row* still throws and reaches `haltAt` rather
than being skipped.

`DocEditor.vue`'s `onUpdate` submits the root transaction **and** TipTap's `appendedTransactions`, because
StarterKit's trailing-node plugin adds its paragraph that way — a document change that never enters `pending`
is content no peer receives, and the next rebase throws because there is nothing to lift. Appends of a
*remote* transaction are deliberately not submitted: every tab runs the same plugin locally, so submitting
them would multiply trailing paragraphs across the group. That is a judgement call about plugin-generated
state, not a proven-safe one — it is untested beyond the harness, and the harness schema cannot produce those
plugins at all. Commit bookkeeping is per batch, never per client: the server commits an accepted batch at
exactly `base + 1`, so `acknowledge(version)` releases only the batch carrying that version — a "we saw one
of our own rows" flag spans everything above the checkpoint (up to `CHECKPOINT_EVERY` versions of older
work) and loses or duplicates the batch the user is waiting on. Frames are handled through one
serialized promise chain because the server fans frames out after commit, so two writers' frames can
arrive out of order; a version gap is closed by refetching `GET /api/documents/{id}/operations?after=`.
Any error in that chain rebuilds the whole client state from the server — except a history row that cannot
be applied, which halts instead (see the next paragraph).

`npm run check:collab` is what these choices are tested against: 21 scenarios, all green. The eight random
configurations additionally fail the run if any client needed a whole-document refetch, so their convergence is
the protocol's and not the recovery path's; most scripted blocks assert the log replay and the drained queue
but not the refetch count, and one (the unappliable row) rebuilds on purpose before it halts. What stays open is
the coverage itself: the schema is hand-built
(`doc/paragraph/text` + `bold/italic`), so `ReplaceAroundStep` — which every real list, blockquote or code block
produces — never passes through `rebaseOver`, and nothing here exercises the real HTTP or WebSocket layer.

Whole-document state is only ever replaced on `INIT`, `RESET`, and the rebuild that any other error triggers, and always with
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
  `@Scheduled` heartbeat renews. The tick renews the lease **first**, then makes one pass over the documents
  it holds members in or owes removals for, issuing at most one `HSET` (its live sessions, minus the removal
  intents it owes) and one varargs `HDEL` (those intents) per document — every call independently guarded.
  Both details are load-bearing. Closing records the removal intent even when its `HDEL` succeeded, because
  a sweep that snapshotted the session beforehand would otherwise re-add it and a field owned by a live node
  is never pruned by anyone. And the pass shares a 5s wall-clock budget — which is why renewal comes first:
  a stalled Redis costs a read timeout per call, and a node holding ~150 documents would otherwise spend
  longer between renewals than the 30s lease lasts, watching peers prune members of a live node. Because the
  budget (or its cap of 8 failing documents) can cut the pass short, the next tick **starts where this one
  stopped**; the visit order is rebuilt identically every tick, so a fixed start would starve the same tail
  forever, and after a lapsed lease only this sweep can restore this node's own members.
  `LocalDelivery.forEachLiveSession` reports **every** registered session with an `open` flag instead of
  filtering closed ones out, precisely because a socket whose `afterConnectionClosed` never ran is never
  announced to `sessionLeft`: registered-minus-open is the only signal that can discover such a ghost, and the
  registry drops the entry as it reports it so the next tick does not re-drop it, and only the redis bus ever
  calls it. Scope note: this covers a close the container noticed; a half-open socket still reports
  `isOpen()` true and is re-asserted forever.

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

Multi-instance status: **code complete, not runtime-verified — but the mode now at least boots.** Two nodes
behind a broker were never exercised because starting Docker Desktop on this machine makes the campus network
refuse authentication (it detects the `vEthernet (WSL)` adapter). `RedisBusModeTest` covers what needs no
server: it starts the whole application context with `app.collab.bus=redis` pointed at a port nothing
listens on, asserts the redis bus is the one wired, and asserts every call degrades instead of throwing.
That test found a real deployment bug: `RedisMessageListenerContainer` is a `SmartLifecycle` bean with no
auto-startup switch, so an unreachable Redis failed `start()` during context refresh and **the application
never came up at all** — a node that booted a moment before its Redis, or restarted while Redis was down, was
simply gone. `CollabFrameListener` now owns the container and subscribes from a retrying virtual thread, so
the node comes up degraded, says so, and catches up when Redis answers — and the log line is asserted, because
"Unable to connect to Redis" means it got as far as the network while "Subscriber not created" would mean the
hand-built container skipped `afterPropertiesSet()` and would fail forever with Redis perfectly healthy
(that counterfactual is not a guess — deleting the call turns `theListenerBlamesTheConnectionRatherThanItsOwnSetup`
red on exactly that text).
Exactly **one** container may be adopted per lifecycle: a second subscribed container hands every frame to the
local sockets twice, so the attempt thread keeps the one it built only by winning
`container.compareAndSet(null, candidate)`, a superseded attempt releases its own instead of orphaning it, and
a generation counter stops an interrupted thread from retrying behind the next `start()` (`stop()` interrupts
rather than joins, because the wait between attempts can be the full retry cap). Of those three, the CAS and
the release-after-adoption are **reasoned, not exercised** — reaching them needs an attempt that succeeds, i.e.
a Redis — while the generation guard and the interrupt are both covered by `CollabFrameListenerTest`, each
verified by mutation.

The window before the first successful subscription is a real limit worth stating: the node publishes but
receives nothing, so it is write-only, and the usual recovery does not cover it — a client attached to that node
never sees a gap to refetch if no frame arrives at all, and an idle reader can sit on stale content with a
plausible `onlineCount` badge until the subscription lands. Presence is unaffected, since the heartbeat uses the
command connection rather than the subscriber. That is why the retry is loud and unbounded rather than a
give-up-after-N.

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
construction. It is *not* automatically safe across a peer edit: `prosemirror-history` can only rebase its own
stored steps if it is told a rebase happened, so the collab layer owes it two signals — a plugin in the state
declaring `historyPreserveItems` (`frontend/src/collab/collabHistory.js`, registered in `DocEditor`'s extension
list; without it history collapses the items between events and `Branch.rebased`'s mirror walk is meaningless),
and `setMeta("rebased", n)` on the transaction that lifts and replays local work, where `n` is the number of
lifted steps and those lifts must be the first `n` steps of the transform. `npm run check:collab` asserts the
second one; the first is a one-line spec flag whose consumer is upstream.

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
that.

**The step-application defect (found and fixed 2026-09-23, after many rounds of being misattributed).**
`applySteps` built its transaction with `tr.addStep(step)`. In the installed ProseMirror that method is
`@internal` and its **second parameter is the resulting document** —
`Transform.addStep(step, doc) { this.docs.push(this.doc); this.steps.push(step); this.mapping.appendMap(step.getMap()); this.doc = doc }`
— so called with one argument every transaction it produced carried `doc = undefined`, and the next thing
ProseMirror does is resolve the selection against `tr.doc`. Hence `Cannot read properties of undefined
(reading 'resolve')`. Consequence, and it is much worse than a wedge: **no remote step ever reached the
document**, so joining or reloading any document whose text lives only in `operation_log` rendered *empty*,
and the crash the earlier reports blamed on out-of-order frames was this same line failing inside `catchUp`.
The fix is `tr.step(step)` — the public wrapper that applies the step and passes `result.doc` to `addStep`.
The public API to remember: `tr.step` (throws `TransformError` if the step does not apply), `tr.maybeStep`
(skips it), `tr.addStep` (never — the caller would have to compute the document itself).

**An unappliable history row halts the client, on purpose.** Because the server orders step JSON it never
interprets, *any* writer can commit a batch no reader can apply — `from: 999` on a five-character document is
accepted and sequenced like anything else. Retrying that is not a delay but a loop: the rebuild replays the
same row and fails the same way, and the tab never becomes usable. `catchUp` therefore catches the failure,
records `haltedAt` and stops. While halted: `flush` refuses, `handleSteps`/`handleAck`/`handleReject` refuse
(both after the `catchUp` await — the case review caught was `handleSteps` continuing past a halt, applying
the triggering frame and adopting its version, which would let the next checkpoint this tab uploads fold away
the rows it skipped), `uploadCheckpoint` refuses (a halted tab must not describe a document it cannot
reproduce), and `bootstrapNow` returns before reviving parked batches onto a half-rebuilt document.
`handleReset` is the deliberate exception: a reset replaces the history that caused the halt, so it clears
the flag — guarded by `frame.version < version` so a late, out-of-order reset cannot downgrade the version or
lift a halt belonging to newer history. The halt is otherwise **sticky for the life of the client**; if the
log is repaired underneath it, a reload is the way back (a fresh client re-checks). The user sees
`无法重放 v{n} 之后的历史，内容停在 v{m}；这里的后续编辑不会被保存，刷新页面可重试`.

Rejected alternatives: falling back to the last checkpoint throws away replayable history and still needs a
human; making the server validate applicability drags content interpretation back into the sequencer, the one
principle this design rests on. Reproduce: commit `GOOD` at position 1, then `{stepType:'replace',from:999,
to:999,...}`, then anything else, and open the document. Observed 2026-09-23: content `GOOD`, one console
error (`RangeError: Position 999 out of range`), **no** rebuild-retry line, message visible; while halted a
further row committed by another client (v4) was ignored — text unchanged, no version adopted; after
`POST /{id}/restore/{version}` the message cleared and typing reached the server (v5). Both guards were then
probed directly, without any whole-document write: dispatching `beforeunload` on a halted tab records nothing
(`checkpointVersion` still 0 and rows 1..3 unfolded, identical to a control document that never saw the probe),
and injecting a frame into the socket's own `onmessage` shows the reset guard biting both ways — `RESET
version 0` is ignored (text and halt unchanged) while `RESET version 9` applies, clears the halt and lets the
tab type again. That last run is also what turned the reject guard from a silent drop into a rebuild: a
rejection the client cannot attribute to its outstanding batch left the tab holding a version the server
never announced, unable to send or abandon it. Reaching that state needs a frame a real server will not
produce, so the rebuild fallback is reasoned rather than observed.

**Exercising these paths without a second browser tab** (the agent browser blocks popups; two real tabs as
two users, per *Verifying changes* below, remains the better test): log in, open `/#/doc/{id}`, and drive the
editor from `evaluate_script` — focus it,
set a caret range, then `document.execCommand('insertText', false, 'x')`; a synthetic `beforeinput` is
ignored. The token is under the `collab-token` localStorage key, and `POST /api/users/register` requires
`email` (without it the endpoint answers 500, not 400). Then commit from a second *real* client: a Node
`WebSocket` at `ws://localhost:8080/ws/document/{id}?token=…` sending
`{type:'STEP_BATCH', clientId, baseVersion: <the version from INIT>, docSize, steps:[{stepType:'replace',
from, to, slice:{content:[{type:'text',text:'x'}]}}]}`. The server sequences whatever step JSON it is handed,
so one browser plus one scripted writer is a genuine two-writer test. The oracle is the reload: what the tab
shows before `location.reload()` must equal what it shows after, because the reload rebuilds only from the
checkpoint plus `GET /{id}/operations?after=`. It proves the replay agrees with this client's live view; it
does **not** prove two clients agree, and it cannot see content this client dropped on the floor and then
laundered into the log by uploading a checkpoint.

Two cautions learned the hard way. Patching `WebSocket.prototype.send` to hold a batch leaves `outstanding`
pointing at a batch the server never saw — `sent` only means "handed to the transport" — so no `REJECT` will
ever rebase it and the held text legitimately disappears on the next replay; that is the harness, not the
protocol. And the repeated failure was never a corrupted editor: `view.dispatch` throws inside
`state.apply`, before any state is stored, so each arriving frame rebuilt its own broken transaction from a
clean state. The same `TypeError` in a log therefore means "every remote application path is failing", not
"one bad frame poisoned something" — read it as a property of `applySteps`, not of the frame that triggered it.

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
  (`NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, `HttpMediaTypeNotSupportedException`,
  `HttpMessageNotReadableException`, `ErrorResponseException`) — without those, an unknown path or a wrong
  verb becomes a 500 instead of 404/405/415.
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
  converge on the same text with no caret jumps. Exercised 2026-09-23 with the recipe above — one browser tab
  plus one scripted writer, **not** two editors: a peer's frame was made to arrive **before** the tab's own
  `ACK` — the ack is held back inside the page's socket handler — and the in-flight text survived
  (`AAABBB`, v2, no error), with the same string rebuilt after a remount; a stale-base batch was delivered,
  came back `REJECT`ed, was rebased and committed; text typed
  while the socket was down was parked by the reconnect bootstrap and revived exactly once; and remounting the
  editor rebuilt the identical text from `operation_log`. Still untested: a second real editor peer, so nobody
  has watched two ProseMirror views land on the same positions.
- The retention loop end to end, because `bootstrapNow`'s `folded` band only exists past `CHECKPOINT_EVERY`:
  fill a document to v199 with a scripted writer, open it in the tab (it replays 199 rows), type one char so
  the *client's own* ack carries `checkpointRequested` and it uploads the checkpoint. Then swallow one ack —
  wrap the live socket's `onmessage` after capturing the instance from a patched `send`, and drop the first
  `ACK` — so the client holds a committed-but-unacknowledged batch, `POST /{id}/checkpoint` at the current
  version to fold the rows away, and close the socket to make the same client bootstrap on reconnect. Expected
  and observed 2026-09-23: `checkpointVersion` 202, `0` rows left, text unchanged at 205 chars with the
  swallowed batch's text present exactly **once** and no error — the batch is recognised as folded rather than
  re-applied. Mutation-checked: with `folded` neutralised and everything else identical, the reconnect on a
  second document re-applied the batch (`…QZZ`, one character longer than the server's content) **and resent
  it**, so the server advanced 201 → 202 and a duplicate row was appended. The duplicate is not a local
  rendering glitch — it enters the shared history, which is what makes this guard worth its complexity.
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
