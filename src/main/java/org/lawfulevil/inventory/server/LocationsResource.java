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

import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.LatLong;
import org.lawfulevil.inventory.api.LocationFactory;
import org.lawfulevil.inventory.api.LocationSystem;

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
 * First-class locations. Deletion answers 409 while items still reference the
 * location, mirroring the store's refusal.
 */
@Path("/api/v1/locations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LocationsResource {

  @Inject
  LocationSystem locations;

  @GET
  public CompletionStage<String> getAllLocations() {
    return this.locations.getAllLocations().thenApply(
        list -> new JsonArray(list.stream().map(LocationFactory::serialize).toList()).encode());
  }

  @GET
  @Path("/{id}")
  public CompletionStage<Response> getLocation(@PathParam("id") String id) {
    return this.locations.getLocation(id)
        .thenApply(o -> o.map(l -> Response.ok(LocationFactory.serialize(l).encode()).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build()));
  }

  @POST
  public CompletionStage<Response> createLocation(String body) {
    JsonObject j = new JsonObject(body);
    LatLong coords = j.containsKey("latitude") && j.containsKey("longitude")
        ? new LatLong(j.getDouble("latitude"), j.getDouble("longitude"))
        : null;
    return this.locations.createLocation(j.getString("name"), coords).thenApply(l -> Response
        .status(Response.Status.CREATED).entity(LocationFactory.serialize(l).encode()).build());
  }

  @DELETE
  @Path("/{id}")
  public CompletionStage<Response> deleteLocation(@PathParam("id") String id) {
    return this.locations.getLocation(id).thenCompose(existing -> {
      if (existing.isEmpty())
        return java.util.concurrent.CompletableFuture
            .completedStage(Response.status(Response.Status.NOT_FOUND).build());
      return this.locations.deleteLocation(id)
          .thenApply(ok -> ok ? Response.noContent().build()
              : Response.status(Response.Status.CONFLICT)
                  .entity(new JsonObject().put("error", "location is referenced by items").encode()).build());
    });
  }
}
