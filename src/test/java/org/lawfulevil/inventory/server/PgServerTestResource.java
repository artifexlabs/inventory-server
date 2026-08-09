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

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import org.testcontainers.containers.PostgreSQLContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Boots a real Postgres and applies the Liquibase schema BEFORE the Quarkus
 * app starts (the startup admin-ensure needs the tables), then points the
 * reactive datasource at it and flips {@code inventory.storage=pg}. Use with
 * {@code restrictToAnnotatedClass = true} — every other server test stays in
 * memory mode.
 */
public class PgServerTestResource implements QuarkusTestResourceLifecycleManager {

  private PostgreSQLContainer<?> pg;

  @Override
  public Map<String, String> start() {
    this.pg = new PostgreSQLContainer<>("postgres:16-alpine");
    this.pg.start();
    try (Connection c = DriverManager.getConnection(this.pg.getJdbcUrl(), this.pg.getUsername(),
        this.pg.getPassword())) {
      new Liquibase("db/changelog-master.yaml", new ClassLoaderResourceAccessor(),
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c))).update("");
    } catch (Exception e) {
      throw new RuntimeException("could not prepare Postgres schema", e);
    }
    return Map.of(
        "inventory.storage", "pg",
        "quarkus.datasource.reactive.url",
        "postgresql://" + this.pg.getHost() + ":" + this.pg.getMappedPort(5432) + "/" + this.pg.getDatabaseName(),
        "quarkus.datasource.username", this.pg.getUsername(),
        "quarkus.datasource.password", this.pg.getPassword(),
        "quarkus.devservices.enabled", "false");
  }

  @Override
  public void stop() {
    if (this.pg != null)
      this.pg.stop();
  }
}
