# Fuzzer Validation Tests

These tests validate that Bluetooth fuzzers continue to run as expected, i.e. without crashing, on a
given corpus. By running in presubmit, the fuzzers can have as much uptime as possible and avoid
inadvertent breakage caused by changes to the Bluetooth stack.

## Running a fuzzer validation test in development environment

Fuzzer validation tests are implemented as GTests and can be run as follows in a repo checkout:

```sh
source build/envsetup.sh # If not done already
lunch aosp_arm64-trunk_staging-eng # If not done already
atest rfcomm-fuzzer-validation-test
```

## Debugging a fuzzer validation test crash

Fuzzer validation tests are compiled with hwasan turned on, as they are for the Haiku-managed fuzzer
runs.

### Symbolization of crash stack traces

When a crash happens its details are dumped in logcat but not necessarily included in test output.

To get a stack trace of the crash, run the following command immediately after the test runs locally
against a development device with hwasan enabled:

```sh
source build/envsetup.sh # If not done already
lunch aosp_arm64-trunk_staging-eng # If not done already
adb logcat -d | stack -v - # Do this, OR
stack -v /tmp/atest_result_*/LATEST/log/invocation_*/inv_*/device_logcat_test_localhost*.txt
```

If there is a crash present in logcat, you should get output similar to this:

```sh
Stack Trace:
  RELADDR           FUNCTION                                                                                                                                                                                        FILE:LINE
  v-------------->  FuzzAsServer(FuzzedDataProvider*)                                                                                                                                                               packages/modules/Bluetooth/system/stack/fuzzers/rfcomm_fuzzer.cc:140
  00000000001720d0  LLVMFuzzerTestOneInput+1664                                                                                                                                                                     packages/modules/Bluetooth/system/stack/fuzzers/rfcomm_fuzzer.cc:213
  0000000000060478  runFuzzerOnCorpusAndExitProcess(std::__1::basic_string<char, std::__1::char_traits<char>, std::__1::allocator<char>> const&)+956                                                                packages/modules/Bluetooth/system/stack/fuzzers/test/fuzzer_validation_test.cc:45
  00000000001abb04  RfcommFuzzerValidationTest_DoesNotCrashOnCorpus_Test::TestBody()+1216                                                                                                                           packages/modules/Bluetooth/system/stack/fuzzers/test/rfcomm/rfcomm_fuzzer_validation_test.cc:49
  v-------------->  void testing::internal::HandleExceptionsInMethodIfSupported<testing::Test, void>(testing::Test*, void (testing::Test::*)(), char const*)                                                        external/googletest/googletest/src/gtest.cc:0
  00000000001c9ee0  testing::Test::Run()+632                                                                                                                                                                        external/googletest/googletest/src/gtest.cc:2713
  00000000001cb058  testing::TestInfo::Run()+824                                                                                                                                                                    external/googletest/googletest/src/gtest.cc:2859
  00000000001cc4cc  testing::TestSuite::Run()+1276                                                                                                                                                                  external/googletest/googletest/src/gtest.cc:3037
  00000000001ea550  testing::internal::UnitTestImpl::RunAllTests()+3140                                                                                                                                             external/googletest/googletest/src/gtest.cc:5968
  v-------------->  bool testing::internal::HandleExceptionsInMethodIfSupported<testing::internal::UnitTestImpl, bool>(testing::internal::UnitTestImpl*, bool (testing::internal::UnitTestImpl::*)(), char const*)  external/googletest/googletest/src/gtest.cc:0
  00000000001e9700  testing::UnitTest::Run()+284                                                                                                                                                                    external/googletest/googletest/src/gtest.cc:5547
  v-------------->  RUN_ALL_TESTS()                                                                                                                                                                                 external/googletest/googletest/include/gtest/gtest.h:2334
  00000000001ab360  main+204                                                                                                                                                                                        packages/modules/Bluetooth/system/stack/fuzzers/test/rfcomm/rfcomm_fuzzer_validation_test.cc:45
  000000000005eaec  __libc_init+172                                                                                                                                                                                 /apex/com.android.runtime/lib64/bionic/hwasan/libc.so
```

Use the stacktrace present in the last column to diagnose the crash, and make appropriate changes,
either in the fuzzer code, or in the current CL.
