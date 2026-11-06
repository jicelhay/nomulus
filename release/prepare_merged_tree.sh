#!/bin/bash
# Copyright 2026 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# This script prepares the merged (public + internal) tree that the release
# artifacts are built from. It must be run from the root of the public repo.
# It is used by release/cloudbuild-release.yaml, and by
# release/cloudbuild-nomulus.yaml and release/cloudbuild-proxy.yaml when the
# caller has not already prepared the tree.
#
# Subcommands:
#   merge: Merges the internal repo, checked out in ./nomulus-internal, into
#     the current tree and removes the environment configs from .gitignore.
#   pin: Hardcodes the digests of the images tagged with <tag_name> in the
#     Dockerfiles and the Cloud Build YAML files, generates the per-environment
#     Cloud Build YAML files and k8s manifests, and points the Gradle wrapper to
#     the Gradle binary in GCS. The images and the Gradle binary must already
#     have been uploaded by release/cloudbuild-release.yaml.
#
# Usage:
#   release/prepare_merged_tree.sh merge
#   release/prepare_merged_tree.sh pin <project_id> <tag_name>

set -e

usage() {
  echo "Usage: $0 merge"
  echo "       $0 pin <project_id> <tag_name>"
  exit 1
}

merge() {
  # Merge the repos.
  shopt -s dotglob
  rm -rf .git && rm -rf nomulus-internal/.git
  cp -rf nomulus-internal/* .
  rm -rf nomulus-internal
  # Remove environment configs from .gitignore
  sed -i \
      -e '\#core/src/main/java/google/registry/config/files/nomulus-config-alpha.yaml#d' \
      -e '\#core/src/main/java/google/registry/config/files/nomulus-config-crash.yaml#d' \
      -e '\#core/src/main/java/google/registry/config/files/nomulus-config-production.yaml#d' \
      -e '\#core/src/main/java/google/registry/config/files/nomulus-config-qa.yaml#d' \
      -e '\#core/src/main/java/google/registry/config/files/nomulus-config-sandbox.yaml#d' \
      .gitignore
}

pin() {
  # Do text replacement in the merged repo, hardcoding image digests.
  builder_digest=$(gcloud container images list-tags "gcr.io/${PROJECT_ID}/builder" \
    --format='get(digest)' --filter="tags = ${TAG_NAME}")
  jetty_digest=$(gcloud container images list-tags "gcr.io/${PROJECT_ID}/jetty" \
    --format='get(digest)' --filter="tags = ${TAG_NAME}")
  temurin_digest=$(gcloud container images list-tags "gcr.io/${PROJECT_ID}/temurin" \
    --format='get(digest)' --filter="tags = ${TAG_NAME}")
  sed -i s%eclipse-temurin:25%gcr.io/${PROJECT_ID}/temurin@$temurin_digest%g proxy/Dockerfile
  sed -i s%eclipse-temurin:25%gcr.io/${PROJECT_ID}/temurin@$temurin_digest%g core/Dockerfile
  sed -i s%jetty:12-jdk25%gcr.io/${PROJECT_ID}/jetty@$jetty_digest%g jetty/Dockerfile
  sed -i s/builder:latest/builder@$builder_digest/g release/cloudbuild-proxy.yaml
  sed -i s/builder:latest/builder@$builder_digest/g release/cloudbuild-nomulus.yaml
  sed -i s/builder:latest/builder@$builder_digest/g release/cloudbuild-deploy-gke.yaml
  sed -i s/builder:latest/builder@$builder_digest/g release/cloudbuild-sync-and-tag.yaml
  sed -i s/builder:latest/builder@$builder_digest/g release/cloudbuild-schema-deploy.yaml
  sed -i s/builder:latest/builder@$builder_digest/g release/cloudbuild-schema-verify.yaml
  sed -i s/builder:latest/builder@$builder_digest/g release/cloudbuild-restart-proxies.yaml
  sed -i s/GCP_PROJECT/${PROJECT_ID}/ proxy/kubernetes/proxy-*.yaml
  sed -i s/'${TAG_NAME}'/${TAG_NAME}/g release/cloudbuild-sync-and-tag.yaml
  sed -i s/'${TAG_NAME}'/${TAG_NAME}/g release/cloudbuild-deploy-gke.yaml
  for environment in alpha crash qa sandbox production; do
    sed s/'${_ENV}'/${environment}/g release/cloudbuild-deploy-gke.yaml \
      > release/cloudbuild-deploy-gke-${environment}.yaml
    sed s/'${_ENV}'/${environment}/g release/cloudbuild-restart-proxies.yaml \
      > release/cloudbuild-restart-proxies-${environment}.yaml
    sed s/'${_ENV}'/${environment}/g release/cloudbuild-restart-proxies.yaml | \
      sed s/proxy-deployment/proxy-deployment-canary/g \
      > release/cloudbuild-restart-proxies-${environment}-canary.yaml
  done

  # Do text replacement in the cloud build YAML files.
  builder_digest=$(gcloud container images list-tags "gcr.io/${PROJECT_ID}/builder" \
    --format='get(digest)' --filter="tags = ${TAG_NAME}")
  schema_deployer_digest=$(gcloud container images list-tags "gcr.io/${PROJECT_ID}/schema_deployer" \
    --format='get(digest)' --filter="tags = ${TAG_NAME}")
  schema_verifier_digest=$(gcloud container images list-tags "gcr.io/${PROJECT_ID}/schema_verifier" \
    --format='get(digest)' --filter="tags = ${TAG_NAME}")
  sed -i s/builder:latest/builder@$builder_digest/g \
    release/cloudbuild-schema-deploy.yaml
  sed -i s/builder:latest/builder@$builder_digest/g \
    release/cloudbuild-schema-verify.yaml
  sed -i s/builder:latest/builder@$builder_digest/g \
    release/cloudbuild-renew-prober-certs.yaml
  sed -i s/builder:latest/builder@$builder_digest/g \
    release/cloudbuild-sync-db-objects.yaml
  sed -i s/schema_deployer:latest/schema_deployer@$schema_deployer_digest/g \
    release/cloudbuild-schema-deploy.yaml
  sed -i s/schema_verifier:latest/schema_verifier@$schema_verifier_digest/g \
    release/cloudbuild-schema-verify.yaml
  sed -i s/'${TAG_NAME}'/${TAG_NAME}/g release/cloudbuild-schema-deploy.yaml
  for environment in alpha crash qa sandbox production; do
    sed s/'${_ENV}'/${environment}/g release/cloudbuild-schema-deploy.yaml \
      > release/cloudbuild-schema-deploy-${environment}.yaml
    sed s/'${_ENV}'/${environment}/g release/cloudbuild-schema-verify.yaml \
      > release/cloudbuild-schema-verify-${environment}.yaml
    sed s/'${_ENV}'/${environment}/g release/cloudbuild-renew-prober-certs.yaml \
      > release/cloudbuild-renew-prober-certs-${environment}.yaml
  done

  # Do text replacement in the k8s manifests.
  for env in alpha crash qa sandbox production
  do
    # This is the project where Nomulus runs, and we need it to correctly bind the k8s service
    # account to the GCP service account.
    if [ ${env} == production ]
    then
      project="domain-registry"
    else
      project="domain-registry-${env}"
    fi
    base_domain=$(grep baseDomain \
      ./core/src/main/java/google/registry/config/files/nomulus-config-${env}.yaml | \
      awk '{print $2}')
    for service in frontend backend pubapi console
    do
      # non-canary
      sed s/GCP_PROJECT/${PROJECT_ID}/g ./jetty/kubernetes/nomulus-${service}.yaml | \
        sed s/latest/${TAG_NAME}/g | \
        sed s/ENVIRONMENT/${env}/g | \
        sed s/PROXY_ENV/"${env}"/g | \
        sed s/EPP/"epp"/g > ./jetty/kubernetes/nomulus-${env}-${service}.yaml
      # Proxy '--log' flag does not work on production.
      if [ ${env} == production ]
      then
        sed -i s/--log,//g ./jetty/kubernetes/nomulus-${env}-${service}.yaml
      fi
      if [ ${service} == frontend ]
      then
        sed -i s/${PROJECT_ID}.iam.gserviceaccount.com/${project}.iam.gserviceaccount.com/g \
          ./jetty/kubernetes/nomulus-${env}-${service}.yaml
      fi
      # canary
      sed s/GCP_PROJECT/${PROJECT_ID}/g ./jetty/kubernetes/nomulus-${service}.yaml | \
        sed s/latest/${TAG_NAME}/g | \
        sed s/ENVIRONMENT/${env}/g | \
        sed s/PROXY_ENV/"${env}_canary"/g | \
        sed s/EPP/"epp-canary"/g | \
        sed s/${service}/${service}-canary/g | \
        # Undo prober endpoint replacement done in the previous line.
        # The link should stay as /ready/${service}.
        sed s/"ready\/${service}-canary"/"ready\/${service}"/g \
        > ./jetty/kubernetes/nomulus-${env}-${service}-canary.yaml
      # Proxy '--log' flag does not work on production.
      if [ ${env} == production ]
      then
        sed -i s/--log,//g ./jetty/kubernetes/nomulus-${env}-${service}-canary.yaml
      fi
      if [ ${service} == frontend ]
      then
        sed -i s/${PROJECT_ID}.iam.gserviceaccount.com/${project}.iam.gserviceaccount.com/g \
          ./jetty/kubernetes/nomulus-${env}-${service}-canary.yaml
      fi
      # partial phase manifests
      for stage in 1 5
      do
        if [ ${service} == backend ] || [ ${service} == console ]
        then
          replicas=1
        else
          replicas=${stage}
        fi
        awk 'NR==1,/^---$/ {if ($0 != "---") print}' ./jetty/kubernetes/nomulus-${env}-${service}.yaml | \
          sed s/name:\ ${service}/name:\ ${service}-partial-phase/g | \
          sed s/service:\ ${service}/deployment:\ ${service}-partial-phase/g | \
          sed s/value:\ ${service}/value:\ ${service}-partial-phase/g | \
          sed "/^spec:$/a\  replicas: ${replicas}" \
          > ./jetty/kubernetes/nomulus-${env}-${service}-partial-phase-${stage}.yaml
      done
      # gateway
      sed s/BASE_DOMAIN/${base_domain}/g \
        ./jetty/kubernetes/gateway/nomulus-route-${service}.yaml \
        > ./jetty/kubernetes/gateway/nomulus-route-${env}-${service}.yaml
      # GCP backend policy
        sed s/SERVICE/${service}/g ./jetty/kubernetes/gateway/nomulus-backend-policy-${env}.yaml \
          > ./jetty/kubernetes/gateway/nomulus-backend-policy-${env}-${service}.yaml
        sed s/SERVICE/${service}-canary/g ./jetty/kubernetes/gateway/nomulus-backend-policy-${env}.yaml \
          > ./jetty/kubernetes/gateway/nomulus-backend-policy-${env}-${service}-canary.yaml
    done
    # Generate manifests for epp-server (which doesn't use HTTP gateway routing)
    # Lowercase EPP-v2 to epp-v2 to match reserved static GCE IP names.
    sed s/GCP_PROJECT/${PROJECT_ID}/g ./jetty/kubernetes/nomulus-epp-server.yaml | \
      sed s/latest/${TAG_NAME}/g | \
      sed s/ENVIRONMENT/${env}/g | \
      sed s/EPP-v2/epp-v2/g > ./jetty/kubernetes/nomulus-${env}-epp-server.yaml
    # Generate partial-phase manifests for epp-server for Cloud Deploy canary stages
    for stage in 1 5
    do
      awk 'NR==1,/^---$/ {if ($0 != "---") print}' ./jetty/kubernetes/nomulus-${env}-epp-server.yaml | \
        sed s/name:\ epp-server/name:\ epp-server-partial-phase/g | \
        sed s/service:\ epp-server/deployment:\ epp-server-partial-phase/g | \
        sed s/value:\ epp-server/value:\ epp-server-partial-phase/g | \
        sed "/^spec:$/a\  replicas: ${stage}" \
        > ./jetty/kubernetes/nomulus-${env}-epp-server-partial-phase-${stage}.yaml
    done
  done

  # Point URL in Gradle wrapper to the Gradle binary uploaded to GCS by
  # release/cloudbuild-release.yaml.
  gcs_loc="domain-registry-maven-repository/gradle"
  sed -i s%services.gradle.org/distributions%storage.googleapis.com/${gcs_loc}% \
    gradle/wrapper/gradle-wrapper.properties
}

case "$1" in
  merge)
    if [[ $# -ne 1 ]]; then
      usage
    fi
    merge
    ;;
  pin)
    if [[ $# -ne 3 ]]; then
      usage
    fi
    PROJECT_ID="$2"
    TAG_NAME="$3"
    pin
    ;;
  *)
    usage
    ;;
esac
