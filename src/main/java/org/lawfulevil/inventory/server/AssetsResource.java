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

import org.lawfulevil.inventory.api.AssetStore;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/**
 * Asset download and deletion by asset id. Item-scoped upload/list live on
 * {@link ItemsResource} (JAX-RS resolves the resource class by longest
 * class-level path, so /items/... paths must live under that class).
 */
@Path("/api/v1/assets")
public class AssetsResource {
  public final static String FILENAME_HEADER = "X-Filename";

  @Inject
  AssetStore assets;

  @GET
  @Path("/{id}")
  public CompletionStage<Response> download(@PathParam("id") String id) {
    return this.assets.get(id)
        .thenApply(o -> o
            .map(a -> Response.ok(a.data(), a.info().contentType())
                .header(FILENAME_HEADER, a.info().filename()).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build()));
  }

  @DELETE
  @Path("/{id}")
  public CompletionStage<Response> delete(@PathParam("id") String id) {
    return this.assets.delete(id).thenApply(ok -> ok ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build());
  }
}
