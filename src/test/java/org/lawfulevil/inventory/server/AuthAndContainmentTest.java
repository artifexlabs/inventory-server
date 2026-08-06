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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
public class AuthAndContainmentTest {

  private static io.restassured.specification.RequestSpecification withToken(String token) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String create(String token, String name, String type) {
    return new JsonObject(withToken(token).body(new JsonObject().put("name", name).put("type", type).encode())
        .post("/api/v1/items").then().statusCode(201).extract().asString()).getString("id");
  }

  @Test
  public void testLoginIssuesWorkingToken() {
    String body = given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", "admin@example.com").put("password", "change-me").encode())
        .post("/api/v1/auth/login").then().statusCode(200).extract().asString();
    JsonObject login = new JsonObject(body);
    String token = login.getString("token");
    assertNotNull(token);
    assertNotNull(login.getJsonObject("user").getString("id"));

    withToken(token).get("/api/v1/items").then().statusCode(200);
  }

  @Test
  public void testBadCredentialsRejected() {
    given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", "admin@example.com").put("password", "nope").encode())
        .post("/api/v1/auth/login").then().statusCode(401);
    given().contentType(ContentType.JSON).body("{}").post("/api/v1/auth/login").then().statusCode(401);
  }

  @Test
  public void testMeReflectsCurrentUser() {
    withToken("dev-token").get("/api/v1/auth/me").then().statusCode(200)
        .body("email", equalTo("admin@example.com")).body("admin", is(true));
    given().get("/api/v1/auth/me").then().statusCode(401);
  }

  @Test
  public void testLogoutRevokesToken() {
    String token = new JsonObject(given().contentType(ContentType.JSON)
        .body(new JsonObject().put("email", "admin@example.com").put("password", "change-me").encode())
        .post("/api/v1/auth/login").then().statusCode(200).extract().asString()).getString("token");

    withToken(token).get("/api/v1/items").then().statusCode(200);
    withToken(token).post("/api/v1/auth/logout").then().statusCode(200).body("revoked", is(true));
    withToken(token).get("/api/v1/items").then().statusCode(401);
  }

  @Test
  public void testContainmentEndpoints() {
    String token = "dev-token";
    String box = create(token, "box", "cont-box");
    String bin = create(token, "bin", "cont-bin");
    String bolt = create(token, "bolt", "cont-part");

    withToken(token).put("/api/v1/items/" + box + "/contained/" + bolt).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + bolt + "/containers").then().statusCode(200).body("size()", is(1))
        .body("[0].id", equalTo(box));

    withToken(token).post("/api/v1/items/" + bolt + "/move-to/" + bin).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + bolt + "/containers").then().statusCode(200).body("size()", is(1))
        .body("[0].id", equalTo(bin));

    withToken(token).delete("/api/v1/items/" + bin + "/contained/" + bolt).then().statusCode(204);
    withToken(token).get("/api/v1/items/" + bolt + "/containers").then().statusCode(200).body("size()", is(0));
    withToken(token).delete("/api/v1/items/" + bin + "/contained/" + bolt).then().statusCode(404);
    withToken(token).put("/api/v1/items/missing/contained/" + bolt).then().statusCode(404);
  }
}
