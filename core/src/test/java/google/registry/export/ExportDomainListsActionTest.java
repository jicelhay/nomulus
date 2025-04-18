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

package google.registry.export;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.FeatureFlag.FeatureName.INCLUDE_PENDING_DELETE_DATE_FOR_DOMAINS;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.MediaType;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.FeatureFlag;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ExportDomainListsAction}. */
class ExportDomainListsActionTest {

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final DriveConnection driveConnection = mock(DriveConnection.class);
  private final ArgumentCaptor<byte[]> bytesExportedToDrive = ArgumentCaptor.forClass(byte[].class);
  private ExportDomainListsAction action;
  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T02:02:02Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    createTld("testtld");
    persistResource(Tld.get("tld").asBuilder().setDriveFolderId("brouhaha").build());
    persistResource(Tld.get("testtld").asBuilder().setTldType(TldType.TEST).build());

    action = new ExportDomainListsAction();
    action.gcsBucket = "outputbucket";
    action.gcsUtils = gcsUtils;
    action.clock = clock;
    action.driveConnection = driveConnection;
    persistFeatureFlag(INACTIVE);
  }

  private void verifyExportedToDrive(String folderId, String filename, String domains)
      throws Exception {
    verify(driveConnection)
        .createOrUpdateFile(
            eq(filename),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq(folderId),
            bytesExportedToDrive.capture());
    assertThat(new String(bytesExportedToDrive.getValue(), UTF_8)).isEqualTo(domains);
  }

  @Test
  void test_outputsOnlyActiveDomains_txt() throws Exception {
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistDeletedDomain("mortuary.tld", DateTime.parse("2001-03-14T10:11:12Z"));
    action.run();
    BlobId existingFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(existingFile), UTF_8);
    // Check that it only contains the active domains, not the dead one.
    assertThat(tlds).isEqualTo("onetwo.tld\nrudnitzky.tld");
    verifyExportedToDrive("brouhaha", "registered_domains_tld.txt", "onetwo.tld\nrudnitzky.tld");
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  void test_outputsOnlyActiveDomains_csv() throws Exception {
    persistFeatureFlag(ACTIVE);
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistDeletedDomain("mortuary.tld", DateTime.parse("2001-03-14T10:11:12Z"));
    action.run();
    BlobId existingFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(existingFile), UTF_8);
    // Check that it only contains the active domains, not the dead one.
    assertThat(tlds).isEqualTo("onetwo.tld,\nrudnitzky.tld,");
    verifyExportedToDrive("brouhaha", "registered_domains_tld.txt", "onetwo.tld,\nrudnitzky.tld,");
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  void test_outputsOnlyDomainsOnRealTlds_txt() throws Exception {
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistActiveDomain("wontgo.testtld");
    action.run();
    BlobId existingFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(existingFile), UTF_8).trim();
    // Check that it only contains the domains on the real TLD, and not the test one.
    assertThat(tlds).isEqualTo("onetwo.tld\nrudnitzky.tld");
    // Make sure that the test TLD file wasn't written out.
    BlobId nonexistentFile = BlobId.of("outputbucket", "testtld.txt");
    assertThrows(StorageException.class, () -> gcsUtils.readBytesFrom(nonexistentFile));
    ImmutableList<String> ls = gcsUtils.listFolderObjects("outputbucket", "");
    assertThat(ls).containsExactly("tld.txt");
    verifyExportedToDrive("brouhaha", "registered_domains_tld.txt", "onetwo.tld\nrudnitzky.tld");
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  void test_outputsOnlyDomainsOnRealTlds_csv() throws Exception {
    persistFeatureFlag(ACTIVE);
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistActiveDomain("wontgo.testtld");
    action.run();
    BlobId existingFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(existingFile), UTF_8).trim();
    // Check that it only contains the domains on the real TLD, and not the test one.
    assertThat(tlds).isEqualTo("onetwo.tld,\nrudnitzky.tld,");
    // Make sure that the test TLD file wasn't written out.
    BlobId nonexistentFile = BlobId.of("outputbucket", "testtld.txt");
    assertThrows(StorageException.class, () -> gcsUtils.readBytesFrom(nonexistentFile));
    ImmutableList<String> ls = gcsUtils.listFolderObjects("outputbucket", "");
    assertThat(ls).containsExactly("tld.txt");
    verifyExportedToDrive("brouhaha", "registered_domains_tld.txt", "onetwo.tld,\nrudnitzky.tld,");
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  void test_outputIncludesDeletionTimes_forPendingDeletes_notRdemption() throws Exception {
    persistFeatureFlag(ACTIVE);
    // Domains pending delete (meaning the 5 day period, not counting the 30 day redemption period)
    // should include their pending deletion date
    persistActiveDomain("active.tld");
    Domain redemption = persistActiveDomain("redemption.tld");
    persistResource(
        redemption
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .addGracePeriod(
                GracePeriod.createWithoutBillingEvent(
                    GracePeriodStatus.REDEMPTION,
                    redemption.getRepoId(),
                    clock.nowUtc().plusDays(20),
                    redemption.getCurrentSponsorRegistrarId()))
            .build());
    persistResource(
        persistActiveDomain("pendingdelete.tld")
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .setDeletionTime(clock.nowUtc().plusDays(3))
            .build());

    action.run();

    verifyExportedToDrive(
        "brouhaha",
        "registered_domains_tld.txt",
        "active.tld,\npendingdelete.tld,2020-02-05T02:02:02.000Z\nredemption.tld,");
  }

  @Test
  void test_outputsDomainsFromDifferentTldsToMultipleFiles_txt() throws Exception {
    createTld("tldtwo");
    persistResource(Tld.get("tldtwo").asBuilder().setDriveFolderId("hooray").build());

    createTld("tldthree");
    // You'd think this test was written around Christmas, but it wasn't.
    persistActiveDomain("dasher.tld");
    persistActiveDomain("prancer.tld");
    persistActiveDomain("rudolph.tldtwo");
    persistActiveDomain("santa.tldtwo");
    persistActiveDomain("buddy.tldtwo");
    persistActiveDomain("cupid.tldthree");
    action.run();
    BlobId firstTldFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(firstTldFile), UTF_8).trim();
    assertThat(tlds).isEqualTo("dasher.tld\nprancer.tld");
    BlobId secondTldFile = BlobId.of("outputbucket", "tldtwo.txt");
    String moreTlds = new String(gcsUtils.readBytesFrom(secondTldFile), UTF_8).trim();
    assertThat(moreTlds).isEqualTo("buddy.tldtwo\nrudolph.tldtwo\nsanta.tldtwo");
    BlobId thirdTldFile = BlobId.of("outputbucket", "tldthree.txt");
    String evenMoreTlds = new String(gcsUtils.readBytesFrom(thirdTldFile), UTF_8).trim();
    assertThat(evenMoreTlds).isEqualTo("cupid.tldthree");
    verifyExportedToDrive("brouhaha", "registered_domains_tld.txt", "dasher.tld\nprancer.tld");
    verifyExportedToDrive(
        "hooray", "registered_domains_tldtwo.txt", "buddy.tldtwo\nrudolph.tldtwo\nsanta.tldtwo");
    // tldthree does not have a drive id, so no export to drive is performed.
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  void test_outputsDomainsFromDifferentTldsToMultipleFiles_csv() throws Exception {
    persistFeatureFlag(ACTIVE);
    createTld("tldtwo");
    persistResource(Tld.get("tldtwo").asBuilder().setDriveFolderId("hooray").build());

    createTld("tldthree");
    // You'd think this test was written around Christmas, but it wasn't.
    persistActiveDomain("dasher.tld");
    persistActiveDomain("prancer.tld");
    persistActiveDomain("rudolph.tldtwo");
    persistActiveDomain("santa.tldtwo");
    persistActiveDomain("buddy.tldtwo");
    persistActiveDomain("cupid.tldthree");
    action.run();
    BlobId firstTldFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(firstTldFile), UTF_8).trim();
    assertThat(tlds).isEqualTo("dasher.tld,\nprancer.tld,");
    BlobId secondTldFile = BlobId.of("outputbucket", "tldtwo.txt");
    String moreTlds = new String(gcsUtils.readBytesFrom(secondTldFile), UTF_8).trim();
    assertThat(moreTlds).isEqualTo("buddy.tldtwo,\nrudolph.tldtwo,\nsanta.tldtwo,");
    BlobId thirdTldFile = BlobId.of("outputbucket", "tldthree.txt");
    String evenMoreTlds = new String(gcsUtils.readBytesFrom(thirdTldFile), UTF_8).trim();
    assertThat(evenMoreTlds).isEqualTo("cupid.tldthree,");
    verifyExportedToDrive("brouhaha", "registered_domains_tld.txt", "dasher.tld,\nprancer.tld,");
    verifyExportedToDrive(
        "hooray", "registered_domains_tldtwo.txt", "buddy.tldtwo,\nrudolph.tldtwo,\nsanta.tldtwo,");
    // tldthree does not have a drive id, so no export to drive is performed.
    verifyNoMoreInteractions(driveConnection);
  }

  private void persistFeatureFlag(FeatureFlag.FeatureStatus status) {
    persistResource(
        new FeatureFlag()
            .asBuilder()
            .setFeatureName(INCLUDE_PENDING_DELETE_DATE_FOR_DOMAINS)
            .setStatusMap(ImmutableSortedMap.of(START_OF_TIME, status))
            .build());
  }
}
