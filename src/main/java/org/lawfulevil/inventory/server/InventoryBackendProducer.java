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

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.lawfulevil.inventory.api.InventorySystem;
import org.lawfulevil.inventory.impl.InMemoryAuditSink;
import org.lawfulevil.inventory.impl.InMemoryInventorySystem;
import org.lawfulevil.inventory.impl.PgInventorySystem;

import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Selects the {@link InventorySystem} backend. {@code inventory.storage=memory}
 * (default) serves from memory; {@code inventory.storage=pg} uses the
 * Postgres-backed implementation against the configured reactive datasource.
 * The Pool is resolved lazily so memory mode needs no datasource at all.
 */
@ApplicationScoped
public class InventoryBackendProducer {

  @ConfigProperty(name = "inventory.storage", defaultValue = "memory")
  String storage;

  @ConfigProperty(name = "inventory.principal", defaultValue = "inventory-server")
  String principal;

  @Inject
  Instance<Pool> pools;

  @Produces
  @Singleton
  public InventorySystem inventorySystem() {
    return switch (this.storage) {
    case "pg" -> new PgInventorySystem(this.pools.get(), this.principal);
    default -> new InMemoryInventorySystem(new InMemoryAuditSink(), this.principal);
    };
  }
}
