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

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
@TestProfile(OidcExchangeAutoProvisionTest.Profile.class)
public class OidcExchangeAutoProvisionTest {
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("inventory.oidc.exchange-secret", "test-secret", "inventory.oidc.provision", "auto");
    }
  }

  @Test
  public void testUnknownEmailIsProvisionedNonAdminAndAudited() {
    String body = given().contentType(ContentType.JSON)
        .header(OidcExchangeResource.SECRET_HEADER, "test-secret")
        .body(new JsonObject().put("email", "newcomer@example.com").put("displayName", "New Comer").encode())
        .post("/api/v1/auth/exchange").then().statusCode(200).body("user.admin", is(false))
        .body("user.email", equalTo("newcomer@example.com")).extract().asString();
    JsonObject login = new JsonObject(body);
    String token = login.getString("token");
    String userId = login.getJsonObject("user").getString("id");

    // the issued token works, and the provisioning was audited
    given().header("Authorization", "Bearer " + token).get("/api/v1/items").then().statusCode(200);
    given().header("Authorization", "Bearer dev-token").get("/api/v1/audit/target/" + userId).then()
        .statusCode(200).body("action", org.hamcrest.Matchers.hasItem("user.create"));

    // second exchange reuses the same user rather than creating another
    String again = given().contentType(ContentType.JSON)
        .header(OidcExchangeResource.SECRET_HEADER, "test-secret")
        .body(new JsonObject().put("email", "newcomer@example.com").encode()).post("/api/v1/auth/exchange")
        .then().statusCode(200).extract().asString();
    org.junit.jupiter.api.Assertions.assertEquals(userId,
        new JsonObject(again).getJsonObject("user").getString("id"));
  }
}
