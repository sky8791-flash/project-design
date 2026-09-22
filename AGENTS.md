# AGENTS.md

Guidance for agents working in this repository.

## Repository layout

Two independent apps, no root build file — build each one separately.

- `backend/` — Spring Boot 3.2.5, Java 21, Maven (`backend/pom.xml`, artifact `online-collab-doc`).
- `frontend/` — Vue 3 + Vite 8 SPA, TipTap editor. No TypeScript, no router, no Pinia.
- `在线协作文档编辑系统.md`, `系统模式结构图.md` — design docs (Chinese): feature scope, pattern diagrams, ER diagram, deployment view. Read these before changing collaboration semantics.

Both `backend/target/` and `frontend/dist/` are build output and are untracked (root `.gitignore`).

## Commands

Backend (run from `backend/`):

```bash
mvn spring-boot:run                 # serves http://localhost:8080
mvn -q -DskipTests package          # jar in target/
mvn test                            # only src/test/ classes; currently there are none
```

Frontend (run from `frontend/`):

```bash
npm install
npm run dev                         # http://localhost:5173, proxies /api and /ws to :8080
npm run build && npm run preview
```

There is no lint, formatter or test-runner script configured.

Prerequisite: MySQL on `localhost:3306`, database `collabdoc`, credentials in
`backend/src/main/resources/application.yml` (plaintext dev password is committed — do not
treat that file as a secret store, and never add real credentials to it).
`spring.jpa.hibernate.ddl-auto: update` means entities, not `backend/src/main/resources/schema.sql`,
define the live schema. `schema.sql` is a reference document and already lags the entities
(no `user.user_code`, `user.role`, `user.enabled`) — keep the two in sync manually or update the doc.

Vite proxies `/api` and `/ws`, so the frontend uses relative URLs only; do not hardcode `:8080` in
`frontend/src/`.

## Architecture

Request path: `*Controller` → `service` → `repository` (Spring Data JPA) → MySQL.
Realtime path: `DocumentWebSocketHandler` (`/ws/document/{documentId}?userId=`) → `DocumentService`.

The collaboration core is four pieces wired together in `DocumentService.applyOperation`
(`backend/.../service/DocumentService.java:96`):

1. `ot/OperationalTransform` — INSERT/DELETE position transforms, replayed against an in-memory
   per-document history capped at `MAX_HISTORY_PER_DOC = 1000`.
2. `pattern/command` — `CommandInvoker` executes `InsertCommand`/`DeleteCommand` against a
   `StringBuilder` and keeps an undo index per document. `FormatCommand` exists but is never
   instantiated; add it to `DocumentService.createCommand` before relying on it.
3. `pattern/memento` — `MementoCaretaker` holds an in-memory history stack (cap 50) per document and
   persists every state as a `document_snapshot` row; `undo`/`redo`/`restoreVersion` read from it.
   `loadHistoryFromDatabase` repopulates the stack on demand.
4. `pattern/observer` — `DocumentSubjectImpl` keeps one `WebSocketObserver` per session, keyed by
   document id; notifying it broadcasts `CONTENT_UPDATE` through `WebSocketSessionManager`.

`document.version` is the single monotonic counter every state-returning method bumps. `onlineCount`
is derived from observer count, not from the session manager.

### WebSocket message protocol

Server → client: `INIT` (on connect), `CONTENT_UPDATE`, `CURSOR_UPDATE`, `USER_LEFT`.
Client → server: `EDIT`, `CURSOR` (dispatched in `handleTextMessage`).

Caveat that shapes all editing work: the frontend currently sends only `CURSOR` over the socket.
Edits are saved as **whole-document HTML** via `PUT /api/documents/{id}` (1s debounce in
`DocEditor.vue`), so the `EDIT`/OT path is exercised by nothing in the running app — last write
wins, and concurrent typers overwrite each other. Implement or restore granular ops in
`DocEditor.vue` + `DocumentWebSocketHandler.handleEdit` together.

### Auth & authorization

There is no session, JWT or Spring Security. The client asserts identity: `?userId=` query params
on document endpoints and an `X-User-Id` header on `/api/admin/**` (injected by the axios interceptor
in `frontend/src/services/api.js` from `localStorage`). `DocumentService.hasAccess` /
`hasWriteAccess` (owner or `document_share.permission == READ_WRITE`) and `AdminService.verifyAdmin`
are the only gates. Anything added here inherits that model — treat it as a trust boundary only
against accidental misuse, not against a hostile client.

Passwords are unsalted SHA-256 (`UserService.hashPassword`). Roles are `User.Role{ADMIN, USER}` and
`enabled` is a plain flag: `authenticate` does **not** check it, so disabled users can still sign in —
enforce it in `UserService.authenticate` if the flag is meant to be authoritative. No code promotes a
user to ADMIN; the first admin must be set directly in the database.

### REST surface

- `/api/users` — `POST /register`, `POST /login`, `GET /lookup?userCode=` (8-digit share handle).
- `/api/documents` — CRUD plus `/user/{id}`, `/shared/{id}`, `/{id}/undo`, `/{id}/redo`,
  `/{id}/history`, `/{id}/restore/{version}`, `/{id}/share`, `/{id}/shares`, `DELETE /{id}/share/{userId}`.
- `/api/admin/users` — list (paged, keyword), detail, `PUT /{id}/role`, `PUT /{id}/enabled`,
  `PUT /{id}/password` (returns a generated one-time password), `DELETE /{id}`.

## Conventions

- Plain POJOs with hand-written getters/setters; Lombok is a declared dependency but unused — do not
  introduce annotations that the rest of the codebase avoids.
- Controllers return `ResponseEntity` and wrap service calls in `try/catch (RuntimeException e)`,
  converting to `400`/`403` with a `Map.of("error", message)` body. Services throw `RuntimeException`
  with a human-readable message; there is no `@ControllerAdvice` or custom exception hierarchy.
- Success/error strings are English on the backend, Chinese in the UI (`LoginView`, `HomeView`,
  `AdminView`, `DocEditor`). Keep that split.
- Constructor injection everywhere on the backend; no field `@Autowired`.
- Frontend state is per-view refs plus two singletons (`services/api`, `services/websocket`); view
  switching is `v-if` branching in `App.vue`, so there is no route to deep-link.
- `DocumentState` is the shared read model returned by every state-changing document call; extend it
  rather than inventing a new response shape.

## Verifying changes

Backend and frontend must be started separately. Two browser tabs on the same document is the
fastest check for realtime sync, cursor broadcast, share permissions and history restore.
Undo/redo and OT history live in JVM memory, so restarts and multi-instance deployments reset them —
test those flows against a freshly started single instance.
