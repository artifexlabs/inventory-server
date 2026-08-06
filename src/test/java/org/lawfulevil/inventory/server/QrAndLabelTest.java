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

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
public class QrAndLabelTest {
  private final static String TOKEN = "dev-token";

  private static String createItem(String name) {
    return new JsonObject(given().header("Authorization", "Bearer " + TOKEN).contentType(ContentType.JSON)
        .body(new JsonObject().put("name", name).put("type", "qr").encode()).post("/api/v1/items").then()
        .statusCode(201).extract().asString()).getString("id");
  }

  @Test
  public void testQrPngDecodesToScanUrl() throws Exception {
    String id = createItem("qr-thing");
    byte[] png = given().header("Authorization", "Bearer " + TOKEN).get("/api/v1/items/" + id + "/qr.png")
        .then().statusCode(200).contentType("image/png").extract().asByteArray();

    // PNG magic bytes, then decode the QR back to the deep link
    Assertions.assertEquals((byte) 0x89, png[0]);
    Assertions.assertEquals('P', (char) png[1]);
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    String decoded = new QRCodeReader()
        .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    Assertions.assertEquals("http://localhost:8081/i/" + id, decoded);
  }

  @Test
  public void testQrForMissingItem404s() {
    given().header("Authorization", "Bearer " + TOKEN).get("/api/v1/items/nope/qr.png").then().statusCode(404);
  }

  @Test
  public void testPrintLabelSucceedsAndAudits() {
    String id = createItem("label-thing");
    given().header("Authorization", "Bearer " + TOKEN).post("/api/v1/items/" + id + "/print-label").then()
        .statusCode(204);
    given().header("Authorization", "Bearer " + TOKEN).get("/api/v1/audit/target/" + id).then().statusCode(200)
        .body("action", org.hamcrest.Matchers.hasItem("label.print"));
    given().header("Authorization", "Bearer " + TOKEN).post("/api/v1/items/nope/print-label").then()
        .statusCode(404);
  }
}
