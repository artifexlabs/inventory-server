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

import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.lawfulevil.inventory.api.TokenService;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Guards every REST resource with a bearer token validated against the
 * {@link TokenService}. The login and exchange endpoints are the sole
 * exemptions.
 *
 * Reactive on purpose: the filter runs on the Vert.x event loop, and in pg
 * mode {@code authenticate} is a real database round trip whose result is
 * delivered by an event loop — joining it here deadlocks (found by
 * PgModeApiTest). Returning a Uni lets the request suspend instead.
 */
public class BearerTokenFilter {

  @Inject
  TokenService tokens;

  @Inject
  CurrentUser currentUser;

  @ServerRequestFilter
  public Uni<Response> filter(ContainerRequestContext requestContext) {
    String path = requestContext.getUriInfo().getPath();
    if (path.endsWith("/auth/login") || path.endsWith("/auth/exchange"))
      return Uni.createFrom().nullItem();
    String header = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
    String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    if (token == null)
      return Uni.createFrom().item(unauthorized());
    return Uni.createFrom().completionStage(() -> this.tokens.authenticate(token)).map(user -> {
      if (user.isEmpty())
        return unauthorized();
      this.currentUser.set(user.get());
      return null;
    });
  }

  private static Response unauthorized() {
    return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON)
        .entity(new JsonObject().put("error", "missing or invalid bearer token").encode()).build();
  }
}
