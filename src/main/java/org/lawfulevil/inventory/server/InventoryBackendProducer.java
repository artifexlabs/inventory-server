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

import org.lawfulevil.inventory.api.AuditReader;
import org.lawfulevil.inventory.api.AuditSink;
import org.lawfulevil.inventory.api.InventorySystem;
import org.lawfulevil.inventory.api.InventoryUser;
import org.lawfulevil.inventory.api.TokenService;
import org.lawfulevil.inventory.impl.InMemoryAuditSink;
import org.lawfulevil.inventory.impl.PgAudit;
import org.lawfulevil.inventory.impl.InMemoryInventorySystem;
import org.lawfulevil.inventory.impl.InMemoryTokenService;
import org.lawfulevil.inventory.impl.InMemoryUserStore;
import org.lawfulevil.inventory.impl.PgInventorySystem;
import org.lawfulevil.inventory.impl.PgTokenService;
import org.lawfulevil.inventory.impl.PgUserStore;
import org.lawfulevil.inventory.impl.UserStore;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Selects the storage backend. {@code inventory.storage=memory} (default)
 * serves everything from memory; {@code inventory.storage=pg} uses the
 * Postgres implementations against the configured reactive datasource. The
 * Pool is resolved lazily so memory mode needs no datasource at all.
 *
 * On startup the configured admin user is ensured to exist (idempotent), and
 * in memory mode the configured static API token is seeded so dev and test
 * flows work without logging in first.
 */
@ApplicationScoped
public class InventoryBackendProducer {

  // Config is read lazily (never injected into fields): in native images this
  // bean can be instantiated during static init, and field-injected values
  // would be frozen at their build-time defaults (e.g. storage=memory even
  // when the container says pg).
  private static String config(String name, String defaultValue) {
    return org.eclipse.microprofile.config.ConfigProvider.getConfig()
        .getOptionalValue(name, String.class).orElse(defaultValue);
  }

  private String storage() {
    return config("inventory.storage", "memory");
  }

  private String principal() {
    return config("inventory.principal", "inventory-server");
  }

  @Inject
  Instance<Pool> pools;

  private final InMemoryAuditSink memoryAudit = new InMemoryAuditSink();
  private volatile PgAudit pgAudit;

  private PgAudit pgAudit() {
    if (this.pgAudit == null)
      this.pgAudit = new PgAudit(this.pools.get());
    return this.pgAudit;
  }

  @Produces
  @Singleton
  public InventorySystem inventorySystem() {
    return switch (storage()) {
    case "pg" -> new PgInventorySystem(this.pools.get(), principal());
    default -> new InMemoryInventorySystem(this.memoryAudit, principal());
    };
  }

  @Produces
  @Singleton
  public AuditSink auditSink() {
    return switch (storage()) {
    case "pg" -> pgAudit();
    default -> this.memoryAudit;
    };
  }

  @Produces
  @Singleton
  public AuditReader auditReader() {
    return switch (storage()) {
    case "pg" -> pgAudit();
    default -> this.memoryAudit;
    };
  }

  @Produces
  @Singleton
  public org.lawfulevil.inventory.api.LocationSystem locationSystem(InventorySystem items) {
    return switch (storage()) {
    case "pg" -> new org.lawfulevil.inventory.impl.PgLocationSystem(this.pools.get(), principal());
    default -> new org.lawfulevil.inventory.impl.InMemoryLocationSystem(items, this.memoryAudit, principal());
    };
  }

  @Produces
  @Singleton
  public org.lawfulevil.inventory.api.LabelPrinter labelPrinter() {
    return new org.lawfulevil.inventory.impl.LoggingLabelPrinter();
  }

  @Produces
  @Singleton
  public org.lawfulevil.inventory.api.AssetStore assetStore(InventorySystem items) {
    return switch (storage()) {
    case "pg" -> new org.lawfulevil.inventory.impl.PgAssetStore(this.pools.get(), principal());
    default -> new org.lawfulevil.inventory.impl.InMemoryAssetStore(items, this.memoryAudit, principal());
    };
  }

  @Produces
  @Singleton
  public UserStore userStore() {
    return switch (storage()) {
    case "pg" -> new PgUserStore(this.pools.get());
    default -> new InMemoryUserStore();
    };
  }

  @Produces
  @Singleton
  public TokenService tokenService() {
    return switch (storage()) {
    case "pg" -> new PgTokenService(this.pools.get());
    default -> new InMemoryTokenService();
    };
  }

  void onStart(@Observes StartupEvent ev, UserStore users, TokenService tokens) {
    InventoryUser admin = users
        .ensureUser(config("inventory.admin.email", "admin@example.com"), "Administrator",
            config("inventory.admin.password", "change-me"), true)
        .toCompletableFuture().join();
    if (tokens instanceof InMemoryTokenService memoryTokens)
      memoryTokens.seed(config("inventory.api.token", "dev-token"), admin);
  }
}
