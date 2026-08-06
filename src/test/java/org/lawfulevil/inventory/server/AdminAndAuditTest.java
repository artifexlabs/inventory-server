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
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThan;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
public class AdminAndAuditTest {
  private final static String ADMIN_TOKEN = "dev-token";

  private static io.restassured.specification.RequestSpecification withToken(String token) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static JsonObject createUser(String email, boolean admin) {
    return new JsonObject(withToken(ADMIN_TOKEN)
        .body(new JsonObject().put("email", email).put("password", "pw").put("admin", admin).encode())
        .post("/api/v1/admin/users").then().statusCode(201).extract().asString());
  }

  private static String loginToken(String email) {
    return new JsonObject(given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", email).put("password", "pw").encode()).post("/api/v1/auth/login")
        .then().statusCode(200).extract().asString()).getString("token");
  }

  @Test
  public void testNonAdminIsForbidden() {
    createUser("pleb@example.com", false);
    String token = loginToken("pleb@example.com");

    withToken(token).get("/api/v1/admin/users").then().statusCode(403);
    withToken(token).body("{}").post("/api/v1/admin/users").then().statusCode(403);
    withToken(token).get("/api/v1/audit").then().statusCode(403);
    // but the per-target history is open to any authenticated user
    withToken(token).get("/api/v1/audit/target/anything").then().statusCode(200);
  }

  @Test
  public void testUserLifecycleIsAuditedAndManageable() {
    JsonObject created = createUser("lifecycle@example.com", false);
    String id = created.getString("id");

    // appears in the list
    withToken(ADMIN_TOKEN).get("/api/v1/admin/users").then().statusCode(200)
        .body("email", org.hamcrest.Matchers.hasItem("lifecycle@example.com"));

    // promote to admin, then verify the flag via a fresh login
    withToken(ADMIN_TOKEN).body(new JsonObject().put("admin", true).encode())
        .post("/api/v1/admin/users/" + id + "/admin").then().statusCode(200).body("admin", is(true));

    // audit trail recorded both actions against this user id
    withToken(ADMIN_TOKEN).get("/api/v1/audit/target/" + id).then().statusCode(200)
        .body("action", org.hamcrest.Matchers.hasItems("user.create", "user.set-admin"));

    // delete; a second delete 404s
    withToken(ADMIN_TOKEN).delete("/api/v1/admin/users/" + id).then().statusCode(204);
    withToken(ADMIN_TOKEN).delete("/api/v1/admin/users/" + id).then().statusCode(404);
  }

  @Test
  public void testAdminCannotDeleteSelf() {
    createUser("selfadmin@example.com", true);
    String token = loginToken("selfadmin@example.com");
    String selfId = new JsonObject(given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", "selfadmin@example.com").put("password", "pw").encode())
        .post("/api/v1/auth/login").then().extract().asString()).getJsonObject("user").getString("id");
    withToken(token).delete("/api/v1/admin/users/" + selfId).then().statusCode(409);
  }

  @Test
  public void testTokenListingAndRevocation() {
    JsonObject created = createUser("tokens@example.com", false);
    String id = created.getString("id");
    String token = loginToken("tokens@example.com");

    String listed = withToken(ADMIN_TOKEN).get("/api/v1/admin/users/" + id + "/tokens").then().statusCode(200)
        .body("size()", greaterThan(0)).extract().asString();
    String issued = new io.vertx.core.json.JsonArray(listed).getJsonObject(0).getString("token");

    withToken(ADMIN_TOKEN).delete("/api/v1/admin/tokens/" + issued).then().statusCode(204);
    withToken(token).get("/api/v1/items").then().statusCode(401);
    withToken(ADMIN_TOKEN).get("/api/v1/admin/users/" + id + "/tokens").then().statusCode(200)
        .body("[0].revoked", is(true));
    withToken(ADMIN_TOKEN).delete("/api/v1/admin/tokens/" + issued).then().statusCode(404);
  }

  @Test
  public void testGlobalAuditFeedForAdmin() {
    withToken(ADMIN_TOKEN)
        .body(new JsonObject().put("name", "audited-item").put("type", "audit-test").encode())
        .post("/api/v1/items").then().statusCode(201);
    withToken(ADMIN_TOKEN).get("/api/v1/audit?limit=100").then().statusCode(200).body("size()", greaterThan(0));
    withToken(ADMIN_TOKEN).get("/api/v1/audit?limit=1").then().statusCode(200).body("size()", equalTo(1));
  }
}
