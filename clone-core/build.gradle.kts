plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    compileOnly(project(":plugin-api"))
    testImplementation(project(":plugin-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
