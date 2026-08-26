plugins {
    alias(libs.plugins.android.application)
}

fun readRepoVersion(): Pair<String, Int> {
    val f = rootProject.file("../VERSION")
    val name = if (f.exists()) f.readText().trim().removePrefix("v") else "1.0.0"
    val parts = name.split(".")
    fun part(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0
    val code = part(0) * 10000 + part(1) * 100 + part(2)
    return name to maxOf(code, 1)
}

android {
    namespace = "com.mk.androidtransfer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mk.androidtransfer"
        minSdk = 24
        targetSdk = 35
        // CI 可用 VERSION_CODE / VERSION_NAME 覆盖；本地读仓库根 VERSION
        val (repoName, repoCode) = readRepoVersion()
        versionCode = (System.getenv("VERSION_CODE") ?: repoCode.toString()).toInt()
        versionName = System.getenv("VERSION_NAME") ?: repoName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 只有环境变量里给了可用的 keystore 才建签名配置，
    // 否则 release 构建走无签名产物，而不是拿一个不存在的文件去签名报错。
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // 网络库
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // 扫码连接：桌面端的二维码要能被 App 直接扫，而不是丢给系统浏览器
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // 图片加载
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // 协程支持
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}