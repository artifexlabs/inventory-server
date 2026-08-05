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

import java.io.IOException;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Guards every REST resource with a static bearer token. Milestone-1 stopgap:
 * the token comes from configuration; the {@code TokenService}-backed
 * per-user tokens replace this in the webapp phase.
 */
@Provider
public class BearerTokenFilter implements ContainerRequestFilter {

  @ConfigProperty(name = "inventory.api.token")
  String apiToken;

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    String header = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.equals("Bearer " + this.apiToken))
      requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
          .type(MediaType.APPLICATION_JSON)
          .entity(new JsonObject().put("error", "missing or invalid bearer token").encode()).build());
  }
}
