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

import org.lawfulevil.inventory.api.AuditEventFactory;
import org.lawfulevil.inventory.api.AuditReader;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Read the audit trail. The global feed is admin-only; the per-target history
 * is available to any authenticated user (it backs the item history view).
 */
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {
  private final static int MAX_LIMIT = 200;

  @Inject
  AuditReader audit;

  @Inject
  CurrentUser current;

  @GET
  public CompletionStage<Response> recent(@QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    if (!this.current.isAdmin())
      return CompletableFuture.completedStage(Response.status(Response.Status.FORBIDDEN)
          .entity(new JsonObject().put("error", "admin access required").encode()).build());
    return this.audit.recent(clamp(limit), Math.max(0, offset))
        .thenApply(AuditResource::ok);
  }

  @GET
  @Path("/target/{id}")
  public CompletionStage<Response> byTarget(@PathParam("id") String id,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    return this.audit.byTarget(id, clamp(limit)).thenApply(AuditResource::ok);
  }

  private static int clamp(int limit) {
    return Math.max(1, Math.min(limit, MAX_LIMIT));
  }

  private static Response ok(java.util.List<org.lawfulevil.inventory.api.AuditEvent> events) {
    return Response.ok(new JsonArray(events.stream().map(AuditEventFactory::serialize).toList()).encode()).build();
  }
}
