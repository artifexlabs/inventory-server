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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * A TCP 9100 sink standing in for the Brother PT-P750W: accepts one connection
 * at a time, swallows the raster job, and keeps the last payload for
 * assertions. Configures the app with the REAL BrotherPTouchPrinter pointed at
 * it, so the print path (including its pool-thread future completion) runs
 * exactly as it does against hardware.
 */
public class FakeRasterPrinterResource implements QuarkusTestResourceLifecycleManager {

  static final AtomicReference<byte[]> lastJob = new AtomicReference<>();

  private ServerSocket server;
  private Thread acceptor;

  @Override
  public Map<String, String> start() {
    try {
      this.server = new ServerSocket(0);
    } catch (IOException e) {
      throw new RuntimeException("could not open fake printer socket", e);
    }
    this.acceptor = new Thread(() -> {
      while (!this.server.isClosed()) {
        try (Socket s = this.server.accept()) {
          ByteArrayOutputStream job = new ByteArrayOutputStream();
          s.getInputStream().transferTo(job);
          lastJob.set(job.toByteArray());
        } catch (IOException closed) {
          return;
        }
      }
    }, "fake-raster-printer");
    this.acceptor.setDaemon(true);
    this.acceptor.start();
    return Map.of(
        "inventory.printer", "brother-p750w",
        "inventory.printer.host", "127.0.0.1",
        "inventory.printer.port", String.valueOf(this.server.getLocalPort()),
        "inventory.printer.tape-mm", "24");
  }

  @Override
  public void stop() {
    try {
      if (this.server != null)
        this.server.close();
    } catch (IOException ignored) {
    }
  }
}
