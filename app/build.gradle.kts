plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}


android {
    namespace = "com.translator.kapamtalk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.translator.kapamtalk"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("com.android.volley:volley:1.2.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("de.hdodenhof:circleimageview:3.1.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("com.google.firebase:firebase-auth:21.0.1")
    implementation(platform("com.google.firebase:firebase-bom:33.8.0"))
    implementation("com.google.firebase:firebase-database:21.0.0")
    testImplementation(libs.junit)
    implementation(libs.material.v190)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}



