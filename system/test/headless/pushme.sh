#!/bin/bash

## Cuttlefish 64 bit bt_headless installation
PRODUCT=vsoc_x86_64

## Ensure that the device storage has been remounted
adb root
adb remount -R
adb wait-for-device

## Push various shared libraries where the executable expects to find them
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/bin/bt_headless /system/bin/bt_headless
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/lib64/android.hardware.bluetooth.audio@2.0.so /system/lib64/android.hardware.bluetooth.audio@2.0.so
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/lib64/android.hardware.bluetooth.audio@2.1.so /system/lib64/android.hardware.bluetooth.audio@2.1.so
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/lib64/android.hardware.bluetooth@1.0.so /system/lib64/android.hardware.bluetooth@1.0.so
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/lib64/android.hardware.bluetooth@1.1.so /system/lib64/android.hardware.bluetooth@1.1.so
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/lib64/android.hardware.bluetooth.audio-V4-ndk.so /system/lib64/android.hardware.bluetooth.audio-V4-ndk.so
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/lib64/android.hardware.audio.common-V3-ndk.so /system/lib64/android.hardware.audio.common-V3-ndk.so
adb push ${ANDROID_BUILD_TOP}/out/target/product/${PRODUCT}/symbols/system/lib64/android.media.audio.common.types-V3-ndk.so /system/lib64/android.media.audio.common.types-V3-ndk.so
