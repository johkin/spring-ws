#!/bin/bash -x

set -euo pipefail

RELEASE_TYPE=$1

export GRADLE_ENTERPRISE_CACHE_USERNAME=${GRADLE_ENTERPRISE_CACHE_USR}
export GRADLE_ENTERPRISE_CACHE_PASSWORD=${GRADLE_ENTERPRISE_CACHE_PSW}

echo 'Deploying to Artifactory...'

MAVEN_OPTS="-Duser.name=jenkins -Duser.home=/tmp/jenkins-home" ./mvnw \
  -s settings.xml \
  -Pjakarta-ee-10,distribute,${RELEASE_TYPE} \
  -Dmaven.test.skip=true \
  clean deploy -B
