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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;

/**
 * The whole REST surface against inventory.storage=pg — real Postgres, real
 * Liquibase schema. Covers InventoryBackendProducer's pg arms with behavior:
 * login (PgUserStore + PgTokenService), item CRUD + audit (PgInventorySystem
 * + PgAudit), locations (PgLocationSystem), assets (PgAssetStore).
 */
@QuarkusTest
@TestProfile(PgModeApiTest.PgProfile.class)
@QuarkusTestResource(value = PgServerTestResource.class, restrictToAnnotatedClass = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PgModeApiTest {

  /** Dedicated app instance so the pg config can never bleed into (or from) other tests. */
  public static class PgProfile implements QuarkusTestProfile {
  }

  private static String token;
  private static String itemId;

  private static String token() {
    if (token == null)
      token = given().contentType("application/json")
          .body(new JsonObject().put("email", "admin@example.com").put("password", "change-me").encode())
          .post("/api/v1/auth/login").then().statusCode(200).body("token", notNullValue()).extract()
          .path("token");
    return token;
  }

  @Test
  @Order(1)
  public void loginIssuesPostgresBackedToken() {
    // pg mode seeds no static token: the dev token must be rejected...
    given().header("Authorization", "Bearer dev-token").get("/api/v1/items").then().statusCode(401);
    // ...while credential login against the ensured admin works.
    given().header("Authorization", "Bearer " + token()).get("/api/v1/items").then().statusCode(200);
  }

  @Test
  @Order(2)
  public void itemCrudAndAuditLandInPostgres() {
    itemId = given().header("Authorization", "Bearer " + token()).contentType("application/json")
        .body(new JsonObject().put("name", "pg-item").put("type", "tool").encode()).post("/api/v1/items")
        .then().statusCode(201).extract().path("id");
    given().header("Authorization", "Bearer " + token()).get("/api/v1/items").then().statusCode(200)
        .body("name", hasItem("pg-item"));
    given().header("Authorization", "Bearer " + token()).get("/api/v1/audit/target/" + itemId).then()
        .statusCode(200).body("action", hasItem("item.create"));
  }

  @Test
  @Order(3)
  public void locationsUseThePgStore() {
    String locationId = given().header("Authorization", "Bearer " + token()).contentType("application/json")
        .body(new JsonObject().put("name", "pg-shelf").encode()).post("/api/v1/locations").then()
        .statusCode(201).extract().path("id");
    given().header("Authorization", "Bearer " + token()).get("/api/v1/locations").then().statusCode(200)
        .body("id", hasItem(locationId));
  }

  @Test
  @Order(4)
  public void assetsUseThePgStore() {
    String assetId = given().header("Authorization", "Bearer " + token()).contentType("image/png")
        .header("X-Filename", "pg.png").body(new byte[] { (byte) 0x89, 'P', 'N', 'G' })
        .post("/api/v1/items/" + itemId + "/assets").then().statusCode(201).extract().path("id");
    byte[] fetched = given().header("Authorization", "Bearer " + token()).get("/api/v1/assets/" + assetId)
        .then().statusCode(200).extract().asByteArray();
    org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] { (byte) 0x89, 'P', 'N', 'G' }, fetched);
  }

  @Test
  @Order(5)
  public void regionsUseThePgStoreTransactionally() {
    // upload a photo of the space (the pg-item acts as the space/container)
    String assetId = given().header("Authorization", "Bearer " + token()).contentType("image/jpeg")
        .header("X-Filename", "space.jpg").body(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 })
        .post("/api/v1/items/" + itemId + "/assets").then().statusCode(201).extract().path("id");
    // draw a bare box (draw-then-describe step 1)
    String boxId = given().header("Authorization", "Bearer " + token()).contentType("application/json")
        .body(new JsonObject().put("x", 0.25).put("y", 0.25).put("w", 0.5).put("h", 0.5).encode())
        .post("/api/v1/assets/" + assetId + "/regions").then().statusCode(201).extract().path("id");
    given().header("Authorization", "Bearer " + token()).get("/api/v1/assets/" + assetId + "/regions").then()
        .statusCode(200).body("size()", equalTo(1)).body("[0].itemId", org.hamcrest.Matchers.nullValue());
    // describe it (step 2): item + containment + link, one pg transaction
    String toolId = given().header("Authorization", "Bearer " + token()).contentType("application/json")
        .body(new JsonObject().put("name", "pg-region-tool").put("type", "tool").put("containerId", itemId)
            .encode())
        .post("/api/v1/regions/" + boxId + "/make-item").then().statusCode(201).extract().path("id");
    given().header("Authorization", "Bearer " + token()).get("/api/v1/items/" + toolId + "/containers").then()
        .statusCode(200).body("id", hasItem(itemId));
    given().header("Authorization", "Bearer " + token()).get("/api/v1/assets/" + assetId + "/regions").then()
        .statusCode(200).body("[0].itemId", equalTo(toolId));
    given().header("Authorization", "Bearer " + token()).get("/api/v1/audit/target/" + toolId).then()
        .statusCode(200).body("action", hasItem("item.create-from-region"))
        .body("action", hasItem("item.contain"));
    // linked boxes refuse re-description; deletion still works and audits
    given().header("Authorization", "Bearer " + token()).contentType("application/json")
        .body(new JsonObject().put("name", "again").put("type", "t").encode())
        .post("/api/v1/regions/" + boxId + "/make-item").then().statusCode(404);
    given().header("Authorization", "Bearer " + token()).delete("/api/v1/regions/" + boxId).then()
        .statusCode(204);
    given().header("Authorization", "Bearer " + token()).get("/api/v1/assets/" + assetId + "/regions").then()
        .statusCode(200).body("size()", equalTo(0));
  }

  @Test
  @Order(6)
  public void logoutRevokesThePostgresToken() {
    given().header("Authorization", "Bearer " + token()).contentType("application/json")
        .post("/api/v1/auth/logout").then().statusCode(200).body("revoked", equalTo(true));
    given().header("Authorization", "Bearer " + token()).get("/api/v1/items").then().statusCode(401);
  }

  @Test
  @Order(7)
  public void auditTrailIsReadableByFreshLogin() {
    String fresh = given().contentType("application/json")
        .body(new JsonObject().put("email", "admin@example.com").put("password", "change-me").encode())
        .post("/api/v1/auth/login").then().statusCode(200).extract().path("token");
    // Self-logout is not audited (only admin revocations are); the pg audit
    // trail carries the mutations from the earlier tests.
    given().header("Authorization", "Bearer " + fresh).get("/api/v1/audit?limit=50&offset=0").then()
        .statusCode(200).body("action", hasItem("item.create")).body("action", hasItem("location.create"))
        .body("action", hasItem("asset.attach"));
    given().header("Authorization", "Bearer " + fresh).get("/api/v1/items/" + itemId).then().statusCode(200)
        .body("name", equalTo("pg-item"));
  }
}
