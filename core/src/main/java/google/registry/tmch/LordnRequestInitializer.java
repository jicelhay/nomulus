// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tmch;

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.flogger.FluentLogger;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.model.tld.Tld;
import google.registry.request.UrlConnectionUtils;
import jakarta.inject.Inject;
import java.net.HttpURLConnection;
import java.util.Optional;

/** Helper class for setting the authorization header on a MarksDB LORDN request. */
final class LordnRequestInitializer {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Optional<String> marksdbLordnPassword;

  @Inject
  LordnRequestInitializer(@Key("marksdbLordnPassword") Optional<String> marksdbLordnPassword) {
    this.marksdbLordnPassword = marksdbLordnPassword;
  }

  /** Initializes a URL fetch request for talking to the MarksDB server. */
  void initialize(HttpURLConnection connection, String tld) {
    getMarksDbLordnCredentials(tld)
        .ifPresent(login -> UrlConnectionUtils.setBasicAuth(connection, login));
  }

  /** Returns the username and password for the current TLD to login to the MarksDB server. */
  private Optional<String> getMarksDbLordnCredentials(String tld) {
    if (marksdbLordnPassword.isPresent()) {
      String lordnUsername =
          verifyNotNull(
              Tld.get(tld).getLordnUsername(),
              "lordnUsername is not set for %s.",
              Tld.get(tld).getTld());
      return Optional.of(String.format("%s:%s", lordnUsername, marksdbLordnPassword.get()));
    } else {
      logger.atWarning().log(
          "Cannot set credentials for LORDN request because password isn't configured.");
      return Optional.empty();
    }
  }
}
