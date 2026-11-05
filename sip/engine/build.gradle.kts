plugins {
    alias(libs.plugins.android.library)
}

// SIP engine boundary (spec §4.3). Features talk to SipEngine only; the PJSUA2 implementation
// arrives in Phase 1 together with :sip:pjsip-native.
android {
    namespace = "com.simplysip.phone.sip.engine"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
