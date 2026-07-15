plugins {
    `kotlin-dsl`
}

group = "app.shareguard.buildlogic"

dependencies {
    implementation("com.android.tools.build:gradle:9.3.0")
}

gradlePlugin {
    plugins {
        register("androidLibraryConvention") {
            id = "shareguard.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
