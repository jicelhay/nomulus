// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.sql.flyway;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.truth.TextDiffSubject.assertThat;

import com.google.common.base.Joiner;
import com.google.common.io.Resources;
import google.registry.persistence.NomulusPostgreSql;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import org.flywaydb.core.Flyway;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;

/** Unit tests about Cloud SQL schema. */
@RunWith(JUnit4.class)
public class SchemaTest {

  // Resource path that is mapped to the testcontainer instance.
  private static final String MOUNTED_RESOURCE_PATH = "testcontainer/mount";
  // The mount point in the container.
  private static final String CONTAINER_MOUNT_POINT = "/tmp/pg_dump_out";
  // pg_dump output file name.
  private static final String DUMP_OUTPUT_FILE = "dump.txt";

  /**
   * The target database for schema deployment.
   *
   * <p>A resource path is mapped to this container in READ_WRITE mode to retrieve the deployed
   * schema generated by the 'pg_dump' command. We do not communicate over stdout because
   * testcontainer adds spurious newlines. See <a
   * href=https://github.com/testcontainers/testcontainers-java/issues/1854>this link</a> for more
   * information.
   */
  @Rule
  public PostgreSQLContainer sqlContainer =
      new PostgreSQLContainer<>(NomulusPostgreSql.getDockerTag())
          .withClasspathResourceMapping(
              MOUNTED_RESOURCE_PATH, CONTAINER_MOUNT_POINT, BindMode.READ_WRITE);

  @Test
  public void deploySchema_success() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .locations("sql/flyway")
            .dataSource(
                sqlContainer.getJdbcUrl(), sqlContainer.getUsername(), sqlContainer.getPassword())
            .load();

    // flyway.migrate() returns the number of newly pushed scripts. This is a variable
    // number as our schema evolves.
    assertThat(flyway.migrate()).isGreaterThan(0);
    flyway.validate();

    Container.ExecResult execResult =
        sqlContainer.execInContainer(
            StandardCharsets.UTF_8,
            getSchemaDumpCommand(sqlContainer.getUsername(), sqlContainer.getDatabaseName()));
    if (execResult.getExitCode() != 0) {
      throw new RuntimeException(execResult.toString());
    }

    URL dumpedSchema =
        Resources.getResource(
            Joiner.on(File.separatorChar).join(MOUNTED_RESOURCE_PATH, DUMP_OUTPUT_FILE));

    assertThat(dumpedSchema)
        .hasSameContentAs(Resources.getResource("sql/schema/nomulus.golden.sql"));
  }

  private static String[] getSchemaDumpCommand(String username, String dbName) {
    return new String[] {
      "pg_dump",
      "-h",
      "localhost",
      "-U",
      username,
      "-f",
      Paths.get(CONTAINER_MOUNT_POINT, DUMP_OUTPUT_FILE).toString(),
      "--schema-only",
      "--no-owner",
      "--no-privileges",
      "--exclude-table",
      "flyway_schema_history",
      dbName
    };
  }
}
