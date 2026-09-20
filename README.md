# APKRepacker - Android Gradle Plugin

[![Maven Central](https://img.shields.io/maven-central/v/cn.lalaki.buildSrc/buildSrc.svg?label=Maven%20Central&logo=sonatype)](https://central.sonatype.com/artifact/cn.lalaki.buildSrc/buildSrc)
[![License: Apache-2.0 (shields.io)](https://img.shields.io/badge/License-Apache--2.0-c02041?logo=apache)](https://github.com/lalakii/APKRepacker?tab=Apache-2.0-1-ov-file)

**APKRepacker** is a gradle plugin that helps you automatically repackage and modify your APK after building. It makes it easier for developers to customize their APKs—such as removing content or adding resources—saving you from manual work.

This plugin is compatible with **AGP 9**, ensuring seamless integration with the latest Android build tools and features.

## Configuration

Add the following to your file: app/build.gradle, see: [app/build.gradle](https://github.com/lalakii/APKRepacker/tree/master/app/build.gradle)

```groovy
plugins {
    id "cn.lalaki.buildSrc" version "1.2.2"
}

def yourTask = tasks.register("yourTaskName") {
    doLast {
        println "This is your custom task."
    }
}

// configuration
repackageConfig {
    // *Sign your apk
    autoSign = true
    storeFile = "D:\\lalaki-new-1.jks"
    keyAlias = "0"
    ksPass = "0"
    keyPass = "0"
    signerName = "hello"

    // *Remove version-control-info.textproto and app-metadata.properties
    includeMetaData = false

    // *Remove DebugProbesKt.bin
    debugKt = false

    // *Remove custom files from the APK. support '*' wildcard
    blackList = List.of("*.version", "lib/armeabi-v7a/libbarhopper_v3.so")

    // *Add file to apk assets dir, only file! can't add dir!
    addAssetsList = List.of("D:\\test.txt")

    // *Print the APK path to the console.
    printOutputApk = true

    // *After APKRepacker Task
    doAfterTask = yourTask.getName()  // or doAfterTask = "yourTaskName"

    // *Disable this plugin.
    disabled = false
}
```

## License

[The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)