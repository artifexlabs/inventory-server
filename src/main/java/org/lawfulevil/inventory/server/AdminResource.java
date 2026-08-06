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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.AuditSink;
import org.lawfulevil.inventory.api.DefaultAuditEvent;
import org.lawfulevil.inventory.api.InventoryUser;
import org.lawfulevil.inventory.api.TokenService;
import org.lawfulevil.inventory.api.UserFactory;
import org.lawfulevil.inventory.impl.Ulid;
import org.lawfulevil.inventory.impl.UserStore;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Admin-only management: users, their admin flag, and issued tokens. Every
 * mutation here writes its own audit entry with the acting admin as the
 * principal.
 */
@Path("/api/v1/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject
  UserStore users;

  @Inject
  TokenService tokens;

  @Inject
  AuditSink audit;

  @Inject
  CurrentUser current;

  private Response forbidden() {
    return Response.status(Response.Status.FORBIDDEN)
        .entity(new JsonObject().put("error", "admin access required").encode()).build();
  }

  private CompletionStage<Void> audit(String action, String targetId, JsonObject details) {
    return this.audit.record(
        new DefaultAuditEvent(Ulid.next(), Instant.now(), this.current.principal(), action, targetId, details));
  }

  @GET
  @Path("/users")
  public CompletionStage<Response> listUsers() {
    if (!this.current.isAdmin())
      return CompletableFuture.completedStage(forbidden());
    return this.users.list().thenApply(list -> Response
        .ok(new JsonArray(list.stream().map(UserFactory::serialize).toList()).encode()).build());
  }

  @POST
  @Path("/users")
  public CompletionStage<Response> createUser(String body) {
    if (!this.current.isAdmin())
      return CompletableFuture.completedStage(forbidden());
    JsonObject j = new JsonObject(body);
    String email = j.getString("email");
    String password = j.getString("password");
    if (email == null || email.isBlank() || password == null || password.isBlank())
      return CompletableFuture.completedStage(Response.status(Response.Status.BAD_REQUEST)
          .entity(new JsonObject().put("error", "email and password are required").encode()).build());
    return this.users.ensureUser(email, j.getString("displayName"), password, Boolean.TRUE.equals(j.getBoolean("admin")))
        .thenCompose(u -> audit("user.create", u.getId(), new JsonObject().put("email", u.getEmail()))
            .thenApply(v -> Response.status(Response.Status.CREATED).entity(UserFactory.serialize(u).encode())
                .build()));
  }

  @DELETE
  @Path("/users/{id}")
  public CompletionStage<Response> deleteUser(@PathParam("id") String id) {
    if (!this.current.isAdmin())
      return CompletableFuture.completedStage(forbidden());
    if (this.current.get().map(InventoryUser::getId).filter(id::equals).isPresent())
      return CompletableFuture.completedStage(Response.status(Response.Status.CONFLICT)
          .entity(new JsonObject().put("error", "cannot delete yourself").encode()).build());
    return this.users.delete(id)
        .thenCompose(ok -> !ok
            ? CompletableFuture.completedStage(Response.status(Response.Status.NOT_FOUND).build())
            : audit("user.delete", id, null).thenApply(v -> Response.noContent().build()));
  }

  @POST
  @Path("/users/{id}/admin")
  public CompletionStage<Response> setAdmin(@PathParam("id") String id, String body) {
    if (!this.current.isAdmin())
      return CompletableFuture.completedStage(forbidden());
    boolean admin = Boolean.TRUE.equals(new JsonObject(body).getBoolean("admin"));
    return this.users.setAdmin(id, admin)
        .thenCompose(o -> o
            .map(u -> audit("user.set-admin", id, new JsonObject().put("admin", admin))
                .thenApply(v -> Response.ok(UserFactory.serialize(u).encode()).build()))
            .orElseGet(() -> CompletableFuture
                .completedStage(Response.status(Response.Status.NOT_FOUND).build())));
  }

  @GET
  @Path("/users/{id}/tokens")
  public CompletionStage<Response> listTokens(@PathParam("id") String id) {
    if (!this.current.isAdmin())
      return CompletableFuture.completedStage(forbidden());
    return this.tokens.tokensFor(id).thenApply(list -> Response
        .ok(new JsonArray(list.stream().map(t -> t.toJson()).toList()).encode()).build());
  }

  @DELETE
  @Path("/tokens/{token}")
  public CompletionStage<Response> revokeToken(@PathParam("token") String token) {
    if (!this.current.isAdmin())
      return CompletableFuture.completedStage(forbidden());
    return this.tokens.revoke(token)
        .thenCompose(ok -> !ok
            ? CompletableFuture.completedStage(Response.status(Response.Status.NOT_FOUND).build())
            : audit("token.revoke", token, null).thenApply(v -> Response.noContent().build()));
  }
}
