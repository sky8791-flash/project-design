package com.collabdoc.security;

import com.collabdoc.entity.Document;
import com.collabdoc.entity.DocumentShare;
import com.collabdoc.entity.User;
import com.collabdoc.repository.UserRepository;
import com.collabdoc.service.DocumentService;
import com.collabdoc.service.UserService;
import com.collabdoc.websocket.JwtHandshakeInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization boundary, exercised through the real filter chain rather than by calling a service:
 * who may reach a route at all, and which identity a handler ends up acting on. Where a caller can still
 * name a person in a request — the body {@code userId} on {@code /share}, the path id on an admin route —
 * that value is asserted to mean the *target*, never the caller.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthorizationMatrixTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentService documentService;
    @Autowired private JwtHandshakeInterceptor handshakeInterceptor;

    private User newUser() {
        String name = "authz-" + UUID.randomUUID().toString().substring(0, 8);
        return userService.createUser(name, name + "@test.local", "pw123456");
    }

    private String tokenOf(User user) {
        return jwtService.issue(user.getId(), user.getUsername(), user.getRole().name());
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    /** Reads a JSON body that is expected to be a 2xx; a non-2xx fails here rather than confusing a later assertion. */
    private JsonNode bodyOf(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isBetween(200, 299);
        return json.readTree(response.getContentAsString());
    }

    @Test
    void anonymousCallersNeverReachAController() throws Exception {
        mvc.perform(get("/api/documents/1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(get("/api/documents/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/documents").contentType("application/json").content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        // The chain runs before @PreAuthorize, so an anonymous admin probe is unauthenticated, not forbidden.
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginAndRegisterStayPublicAndAnswerFromTheController() throws Exception {
        User created = newUser();
        mvc.perform(post("/api/users/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("username", created.getUsername(),
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));

        String name = "public-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/users/register").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("username", name,
                                "email", name + "@test.local", "password", "pw123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void onlyHealthAndInfoAreExposedOnTheActuator() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        User admin = newUser();
        admin.setRole(User.Role.ADMIN);
        userRepository.save(admin);

        // Not 403/401 but 404: the endpoint is not published at all, so a token cannot read the environment.
        mvc.perform(bearer(get("/actuator/env"), tokenOf(admin))).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/actuator/heapdump"), tokenOf(admin))).andExpect(status().isNotFound());
    }

    @Test
    void springOwnRoutingErrorsKeepTheirStatusInsteadOfBecomingServerFaults() throws Exception {
        User user = newUser();
        String token = tokenOf(user);

        mvc.perform(bearer(get("/api/documents/not-a-number"), token)).andExpect(status().isBadRequest());
        mvc.perform(bearer(delete("/api/documents/me"), token)).andExpect(status().isBadRequest());
        mvc.perform(bearer(patch("/api/documents/1"), token)).andExpect(status().isMethodNotAllowed());
        mvc.perform(bearer(get("/api/documents/nope/nope/nope"), token)).andExpect(status().isNotFound());
        // The case that motivated the Throwable widening: MissingServletRequestParameterException is a
        // checked exception, so a handler parametered on RuntimeException never ran for it. The status was
        // 400 either way and Boot's default body also has an "error" key, so only the *message* proves the
        // advice answered: Boot would say "Bad Request", the advice says which parameter is missing.
        mvc.perform(bearer(get("/api/users/lookup"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("userCode")));
    }

    @Test
    void callerSuppliedIdentityFieldsChangeWhoIsAllowedButNotWhoIsActing() throws Exception {
        User owner = newUser();
        User stranger = newUser();
        Document doc = documentService.createDocument("private", owner.getId());

        String strangerToken = tokenOf(stranger);

        // The ?userId= below and above is inert by construction: no handler in DocumentController or
        // NotificationController binds a caller id parameter, so there is no check to delete here. What
        // these do guard is the day someone adds one — the 403 and the empty list both come from the
        // principal alone.
        mvc.perform(bearer(get("/api/documents/" + doc.getId() + "?userId=" + owner.getId()), strangerToken))
                .andExpect(status().isForbidden());

        // ...and the listing must come from the token, not from the parameter.
        JsonNode mine = bodyOf(bearer(get("/api/documents/me?userId=" + owner.getId()), strangerToken));
        assertThat(mine).isEmpty();

        JsonNode ownersList = bodyOf(bearer(get("/api/documents/me"), tokenOf(owner)));
        assertThat(ownersList.get(0).get("id").asText()).isEqualTo(String.valueOf(doc.getId()));

        // Same for the routes that used to take a caller id in the path: they no longer exist.
        // /user/1 is a 404 because nothing has that shape; DELETE /me is a 400 from @PathVariable Long id
        // rejecting "me" — @DeleteMapping("/{id}") matches both path and verb, so it is never a routing 405.
        mvc.perform(bearer(get("/api/documents/user/" + owner.getId()), strangerToken))
                .andExpect(status().isNotFound());
        mvc.perform(bearer(get("/api/documents/shared/" + owner.getId()), strangerToken))
                .andExpect(status().isNotFound());

        // The one surviving caller-supplied identity is the body userId on /share, and it names the
        // *target*. If it were ever read as the caller, sharing your own document with yourself would fail
        // with 400 ("Cannot share document with yourself") instead of landing on the stranger.
        mvc.perform(bearer(post("/api/documents/" + doc.getId() + "/share").contentType("application/json")
                        .content("{\"userId\":" + stranger.getId() + ",\"permission\":\"READ_ONLY\"}"),
                tokenOf(owner)))
                .andExpect(status().isOk());
        mvc.perform(bearer(get("/api/documents/" + doc.getId()), tokenOf(stranger)))
                .andExpect(status().isOk());
        assertThat(bodyOf(bearer(get("/api/documents/me"), tokenOf(owner)))
                .get(0).get("id").asText()).isEqualTo(String.valueOf(doc.getId()));
    }

    @Test
    void inboxIsAlwaysTheCallersOwn() throws Exception {
        User owner = newUser();
        User recipient = newUser();
        Document doc = documentService.createDocument("shared-doc", owner.getId());
        documentService.shareDocument(doc.getId(), recipient.getId(), owner.getId(),
                DocumentShare.Permission.READ_ONLY);

        JsonNode recipientInbox = bodyOf(bearer(get("/api/notifications"), tokenOf(recipient)));
        assertThat(recipientInbox).hasSize(1);
        assertThat(recipientInbox.get(0).get("userId").asLong()).isEqualTo(recipient.getId());

        // Asking for someone else's inbox by id returns your own, and marking read cannot reach sideways.
        JsonNode forged = bodyOf(bearer(get("/api/notifications?userId=" + recipient.getId()), tokenOf(owner)));
        assertThat(forged).isEmpty();
        mvc.perform(bearer(post("/api/notifications/read?userId=" + recipient.getId()), tokenOf(owner)))
                .andExpect(status().isOk());
        // Asserted through jsonPath because MissingNode.asBoolean() is false: a renamed field would pass a
        // plain asBoolean() check while proving nothing.
        mvc.perform(bearer(get("/api/notifications"), tokenOf(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(recipient.getId()))
                .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    void roleComesFromTheDatabaseAndNotFromTheTokenClaim() throws Exception {
        User user = newUser();
        // Correctly signed, but the role claim lies.
        String forgedAdminToken = jwtService.issue(user.getId(), user.getUsername(), User.Role.ADMIN.name());

        mvc.perform(bearer(get("/api/admin/users"), forgedAdminToken)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/documents/me"), forgedAdminToken)).andExpect(status().isOk());

        user.setRole(User.Role.ADMIN);
        userRepository.save(user);
        // The same token now works: the authority is re-read per request, so promotion needs no re-login.
        mvc.perform(bearer(get("/api/admin/users"), forgedAdminToken)).andExpect(status().isOk());

        user.setRole(User.Role.USER);
        userRepository.save(user);
        mvc.perform(bearer(get("/api/admin/users"), forgedAdminToken)).andExpect(status().isForbidden());
    }

    @Test
    void disablingAnAccountEndsItsTokensOnTheNextRequest() throws Exception {
        User user = newUser();
        String token = tokenOf(user);
        Document doc = documentService.createDocument("mine", user.getId());

        mvc.perform(bearer(get("/api/documents/me"), token)).andExpect(status().isOk());
        mvc.perform(bearer(get("/api/documents/" + doc.getId()), token)).andExpect(status().isOk());

        user.setEnabled(false);
        userRepository.save(user);

        mvc.perform(bearer(get("/api/documents/me"), token)).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/api/documents/" + doc.getId()), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedSignatureAndUnknownAccountAreBothRefused() throws Exception {
        User user = newUser();
        String token = tokenOf(user);
        // Corrupt the first character of the signature segment. The last one is tempting but unsound: for
        // a 32-byte HS256 signature the 43rd base64url character carries only four significant bits and
        // two ignored ones, so some tail-tampered tokens verify identically to the original.
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (first == 'a' ? 'b' : 'a')
                + token.substring(signatureStart + 1);

        mvc.perform(bearer(get("/api/documents/me"), tampered)).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/api/documents/me"), token.substring(0, token.length() - 1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/api/documents/me"), "not-a-jwt")).andExpect(status().isUnauthorized());

        // A well-formed token for an id the database does not know must not authenticate either.
        String ghost = jwtService.issue(9_999_999L, "ghost", User.Role.USER.name());
        mvc.perform(bearer(get("/api/documents/me"), ghost)).andExpect(status().isUnauthorized());
    }

    @Test
    void readOnlyShareeCanReadButCannotWriteAnything() throws Exception {
        User owner = newUser();
        User reader = newUser();
        Document doc = documentService.createDocument("read-only", owner.getId());
        documentService.appendStepBatch(doc.getId(), owner.getId(), 0, "owner", steps(), 1, "session-owner");
        documentService.shareDocument(doc.getId(), reader.getId(), owner.getId(),
                DocumentShare.Permission.READ_ONLY);

        String readerToken = tokenOf(reader);
        mvc.perform(bearer(get("/api/documents/" + doc.getId()), readerToken)).andExpect(status().isOk());
        mvc.perform(bearer(get("/api/documents/" + doc.getId() + "/history"), readerToken))
                .andExpect(status().isOk());

        Map<String, Object> save = Map.of("content", "<p>clobber</p>", "version", doc.getVersion() + 5);
        mvc.perform(bearer(put("/api/documents/" + doc.getId()).contentType("application/json")
                .content(json.writeValueAsString(save)), readerToken)).andExpect(status().isForbidden());
        mvc.perform(bearer(put("/api/documents/" + doc.getId() + "/title").contentType("application/json")
                .content("{\"title\":\"hijacked\"}"), readerToken)).andExpect(status().isForbidden());
        mvc.perform(bearer(delete("/api/documents/" + doc.getId()), readerToken))
                .andExpect(status().isForbidden());
        // Reaching a snapshot by number must be refused before the snapshot is even read.
        mvc.perform(bearer(post("/api/documents/" + doc.getId() + "/restore/1"), readerToken))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/documents/" + doc.getId() + "/share").contentType("application/json")
                .content("{\"userId\":" + owner.getId() + "}"), readerToken)).andExpect(status().isForbidden());

        assertThat(documentService.getDocumentState(doc.getId()).getTitle()).isEqualTo("read-only");
    }

    @Test
    void adminCannotLockHimselfOutOfTheConsole() throws Exception {
        User admin = newUser();
        admin.setRole(User.Role.ADMIN);
        userRepository.save(admin);
        String token = tokenOf(admin);

        mvc.perform(bearer(put("/api/admin/users/" + admin.getId() + "/enabled").contentType("application/json")
                .content("{\"enabled\":false}"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(put("/api/admin/users/" + admin.getId() + "/role").contentType("application/json")
                .content("{\"role\":\"USER\"}"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(delete("/api/admin/users/" + admin.getId()), token))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(admin.getId())).get().matches(User::isEnabled);
    }

    @Test
    void socketUpgradeNeedsAValidLiveToken() throws Exception {
        User user = newUser();
        WebSocketHandler handler = new TextWebSocketHandler();

        assertThat(handshakeInterceptor.beforeHandshake(request("/ws/document/1"),
                new ServletServerHttpResponse(new MockHttpServletResponse()), handler, new HashMap<>())).isFalse();
        assertThat(handshakeInterceptor.beforeHandshake(request("/ws/document/1?token=garbage"),
                new ServletServerHttpResponse(new MockHttpServletResponse()), handler, new HashMap<>())).isFalse();

        Map<String, Object> attributes = new HashMap<>();
        ServletServerHttpResponse ok = new ServletServerHttpResponse(new MockHttpServletResponse());
        assertThat(handshakeInterceptor.beforeHandshake(
                request("/ws/document/1?token=" + tokenOf(user) + "&userId=999999"), ok, handler, attributes))
                .isTrue();
        // The interceptor only ever reads the token; a userId riding along on the URL is ignored here and
        // never bound by the handler either (see callerSuppliedIdentityFields... for that guarantee).
        assertThat(((AuthUser) attributes.get(JwtHandshakeInterceptor.ATTRIBUTE_USER)).id())
                .isEqualTo(user.getId());
        assertThat(ok.getHeaders().getCacheControl()).contains("no-store");

        user.setEnabled(false);
        userRepository.save(user);
        Map<String, Object> stale = new HashMap<>();
        assertThat(handshakeInterceptor.beforeHandshake(request("/ws/document/1?token=" + tokenOf(user)),
                new ServletServerHttpResponse(new MockHttpServletResponse()), handler, stale)).isFalse();
        assertThat(stale).isEmpty();
    }

    /** Builds a handshake request whose path and parameters match what Tomcat would hand over. */
    private ServletServerHttpRequest request(String uriWithQuery) {
        int split = uriWithQuery.indexOf('?');
        String path = split < 0 ? uriWithQuery : uriWithQuery.substring(0, split);
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", path);
        if (split >= 0) {
            for (String pair : uriWithQuery.substring(split + 1).split("&")) {
                int eq = pair.indexOf('=');
                raw.addParameter(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new ServletServerHttpRequest(raw);
    }

    private JsonNode steps() {
        try {
            return json.readTree("[{\"stepType\":\"replace\",\"from\":1,\"to\":1,"
                    + "\"slice\":{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],"
                    + "\"openStart\":0,\"openEnd\":0}}]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
