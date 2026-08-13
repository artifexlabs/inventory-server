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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

/**
 * Federated-identity resolution at the exchange: subject match wins over
 * email, an email match links the new identity, and a relay-style email that
 * matches nothing follows the provisioning policy.
 */
@QuarkusTest
@TestProfile(OidcExchangeIdentityTest.Profile.class)
public class OidcExchangeIdentityTest {
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("inventory.oidc.exchange-secret", "test-secret", "inventory.oidc.provision", "auto");
    }
  }

  private static String exchange(JsonObject body) {
    return given().contentType(ContentType.JSON).header(OidcExchangeResource.SECRET_HEADER, "test-secret")
        .body(body.encode()).post("/api/v1/auth/exchange").then().statusCode(200).extract().asString();
  }

  private static String userId(String response) {
    return new JsonObject(response).getJsonObject("user").getString("id");
  }

  @Test
  public void testSubjectSurvivesEmailChange() {
    String first = exchange(new JsonObject().put("email", "apple-user@privaterelay.appleid.com")
        .put("provider", "apple").put("subject", "apple-sub-1"));
    // same subject, different (rotated relay) email → same account
    String second = exchange(new JsonObject().put("email", "other-relay@privaterelay.appleid.com")
        .put("provider", "apple").put("subject", "apple-sub-1"));
    assertEquals(userId(first), userId(second));
  }

  @Test
  public void testEmailMatchLinksNewProviderToExistingUser() {
    String google = exchange(
        new JsonObject().put("email", "linked@example.com").put("provider", "google").put("subject", "g-sub-9"));
    // Apple login with the same email links rather than creating a second user
    String apple = exchange(
        new JsonObject().put("email", "LINKED@example.com").put("provider", "apple").put("subject", "a-sub-9"));
    assertEquals(userId(google), userId(apple));
    // and the apple identity now resolves even if the email later differs
    String relay = exchange(new JsonObject().put("email", "relay@privaterelay.appleid.com")
        .put("provider", "apple").put("subject", "a-sub-9"));
    assertEquals(userId(google), userId(relay));
  }

  @Test
  public void testDistinctSubjectsAreDistinctUsers() {
    String a = exchange(new JsonObject().put("email", "a@privaterelay.appleid.com").put("provider", "apple")
        .put("subject", "sub-a"));
    String b = exchange(new JsonObject().put("email", "b@privaterelay.appleid.com").put("provider", "apple")
        .put("subject", "sub-b"));
    assertNotEquals(userId(a), userId(b));
  }

  @Test
  public void testLegacyEmailOnlyBodyStillWorks() {
    String first = exchange(new JsonObject().put("email", "legacy@example.com").put("displayName", "Legacy"));
    String again = exchange(new JsonObject().put("email", "legacy@example.com"));
    assertEquals(userId(first), userId(again));
    given().contentType(ContentType.JSON).header(OidcExchangeResource.SECRET_HEADER, "test-secret")
        .body(new JsonObject().put("provider", "apple").put("subject", "no-email").encode())
        .post("/api/v1/auth/exchange").then().statusCode(400)
        .body("error", equalTo("email is required"));
  }
}
