/*
 * @formatter:off
 * Copyright © 2019 admin (admin@artifexlabs.io)
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.lawfulevil.inventory.api.bus.BusActions;
import org.lawfulevil.inventory.api.bus.Roles;
import org.lawfulevil.inventory.impl.bus.DefaultBusEnvelope;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The worker fabric, exercised over the real event bus: guard refusals
 * (fabric token, roles) and representative worker paths, memory backend.
 */
@QuarkusTest
public class BusHostTest {

  private final static String FABRIC = "dev-bus-token";

  @jakarta.inject.Inject
  Vertx vertx;

  private Object request(String action, Optional<String> target, JsonObject data, String token, Set<String> roles)
      throws Exception {
    JsonObject envelope = new DefaultBusEnvelope(DefaultBusEnvelope.VERSION, token, "test-user",
        "tester@example.com", roles, action, target, data).toJson();
    CompletableFuture<Object> reply = new CompletableFuture<>();
    this.vertx.eventBus().request(BusActions.addressOf(action), envelope, r -> {
      if (r.succeeded())
        reply.complete(r.result().body());
      else
        reply.completeExceptionally(r.cause());
    });
    return reply.get(10, TimeUnit.SECONDS);
  }

  private static int failureCode(Exception e) {
    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
    assertTrue(cause instanceof ReplyException, "expected a bus failure, got " + cause);
    return ((ReplyException) cause).failureCode();
  }

  @Test
  public void badFabricTokenIsRefused401() throws Exception {
    try {
      request(BusActions.ITEMS_LIST, Optional.empty(), new JsonObject(), "wrong-token", Set.of(Roles.READ));
      throw new AssertionError("admitted an envelope with a bad fabric token");
    } catch (ExecutionException e) {
      assertEquals(401, failureCode(e));
    }
  }

  @Test
  public void missingRoleIsRefused403() throws Exception {
    try {
      request(BusActions.AUDIT_RECENT, Optional.empty(), new JsonObject(), FABRIC, Set.of(Roles.READ, Roles.WRITE));
      throw new AssertionError("admitted audit.recent without the admin role");
    } catch (ExecutionException e) {
      assertEquals(403, failureCode(e));
    }
  }

  @Test
  public void crudRoundTripOverTheBus() throws Exception {
    JsonObject created = (JsonObject) request(BusActions.ITEMS_CREATE, Optional.empty(),
        new JsonObject().put("name", "bus-crate").put("displayName", "Bus Crate").put("type", "container"),
        FABRIC, Set.of(Roles.READ, Roles.WRITE));
    String id = created.getString("id");
    assertNotNull(id);

    JsonObject fetched = (JsonObject) request(BusActions.ITEMS_GET, Optional.of(id), new JsonObject(), FABRIC,
        Set.of(Roles.READ));
    assertEquals("bus-crate", fetched.getString("name"));

    JsonArray all = (JsonArray) request(BusActions.ITEMS_LIST, Optional.empty(), new JsonObject(), FABRIC,
        Set.of(Roles.READ));
    assertTrue(all.stream().map(JsonObject.class::cast).anyMatch(j -> id.equals(j.getString("id"))));

    // every action is attributed to the envelope's authenticated user
    JsonArray history = (JsonArray) request(BusActions.AUDIT_BY_TARGET, Optional.of(id), new JsonObject(), FABRIC,
        Set.of(Roles.READ));
    assertTrue(history.stream().map(JsonObject.class::cast)
        .anyMatch(e -> "item.create".equals(e.getString("action"))
            && "tester@example.com".equals(e.getString("principal"))),
        "item.create should carry the acting principal, got: " + history);

    try {
      request(BusActions.ITEMS_GET, Optional.of("nope"), new JsonObject(), FABRIC, Set.of(Roles.READ));
      throw new AssertionError("found an item that does not exist");
    } catch (ExecutionException e) {
      assertEquals(404, failureCode(e));
    }
  }

  @Test
  public void authLoginAndTokenResolutionWork() throws Exception {
    JsonObject login = (JsonObject) request(BusActions.AUTH_LOGIN, Optional.empty(),
        new JsonObject().put("email", "admin@example.com").put("password", "change-me"), FABRIC, Set.of());
    String token = login.getString("token");
    assertNotNull(token);
    assertTrue(login.getJsonObject("user").getBoolean("admin"));

    JsonObject resolved = (JsonObject) request(BusActions.AUTH_TOKEN, Optional.empty(),
        new JsonObject().put("token", token), FABRIC, Set.of());
    assertEquals("admin@example.com", resolved.getString("email"));

    try {
      request(BusActions.AUTH_TOKEN, Optional.empty(), new JsonObject().put("token", "junk"), FABRIC, Set.of());
      throw new AssertionError("resolved a junk token");
    } catch (ExecutionException e) {
      assertEquals(401, failureCode(e));
    }
  }

  @Test
  public void labelQrRendersOverTheBus() throws Exception {
    JsonObject created = (JsonObject) request(BusActions.ITEMS_CREATE, Optional.empty(),
        new JsonObject().put("name", "qr-target").put("displayName", "QR Target").put("type", "thing"), FABRIC,
        Set.of(Roles.READ, Roles.WRITE));
    String id = created.getString("id");
    JsonObject qr = (JsonObject) request(BusActions.LABELS_QR, Optional.of(id),
        new JsonObject().put("url", "http://localhost:8081/i/" + id), FABRIC, Set.of(Roles.READ));
    byte[] png = qr.getBinary("png");
    assertTrue(png.length > 100, "png should have real content");
    // PNG magic
    assertEquals((byte) 0x89, png[0]);
    assertEquals('P', png[1]);
  }
}
