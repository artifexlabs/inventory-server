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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.TokenService;
import org.lawfulevil.inventory.api.UserFactory;
import org.lawfulevil.inventory.impl.UserStore;

import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Credential login granting API bearer tokens. {@code /login} is the only
 * unauthenticated path in the API; everything else demands a token this
 * endpoint (or an admin) issued.
 */
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

  @Inject
  UserStore users;

  @Inject
  TokenService tokens;

  @POST
  @Path("/login")
  public CompletionStage<Response> login(String body) {
    JsonObject j = new JsonObject(body == null || body.isBlank() ? "{}" : body);
    String email = j.getString("email");
    String password = j.getString("password");
    if (email == null || password == null)
      return CompletableFuture.completedStage(badCredentials());
    return this.users.authenticate(email, password)
        .thenCompose(o -> o
            .map(u -> this.tokens.issue(u)
                .thenApply(t -> Response
                    .ok(new JsonObject().put("token", t).put("user", UserFactory.serialize(u)).encode()).build()))
            .orElseGet(() -> CompletableFuture.completedStage(badCredentials())));
  }

  @POST
  @Path("/logout")
  public CompletionStage<Response> logout(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
    String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
    return this.tokens.revoke(token)
        .thenApply(ok -> Response.ok(new JsonObject().put("revoked", ok).encode()).build());
  }

  private static Response badCredentials() {
    return Response.status(Response.Status.UNAUTHORIZED)
        .entity(new JsonObject().put("error", "invalid credentials").encode()).build();
  }
}
