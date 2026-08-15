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

import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.lawfulevil.inventory.api.AssetStore;
import org.lawfulevil.inventory.api.AuditReader;
import org.lawfulevil.inventory.api.AuditSink;
import org.lawfulevil.inventory.api.InventorySystem;
import org.lawfulevil.inventory.api.LabelPrinter;
import org.lawfulevil.inventory.api.RegionSystem;
import org.lawfulevil.inventory.api.TokenService;
import org.lawfulevil.inventory.impl.UserStore;
import org.lawfulevil.inventory.impl.bus.BusGuard;
import org.lawfulevil.inventory.impl.bus.BusWorkers;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * This IS inventory-server now: no HTTP surface beyond health, just the bus
 * workers deployed on the (usually clustered) Vert.x instance — CRUD, audit,
 * QR/label, users, tokens, and authentication, every one behind the
 * {@link BusGuard}'s fabric-token and role checks. Whether the bus is
 * process-local or clustered is Vert.x configuration, not code.
 */
@ApplicationScoped
public class BusHost {
  private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BusHost.class);

  @ConfigProperty(name = "inventory.bus.token", defaultValue = "dev-bus-token")
  String fabricToken;

  @ConfigProperty(name = "inventory.oidc.provision", defaultValue = "invited")
  String provision;

  @Inject
  Vertx vertx;

  void onStart(@Observes StartupEvent ev, InventorySystem inventory, AssetStore assets,
      RegionSystem regions, AuditReader auditReader, AuditSink auditSink, LabelPrinter printer, UserStore users,
      TokenService tokens) {
    var services = new BusWorkers.BackendServices(inventory, assets, regions, auditReader, auditSink,
        printer, users, tokens);
    try {
      BusWorkers.deploy(this.vertx, services, new BusGuard(this.fabricToken), this.provision).toCompletableFuture()
          .get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("bus workers failed to deploy", e);
    }
    log.info("inventory-server bus workers deployed on {}", BusHost.class.getPackageName());
  }
}
