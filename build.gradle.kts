plugins {
    id("com.android.application") version "8.10.1" apply false
    // Media3 1.11.1 dibangun dengan metadata Kotlin 2.2.x.
    // Samakan compiler project agar tidak gagal saat compileDebugKotlin.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
