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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

/**
 * print-label through the REAL BrotherPTouchPrinter against a local TCP sink.
 * Regression for the 2026-08-09 hardware-smoke bug: the printer's future
 * completes on a ForkJoinPool thread, where the request-scoped CurrentUser
 * proxy is unreachable — the audit step 500'd AFTER the label had physically
 * printed. The log printer completes synchronously and can never catch this.
 */
@QuarkusTest
@TestProfile(BrotherPrinterModeTest.BrotherProfile.class)
@QuarkusTestResource(value = FakeRasterPrinterResource.class, restrictToAnnotatedClass = true)
public class BrotherPrinterModeTest {

  /** Dedicated app instance so the printer config cannot bleed into other tests. */
  public static class BrotherProfile implements QuarkusTestProfile {
  }

  private static final String TOKEN = "dev-token";

  @Test
  public void testPrintLabelViaBrotherPipelineAuditsAndSendsRaster() {
    String id = new JsonObject(given().header("Authorization", "Bearer " + TOKEN).contentType(ContentType.JSON)
        .body(new JsonObject().put("name", "brother-thing").put("type", "tool").encode()).post("/api/v1/items")
        .then().statusCode(201).extract().asString()).getString("id");

    given().header("Authorization", "Bearer " + TOKEN).post("/api/v1/items/" + id + "/print-label").then()
        .statusCode(204);
    given().header("Authorization", "Bearer " + TOKEN).get("/api/v1/audit/target/" + id).then().statusCode(200)
        .body("action", org.hamcrest.Matchers.hasItem("label.print"));

    // The sink records the job once the printer closes the socket.
    byte[] job = null;
    for (int i = 0; i < 100 && (job = FakeRasterPrinterResource.lastJob.get()) == null; i++) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    Assertions.assertNotNull(job, "fake printer never received a raster job");
    // Brother raster preamble: 100-byte invalidate, then ESC @ (1B 40).
    Assertions.assertTrue(job.length > 102, "raster job too short: " + job.length);
    for (int i = 0; i < 100; i++)
      Assertions.assertEquals(0, job[i], "invalidate byte " + i);
    Assertions.assertEquals(0x1B, job[100]);
    Assertions.assertEquals(0x40, job[101]);
    Assertions.assertEquals(0x1A, job[job.length - 1], "job must end with the print command (0x1A)");
  }
}
