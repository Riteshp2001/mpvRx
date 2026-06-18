# Task: Strict ARM64-v8a ABI Restriction

- [x] Cleanup `app/build.gradle.kts` variables (`enableX86`, `x86Abis`)
- [x] Configure strict ABI filters in `defaultConfig`
- [x] Add JNI library excludes for other ABIs in `packaging`
- [x] Clean up `splits` configuration
- [x] Verify changes with Gradle sync
