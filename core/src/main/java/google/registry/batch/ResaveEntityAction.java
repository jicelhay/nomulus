// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESAVE_TIMES;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.EppResource;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * An action that re-saves a given entity, typically after a certain amount of time has passed.
 *
 * <p>{@link EppResource}s will be projected forward to the current time.
 */
@Action(
    service = GaeService.BACKEND,
    path = ResaveEntityAction.PATH,
    auth = Auth.AUTH_ADMIN,
    method = Method.POST)
public class ResaveEntityAction implements Runnable {

  public static final String PATH = "/_dr/task/resaveEntity";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String resourceKey;
  private final DateTime requestedTime;
  private final ImmutableSortedSet<DateTime> resaveTimes;
  private final AsyncTaskEnqueuer asyncTaskEnqueuer;
  private final Response response;

  @Inject
  ResaveEntityAction(
      @Parameter(PARAM_RESOURCE_KEY) String resourceKey,
      @Parameter(PARAM_REQUESTED_TIME) DateTime requestedTime,
      @Parameter(PARAM_RESAVE_TIMES) ImmutableSet<DateTime> resaveTimes,
      AsyncTaskEnqueuer asyncTaskEnqueuer,
      Response response) {
    this.resourceKey = resourceKey;
    this.requestedTime = requestedTime;
    this.resaveTimes = ImmutableSortedSet.copyOf(resaveTimes);
    this.asyncTaskEnqueuer = asyncTaskEnqueuer;
    this.response = response;
  }

  @Override
  public void run() {
    logger.atInfo().log(
        "Re-saving entity %s which was enqueued at %s.", resourceKey, requestedTime);
    tm().transact(
            () -> {
              Optional<EppResource> entity =
                  tm().loadByKeyIfPresent(VKey.createEppVKeyFromString(resourceKey));
              if (entity.isEmpty()) {
                logger.atSevere().log(
                    "Could not re-save entity %s because it does not exist; failing permanently.",
                    resourceKey);
                return;
              }
              tm().put(entity.get().cloneProjectedAtTime(tm().getTransactionTime()));
              if (!resaveTimes.isEmpty()) {
                asyncTaskEnqueuer.enqueueAsyncResave(
                    VKey.createEppVKeyFromString(resourceKey), requestedTime, resaveTimes);
              }
            });
    response.setPayload("Entity re-saved.");
  }
}
