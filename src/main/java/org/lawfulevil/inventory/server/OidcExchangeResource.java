/*
 * @formatter:off
 * Copyright © 2019 admin (admin@infrastructurebuilder.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * @formatter:on
 */
package org.lawfulevil.inventory.server;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.lawfulevil.inventory.api.AuditSink;
import org.lawfulevil.inventory.api.DefaultAuditEvent;
import org.lawfulevil.inventory.api.InventoryUser;
import org.lawfulevil.inventory.api.TokenService;
import org.lawfulevil.inventory.api.UserFactory;
import org.lawfulevil.inventory.impl.Ulid;
import org.lawfulevil.inventory.impl.UserStore;

import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Trusted webapp-to-server identity exchange for OIDC logins: the webapp has
 * already verified the user's identity with Google, and presents the shared
 * secret plus the verified email to obtain an API token.
 *
 * Disabled entirely (404) until {@code inventory.oidc.exchange-secret} is
 * configured. Provisioning policy {@code inventory.oidc.provision}:
 * {@code invited} (default — the email must already be a user) or {@code auto}
 * (create a passwordless-in-practice user on first login).
 */
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OidcExchangeResource {
  public final static String SECRET_HEADER = "X-Exchange-Secret";

  @ConfigProperty(name = "inventory.oidc.exchange-secret")
  Optional<String> exchangeSecret;

  @ConfigProperty(name = "inventory.oidc.provision", defaultValue = "invited")
  String provision;

  @Inject
  UserStore users;

  @Inject
  TokenService tokens;

  @Inject
  AuditSink audit;

  @POST
  @Path("/exchange")
  public CompletionStage<Response> exchange(@HeaderParam(SECRET_HEADER) String presentedSecret, String body) {
    if (this.exchangeSecret.isEmpty())
      return CompletableFuture.completedStage(Response.status(Response.Status.NOT_FOUND).build());
    if (presentedSecret == null || !presentedSecret.equals(this.exchangeSecret.get()))
      return CompletableFuture.completedStage(error(Response.Status.UNAUTHORIZED, "bad exchange secret"));
    JsonObject j = new JsonObject(body == null || body.isBlank() ? "{}" : body);
    String email = j.getString("email");
    if (email == null || email.isBlank())
      return CompletableFuture.completedStage(error(Response.Status.BAD_REQUEST, "email is required"));
    return findByEmail(email).thenCompose(existing -> {
      if (existing.isPresent())
        return issue(existing.get());
      if (!"auto".equals(this.provision))
        return CompletableFuture
            .completedStage(error(Response.Status.FORBIDDEN, "not invited: " + email));
      // auto-provision with an unguessable password: the account is OIDC-only in practice
      return this.users.ensureUser(email, j.getString("displayName"), UUID.randomUUID().toString(), false)
          .thenCompose(u -> this.audit
              .record(new DefaultAuditEvent(Ulid.next(), Instant.now(), email, "user.create", u.getId(),
                  new JsonObject().put("email", email).put("via", "oidc-auto-provision")))
              .thenCompose(v -> issue(u)));
    });
  }

  private CompletionStage<Optional<InventoryUser>> findByEmail(String email) {
    return this.users.list()
        .thenApply(all -> all.stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).findFirst());
  }

  private CompletionStage<Response> issue(InventoryUser user) {
    return this.tokens.issue(user).thenApply(t -> Response
        .ok(new JsonObject().put("token", t).put("user", UserFactory.serialize(user)).encode()).build());
  }

  private static Response error(Response.Status status, String message) {
    return Response.status(status).entity(new JsonObject().put("error", message).encode()).build();
  }
}
