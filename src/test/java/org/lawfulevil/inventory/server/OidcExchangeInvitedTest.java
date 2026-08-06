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

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
@TestProfile(OidcExchangeInvitedTest.Profile.class)
public class OidcExchangeInvitedTest {
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("inventory.oidc.exchange-secret", "test-secret");
    }
  }

  private static io.restassured.response.ValidatableResponse exchange(String secret, String email) {
    var request = given().contentType(ContentType.JSON);
    if (secret != null)
      request = request.header(OidcExchangeResource.SECRET_HEADER, secret);
    return request.body(new JsonObject().put("email", email).encode()).post("/api/v1/auth/exchange").then();
  }

  @Test
  public void testInvitedUserGetsWorkingToken() {
    String body = exchange("test-secret", "admin@example.com").statusCode(200).extract().asString();
    String token = new JsonObject(body).getString("token");
    given().header("Authorization", "Bearer " + token).get("/api/v1/items").then().statusCode(200);
  }

  @Test
  public void testWrongOrMissingSecretRejected() {
    exchange("wrong-secret", "admin@example.com").statusCode(401);
    exchange(null, "admin@example.com").statusCode(401);
  }

  @Test
  public void testUninvitedEmailForbidden() {
    exchange("test-secret", "stranger@example.com").statusCode(403)
        .body("error", equalTo("not invited: stranger@example.com"));
  }

  @Test
  public void testMissingEmailRejected() {
    given().contentType(ContentType.JSON).header(OidcExchangeResource.SECRET_HEADER, "test-secret").body("{}")
        .post("/api/v1/auth/exchange").then().statusCode(400);
  }
}
