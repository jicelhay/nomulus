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

package google.registry.tools;

import static google.registry.model.tld.Tlds.assertTldsExist;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import google.registry.model.tld.Tld;
import jakarta.inject.Inject;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.List;

/** Command to show a TLD record. */
@Parameters(separators = " =", commandDescription = "Show TLD record(s)")
final class GetTldCommand implements Command {

  @Parameter(
      description = "TLD(s) to show",
      required = true)
  private List<String> mainParameters;

  @Inject ObjectMapper objectMapper;

  @Override
  public void run() throws JsonProcessingException, UnsupportedEncodingException {
    // Don't use try-with-resources to manage standard output streams, closing the stream will
    // cause subsequent output to standard output or standard error to be lost
    // See: https://errorprone.info/bugpattern/ClosingStandardOutputStreams
    PrintStream printStream = new PrintStream(System.out, false, UTF_8.name());
    for (String tld : assertTldsExist(mainParameters)) {
        printStream.println(objectMapper.writeValueAsString(Tld.get(tld)));
      }
  }
}
