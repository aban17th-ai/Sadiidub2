plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
