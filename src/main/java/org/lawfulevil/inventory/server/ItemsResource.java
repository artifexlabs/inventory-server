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

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.lawfulevil.inventory.api.InventorySystem;
import org.lawfulevil.inventory.api.Item;
import org.lawfulevil.inventory.api.ItemFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * CRUD over the inventory. The wire format is exactly
 * {@link ItemFactory#serialize(Item)} — the same JSON that travels the event
 * bus, so REST and bus consumers share one contract.
 */
@Path("/api/v1/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ItemsResource {

  @Inject
  InventorySystem inventory;

  @GET
  public CompletionStage<String> getAllItems() {
    return this.inventory.getAllItems().thenApply(ItemsResource::toJsonArray);
  }

  @GET
  @Path("/type/{type}")
  public CompletionStage<String> getItemsOfType(@PathParam("type") String type) {
    return this.inventory.getItemsOfType(type).thenApply(ItemsResource::toJsonArray);
  }

  @GET
  @Path("/{id}")
  public CompletionStage<Response> getItem(@PathParam("id") String id) {
    return this.inventory.getItem(id)
        .thenApply(o -> o.map(i -> Response.ok(ItemFactory.serialize(i).encode()).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build()));
  }

  @POST
  public CompletionStage<Response> createItem(String body) {
    JsonObject j = new JsonObject(body);
    return this.inventory.createItem(j.getString("name"), j.getString("displayName"), j.getString("type"))
        .thenApply(i -> Response.status(Response.Status.CREATED).entity(ItemFactory.serialize(i).encode()).build());
  }

  @PUT
  @Path("/{id}")
  public CompletionStage<Response> updateItem(@PathParam("id") String id, String body) {
    Item item = ItemFactory.deserialize(new JsonObject(body));
    if (!item.getId().equals(id))
      return java.util.concurrent.CompletableFuture.completedStage(
          Response.status(Response.Status.BAD_REQUEST)
              .entity(new JsonObject().put("error", "body id does not match path id").encode()).build());
    return this.inventory.updateItem(item)
        .thenApply(ok -> ok ? Response.ok(ItemFactory.serialize(item).encode()).build()
            : Response.status(Response.Status.NOT_FOUND).build());
  }

  @DELETE
  @Path("/{id}")
  public CompletionStage<Response> deleteItem(@PathParam("id") String id) {
    return this.inventory.deleteItem(id).thenApply(
        ok -> ok ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build());
  }

  @GET
  @Path("/{id}/containers")
  public CompletionStage<String> getContainers(@PathParam("id") String id) {
    return this.inventory.getContainersOf(id).thenApply(ItemsResource::toJsonArray);
  }

  @PUT
  @Path("/{containerId}/contained/{itemId}")
  public CompletionStage<Response> addToContainer(@PathParam("containerId") String containerId,
      @PathParam("itemId") String itemId) {
    return this.inventory.addToContainer(containerId, itemId).thenApply(ItemsResource::noContentOr404);
  }

  @DELETE
  @Path("/{containerId}/contained/{itemId}")
  public CompletionStage<Response> removeFromContainer(@PathParam("containerId") String containerId,
      @PathParam("itemId") String itemId) {
    return this.inventory.removeFromContainer(containerId, itemId).thenApply(ItemsResource::noContentOr404);
  }

  @POST
  @Path("/{itemId}/move-to/{containerId}")
  public CompletionStage<Response> moveToContainer(@PathParam("itemId") String itemId,
      @PathParam("containerId") String containerId) {
    return this.inventory.moveToContainer(itemId, containerId).thenApply(ItemsResource::noContentOr404);
  }

  private static Response noContentOr404(boolean ok) {
    return ok ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
  }

  @jakarta.inject.Inject
  org.lawfulevil.inventory.api.AssetStore assets;

  @POST
  @Path("/{itemId}/assets")
  @Consumes(MediaType.WILDCARD)
  public CompletionStage<Response> uploadAsset(@PathParam("itemId") String itemId,
      @jakarta.ws.rs.HeaderParam(AssetsResource.FILENAME_HEADER) String filename,
      @jakarta.ws.rs.HeaderParam("Content-Type") String contentType, byte[] body) {
    String name = filename == null || filename.isBlank() ? "unnamed" : filename;
    String type = contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : contentType;
    return this.assets.store(itemId, name, type, body == null ? new byte[0] : body)
        .thenApply(o -> o
            .map(info -> Response.status(Response.Status.CREATED).entity(info.toJson().encode()).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build()));
  }

  @GET
  @Path("/{itemId}/assets")
  public CompletionStage<String> listAssets(@PathParam("itemId") String itemId) {
    return this.assets.listFor(itemId)
        .thenApply(list -> new JsonArray(list.stream().map(i -> i.toJson()).toList()).encode());
  }

  private static String toJsonArray(List<Item> items) {
    return new JsonArray(items.stream().map(i -> ItemFactory.serialize(i)).toList()).encode();
  }
}
