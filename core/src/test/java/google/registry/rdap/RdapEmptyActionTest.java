// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;

import google.registry.testing.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link RdapEmptyAction}. */
public class RdapEmptyActionTest {

  private FakeResponse fakeResponse;
  private RdapEmptyAction action;

  @BeforeEach
  void beforeEach() {
    fakeResponse = new FakeResponse();
    action = new RdapEmptyAction(fakeResponse);
  }

  @Test
  void testRedirect() {
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(HttpServletResponse.SC_FOUND);
    assertThat(fakeResponse.getPayload()).isEqualTo("Redirected to /rdap/help");
  }
}
