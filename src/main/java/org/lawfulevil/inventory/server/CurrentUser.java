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

import java.util.Optional;

import org.lawfulevil.inventory.api.InventoryUser;

import jakarta.enterprise.context.RequestScoped;

/**
 * The authenticated user for the current request, set by
 * {@link BearerTokenFilter} once the token resolves.
 */
@RequestScoped
public class CurrentUser {
  private InventoryUser user;

  public void set(InventoryUser user) {
    this.user = user;
  }

  public Optional<InventoryUser> get() {
    return Optional.ofNullable(this.user);
  }

  public boolean isAdmin() {
    return this.user != null && this.user.isAdmin();
  }

  public String principal() {
    return this.user == null ? "anonymous" : this.user.getEmail();
  }
}
