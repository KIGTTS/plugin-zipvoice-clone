plugins { id("com.android.library"); kotlin("android") }
kotlin {
    // sherpa's JNI looks up invoke(float[]): Integer; indy Function1 bridges erase that method.
    compilerOptions { freeCompilerArgs.add("-Xlambdas=class") }
}
android {
    namespace = "com.kigtts.clone.zipvoice"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
}
dependencies {
    compileOnly(project(":plugin-android-api"))
    implementation(project(":clone-core"))
    compileOnly(files("libs/sherpa-onnx-2.0.1.aar"))
    androidTestImplementation(project(":plugin-android-api"))
    androidTestImplementation(files("libs/sherpa-onnx-2.0.1.aar"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
