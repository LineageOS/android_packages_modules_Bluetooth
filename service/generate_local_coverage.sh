#!/usr/bin/env bash

set -e
if [ -z "${ANDROID_BUILD_TOP}" ]
then
    printf "ANDROID_BUILD_TOP is not set. Please source the android env:\n\tsource build/envsetup.sh\n"
    false
fi
if [ ! -f "${ANDROID_BUILD_TOP}/out/host/linux-x86/framework/jacoco-cli.jar" ]
then
    printf "You need to build jacoco-cli in order to run coverage tool:\n\tm jacoco-cli\n"
    false
fi

# Build test independently, to prevent spamming logs in the terminal if it doesn't build
m ServiceBluetoothRoboTests

# Setup tmp folder
COVERAGE_TMP_FOLDER=/tmp/coverage_robolectric
rm -rf ${COVERAGE_TMP_FOLDER}
mkdir -p ${COVERAGE_TMP_FOLDER}/com/android/server/

# Run test + collect coverage
script -q ${COVERAGE_TMP_FOLDER}/atest_log \
  -c 'atest ServiceBluetoothRoboTests -- \
  --jacocoagent-path gs://tradefed_test_resources/teams/code_coverage/jacocoagent.jar \
  --coverage --coverage-toolchain JACOCO'

# "Atest results and logs directory: /tmp/path<CR><LF>" -> "/tmp/path"
COVERAGE_COLLECTED=$(rg 'Atest results and logs directory:' ${COVERAGE_TMP_FOLDER}/atest_log | cut -d ':' -f 2 | tr -d '\r' | xargs)

# Link source into the tmp folder
ln -s "${ANDROID_BUILD_TOP}"/packages/modules/Bluetooth/service/src ${COVERAGE_TMP_FOLDER}/com/android/server/bluetooth

# Extract class coverage and delete unwanted class for the report
unzip "${ANDROID_HOST_OUT}"/testcases/ServiceBluetoothRoboTests/ServiceBluetoothRoboTests.jar "com/android/server/bluetooth*" -d ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip
# shellcheck disable=SC2046
rm -rf $(find ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip -iname "*test*")
rm -rf $(find ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip -iname "*SystemServiceMessage*")
# shellcheck disable=SC2016
rm -rf ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip/com/android/server/bluetooth/'BluetoothAdapterState$waitForState$3$invokeSuspend$$inlined$filter$1.class'
# shellcheck disable=SC2016
rm -rf ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip/com/android/server/bluetooth/'BluetoothAdapterState$waitForState$3$invokeSuspend$$inlined$filter$1$2.class'
# shellcheck disable=SC2016
rm -rf ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip/com/android/server/bluetooth/'BluetoothAdapterState$waitForState$3$invokeSuspend$$inlined$filter$1$2$1.class'
rm -rf ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip/com/android/server/bluetooth/R.class

# Generate report:
# You may want to run "m jacoco-cli" if above command failed
java -jar "${ANDROID_BUILD_TOP}"/out/host/linux-x86/framework/jacoco-cli.jar report "${COVERAGE_COLLECTED}"/log/invocation_*/inv_*/ServiceBluetoothRoboTests/coverage_*.ec --classfiles ${COVERAGE_TMP_FOLDER}/ServiceBluetoothRobo_unzip --html ${COVERAGE_TMP_FOLDER}/coverage --name coverage.html --sourcefiles ${COVERAGE_TMP_FOLDER}

# Start python server and expose URL
printf "Url to connect to the coverage \033[32m http://%s:8000 \033[0m\n" "$(hostname)"
python3 -m http.server --directory ${COVERAGE_TMP_FOLDER}/coverage
