plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.plugins.kotlin.multiplatform.get().let {
        "org.jetbrains.kotlin:kotlin-gradle-plugin:${it.version}"
    })
    compileOnly(libs.plugins.android.kmp.library.get().let {
        "com.android.tools.build:gradle:${it.version}"
    })
}
