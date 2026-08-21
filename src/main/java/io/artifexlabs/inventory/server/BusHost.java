/*
 * @formatter:off
 * Copyright © 2019 admin (admin@artifexlabs.io)
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
package io.artifexlabs.inventory.server;

import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.artifexlabs.inventory.api.AssetStore;
import io.artifexlabs.inventory.api.AuditReader;
import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.LabelPrinter;
import io.artifexlabs.inventory.api.RegionSystem;
import io.artifexlabs.inventory.api.TokenService;
import io.artifexlabs.inventory.api.UserStore;
import io.artifexlabs.inventory.impl.bus.BusGuard;
import io.artifexlabs.inventory.impl.bus.BusWorkers;

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
      TokenService tokens, io.artifexlabs.inventory.api.UpcCatalog catalog) {
    var services = new BusWorkers.BackendServices(inventory, assets, regions, auditReader, auditSink,
        printer, users, tokens, catalog);
    try {
      BusWorkers.deploy(this.vertx, services, new BusGuard(this.fabricToken,
          new io.artifexlabs.inventory.impl.bus.VertxStatusPublisher(this.vertx)), this.provision).toCompletableFuture()
          .get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("bus workers failed to deploy", e);
    }
    log.info("inventory-server bus workers deployed on {}", BusHost.class.getPackageName());
  }
}
