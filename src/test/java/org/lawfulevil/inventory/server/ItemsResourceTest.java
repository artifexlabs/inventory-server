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
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
public class ItemsResourceTest {
  private final static String TOKEN = "dev-token";

  private static io.restassured.specification.RequestSpecification authed() {
    return given().header("Authorization", "Bearer " + TOKEN).contentType(ContentType.JSON);
  }

  @Test
  public void testRequestsWithoutTokenAreRejected() {
    given().get("/api/v1/items").then().statusCode(401);
    given().header("Authorization", "Bearer wrong").get("/api/v1/items").then().statusCode(401);
  }

  @Test
  public void testCrudRoundTrip() {
    String created = authed()
        .body(new JsonObject().put("name", "wrench").put("displayName", "Big Wrench").put("type", "tool-crud").encode())
        .post("/api/v1/items").then().statusCode(201).extract().asString();
    JsonObject item = new JsonObject(created);
    String id = item.getString("id");

    authed().get("/api/v1/items/" + id).then().statusCode(200).body("name", equalTo("wrench"));
    authed().get("/api/v1/items/type/tool-crud").then().statusCode(200).body("size()", is(1));

    item.put("description", "a fine wrench").put("quantity", 2L);
    authed().body(item.encode()).put("/api/v1/items/" + id).then().statusCode(200).body("description",
        equalTo("a fine wrench"));

    authed().delete("/api/v1/items/" + id).then().statusCode(204);
    authed().get("/api/v1/items/" + id).then().statusCode(404);
    authed().delete("/api/v1/items/" + id).then().statusCode(404);
  }

  @Test
  public void testUpdateWithMismatchedIdIsRejected() {
    String created = authed().body(new JsonObject().put("name", "hammer").put("type", "tool-mismatch").encode())
        .post("/api/v1/items").then().statusCode(201).extract().asString();
    authed().body(created).put("/api/v1/items/some-other-id").then().statusCode(400);
  }

  @Test
  public void testHealthAndOpenApiExposed() {
    given().get("/q/health").then().statusCode(200).body("status", equalTo("UP"));
    given().accept("application/json").get("/q/openapi").then().statusCode(200).body(containsString("openapi"));
  }
}
