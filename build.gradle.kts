buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        val agpVersion = "8.13.2"
        val kotlinVersion = "1.9.24"
        classpath("com.android.tools.build:gradle:$agpVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}