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

import io.artifexlabs.inventory.api.AuditReader;
import io.artifexlabs.inventory.api.AuditSink;
import io.artifexlabs.inventory.api.InventorySystem;
import io.artifexlabs.inventory.api.InventoryUser;
import io.artifexlabs.inventory.api.TokenService;
import io.artifexlabs.inventory.impl.InMemoryAuditSink;
import io.artifexlabs.inventory.impl.InMemoryInventorySystem;
import io.artifexlabs.inventory.impl.InMemoryTokenService;
import io.artifexlabs.inventory.impl.InMemoryUserStore;
import io.artifexlabs.inventory.impl.PgAudit;
import io.artifexlabs.inventory.impl.PgInventorySystem;
import io.artifexlabs.inventory.impl.PgTokenService;
import io.artifexlabs.inventory.impl.PgUserStore;
import io.artifexlabs.inventory.api.UserStore;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Selects the storage backend the bus workers act through.
 * {@code inventory.storage=memory} (default) serves everything from memory;
 * {@code inventory.storage=pg} uses the Postgres implementations against the
 * configured reactive datasource. The Pool is resolved lazily so memory mode
 * needs no datasource at all.
 *
 * Since the event-bus migration this producer lives in inventory-server: the
 * domain beans (and their transactions and audit writes) belong to the worker
 * host, not the HTTP gateway. The gateway keeps its own copy solely for its
 * embedded single-process mode.
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

  /**
   * Where committed domain facts go (VERTICLES.md):
   * {@code inventory.events.bus} = {@code none} (default — events go
   * nowhere), {@code local} (in-process Vert.x bus), or {@code clustered}
   * (same publisher; the cluster is Vert.x configuration, not code).
   */
  @Produces
  @Singleton
  public io.artifexlabs.inventory.api.events.EventPublisher eventPublisher(
      Instance<io.vertx.core.Vertx> vertx) {
    return switch (config("inventory.events.bus", "none")) {
    case "local", "clustered" -> new io.artifexlabs.inventory.impl.bus.VertxEventPublisher(vertx.get());
    default -> io.artifexlabs.inventory.api.events.EventPublisher.NOOP;
    };
  }

  @Produces
  @Singleton
  public InventorySystem inventorySystem(io.artifexlabs.inventory.api.events.EventPublisher events,
      AuditSink sink) {
    return switch (storage()) {
    case "pg" -> new PgInventorySystem(this.pools.get(), principal()).withEventPublisher(events);
    default -> new InMemoryInventorySystem(sink, principal());
    };
  }

  /**
   * The sink every recorder sees is the publishing decorator: recorded events
   * are also announced as domain facts. The Pg domain systems bypass this (in-
   * transaction audit rows) and publish after commit themselves.
   */
  @Produces
  @Singleton
  public AuditSink auditSink(io.artifexlabs.inventory.api.events.EventPublisher events) {
    AuditSink raw = switch (storage()) {
    case "pg" -> pgAudit();
    default -> this.memoryAudit;
    };
    return new io.artifexlabs.inventory.impl.PublishingAuditSink(raw, events);
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
  public io.artifexlabs.inventory.api.LabelPrinter labelPrinter(InventorySystem items,
      Instance<io.vertx.core.Vertx> vertx) {
    // printer refusals reach a human through the status topic (PLAN.md Phase 21)
    io.artifexlabs.inventory.api.events.StatusPublisher status =
        new io.artifexlabs.inventory.impl.bus.VertxStatusPublisher(vertx.get());
    return switch (config("inventory.printer", "log")) {
    case "brother-p750w" -> new io.artifexlabs.inventory.impl.BrotherPTouchPrinter(
        config("inventory.printer.host", "localhost"),
        Integer.parseInt(config("inventory.printer.port", "9100")),
        Integer.parseInt(config("inventory.printer.tape-mm", "24")), status);
    case "zebra-gk420t" -> new io.artifexlabs.inventory.impl.ZebraPrinter(
        config("inventory.printer.host", "localhost"),
        Integer.parseInt(config("inventory.printer.port", "9100")),
        config("inventory.printer.format", "standard"))
        .withStatusPublisher(status)
        // labels print the container's name — "where is it" IS the container
        .withContainerLookup(items::getItem);
    default -> new io.artifexlabs.inventory.impl.LoggingLabelPrinter();
    };
  }

  /**
   * External UPC catalog sources, ordered (first hit wins): open data before
   * the rate-limited commercial trial. {@code off} disables lookups entirely;
   * base-URL overrides point tests at local stub fixtures.
   */
  @Produces
  @Singleton
  public io.artifexlabs.inventory.api.UpcCatalog upcCatalog() {
    String configured = config("inventory.catalog", "open-facts,upcitemdb");
    if (configured.isBlank() || "off".equals(configured.trim()))
      return io.artifexlabs.inventory.api.UpcCatalog.OFF;
    java.util.List<io.artifexlabs.inventory.api.UpcCatalog> sources = new java.util.ArrayList<>();
    for (String token : configured.split(",")) {
      switch (token.trim()) {
      case "open-facts" -> {
        String override = config("inventory.catalog.open-facts.url", "");
        sources.add(new io.artifexlabs.inventory.impl.catalog.OpenFactsCatalog(override.isBlank()
            ? io.artifexlabs.inventory.impl.catalog.OpenFactsCatalog.DEFAULT_BASES
            : java.util.List.of(override)));
      }
      case "upcitemdb" -> sources.add(new io.artifexlabs.inventory.impl.catalog.UpcItemDbCatalog(
          config("inventory.catalog.upcitemdb.url",
              io.artifexlabs.inventory.impl.catalog.UpcItemDbCatalog.DEFAULT_BASE)));
      default -> throw new IllegalArgumentException("unknown catalog source: " + token);
      }
    }
    return sources.size() == 1 ? sources.get(0)
        : new io.artifexlabs.inventory.impl.catalog.CompositeCatalog(sources);
  }

  @Produces
  @Singleton
  public io.artifexlabs.inventory.api.AssetStore assetStore(InventorySystem items,
      io.artifexlabs.inventory.api.events.EventPublisher events, AuditSink sink) {
    return switch (storage()) {
    case "pg" -> new io.artifexlabs.inventory.impl.PgAssetStore(this.pools.get(), principal())
        .withEventPublisher(events);
    default -> new io.artifexlabs.inventory.impl.InMemoryAssetStore(items, sink, principal());
    };
  }

  @Produces
  @Singleton
  public io.artifexlabs.inventory.api.RegionSystem regionSystem(InventorySystem items,
      io.artifexlabs.inventory.api.AssetStore assets,
      io.artifexlabs.inventory.api.events.EventPublisher events, AuditSink sink) {
    return switch (storage()) {
    case "pg" -> new io.artifexlabs.inventory.impl.PgRegionSystem(this.pools.get(), principal())
        .withEventPublisher(events);
    default -> new io.artifexlabs.inventory.impl.InMemoryRegionSystem(items, assets, sink, principal());
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
