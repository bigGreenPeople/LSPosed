/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.api.AndroidBasePlugin

plugins {
    alias(libs.plugins.lsplugin.cmaker)
    alias(libs.plugins.lsplugin.jgit)
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.nav.safeargs) apply false
}

cmaker {
    default {
        arguments.addAll(
            arrayOf(
                "-DEXTERNAL_ROOT=${File(rootDir.absolutePath, "external")}",
                "-DCORE_ROOT=${File(rootDir.absolutePath, "core/src/main/jni")}",
                "-DANDROID_STL=none"
            )
        )
        val flags = arrayOf(
            "-DINJECTED_AID=$injectedPackageUid",
            "-Wno-gnu-string-literal-operator-template",
            "-Wno-c++2b-extensions",
        )
        cFlags.addAll(flags)
        cppFlags.addAll(flags)
        abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
    buildTypes {
        if (it.name == "release") {
            arguments += "-DDEBUG_SYMBOLS_PATH=${buildDir.absolutePath}/symbols"
        }
    }
}

// 定义 Git 仓库信息的变量
val repo = jgit.repo()  // 初始化一个 Git 仓库对象
// 计算提交数量
val commitCount = (repo?.commitCount("refs/remotes/origin/master") ?: 1) + 4200
// 从仓库获取最新的标签或使用默认值
val latestTag = repo?.latestTag?.removePrefix("v") ?: "1.0"

// 定义注入包信息的变量
val injectedPackageName by extra("com.android.shell")
val injectedPackageUid by extra(2000)

// 定义默认管理器包信息的变量
val defaultManagerPackageName by extra("org.lsposed.manager")
val verCode by extra(commitCount)
val verName by extra(latestTag)

//下面都是写sdk的版本信息
val androidTargetSdkVersion by extra(34)
val androidMinSdkVersion by extra(27)
val androidBuildToolsVersion by extra("34.0.0")
val androidCompileSdkVersion by extra(34)
val androidCompileNdkVersion by extra("25.2.9519653")
val androidSourceCompatibility by extra(JavaVersion.VERSION_17)
val androidTargetCompatibility by extra(JavaVersion.VERSION_17)

// 注册一个任务来删除 build 目录
tasks.register("Delete", Delete::class) {
    delete(rootProject.buildDir)
}

subprojects {
    // 对于应用 Android 插件
    plugins.withType(AndroidBasePlugin::class.java) {
        // 配置 CommonExtension，这是 Android 插件的一个扩展
        extensions.configure(CommonExtension::class.java) {
            // 设置编译所需的 SDK 版本、NDK 版本、构建工具版本等
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidCompileNdkVersion
            buildToolsVersion = androidBuildToolsVersion

            externalNativeBuild {
                cmake {
                    version = "3.22.1+"
                }
            }

            // 配置默认配置，包括最小支持 SDK 版本、目标 SDK 版本、版本号和版本名
            defaultConfig {
                minSdk = androidMinSdkVersion
                if (this is ApplicationDefaultConfig) {
                    targetSdk = androidTargetSdkVersion
                    versionCode = verCode
                    versionName = verName
                }
            }

            // 配置 Lint，设置是否在错误出现时中止构建，以及是否检查 Release 构建
            lint {
                abortOnError = true
                checkReleaseBuilds = false
            }
            // 配置编译选项，设置源代码和目标兼容性
            compileOptions {
                sourceCompatibility = androidSourceCompatibility
                targetCompatibility = androidTargetCompatibility
            }
        }
    }
    // 对于 Java 插件
    plugins.withType(JavaPlugin::class.java) {
        // 配置 Java 插件扩展
        extensions.configure(JavaPluginExtension::class.java) {
            // 设置源代码和目标兼容性
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
}
