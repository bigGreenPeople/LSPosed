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

import org.apache.commons.codec.binary.Hex
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.resopt)
}
// 声明模块的基本信息
val moduleName = "LSPosed"
val moduleBaseId = "lsposed"
val authors = "LSPosed Developers"
// Riru 模块信息
val riruModuleId = "lsposed"
val moduleMinRiruApiVersion = 26
val moduleMinRiruVersionName = "26.1.7"
val moduleMaxRiruApiVersion = 26

// 从根项目的 extra 配置中读取一些信息
val injectedPackageName: String by rootProject.extra
val injectedPackageUid: Int by rootProject.extra

val defaultManagerPackageName: String by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra

android {
    flavorDimensions += "api"

    buildFeatures {
        prefab = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.lsposed.lspd"
        multiDexEnabled = false

        buildConfigField(
            "String",
            "DEFAULT_MANAGER_PACKAGE_NAME",
            """"$defaultManagerPackageName""""
        )
        buildConfigField("String", "MANAGER_INJECTED_PKG_NAME", """"$injectedPackageName"""")
        buildConfigField("int", "MANAGER_INJECTED_UID", """$injectedPackageUid""")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }
    //创建两个风味Variant
    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments += "-DMODULE_NAME=${name.lowercase()}_$moduleBaseId"
                    arguments += "-DAPI=${name.lowercase()}"
                }
            }
        }

        create("Riru") {
            dimension = "api"
            externalNativeBuild {
                cmake {
                    arguments += "-DAPI_VERSION=$moduleMaxRiruApiVersion"
                }
            }
        }

        create("Zygisk") {
            dimension = "api"
            externalNativeBuild {
                cmake {
                    arguments += "-DAPI_VERSION=1"
                }
            }
        }
    }
    namespace = "org.lsposed.lspd"
}
abstract class Injected @Inject constructor(val magiskDir: String) {
    @get:Inject
    abstract val factory: ObjectFactory
}

dependencies {
    implementation(projects.core)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.managerService)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}
//先声明
val zipAll = task("zipAll") {
    group = "LSPosed"
}

// 定义一个函数，用于在构建配置评估后执行一些操作
fun afterEval() = android.applicationVariants.forEach { variant ->
    // 根据不同的变体（variant）、构建类型（buildType）、风味（flavor）生成不同的字符串格式
    //ZygiskDebug
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
    //zygiskdebug
    val variantLowered = variant.name.lowercase()
    val buildTypeCapped = variant.buildType.name.replaceFirstChar { it.uppercase() }
    //debug
    val buildTypeLowered = variant.buildType.name.lowercase()
    val flavorCapped = variant.flavorName!!.replaceFirstChar { it.uppercase() }
    //zygisk
    val flavorLowered = variant.flavorName!!.lowercase()

    //zygiskdebug
    val magiskDir = "$buildDir/magisk/$variantLowered"

    val moduleId = "${flavorLowered}_$moduleBaseId"
    //LSPosed-v1.0-6964-zygisk-debug.zip
    val zipFileName = "$moduleName-v$verName-$verCode-${flavorLowered}-$buildTypeLowered.zip"

    //动态创建task用于先编译相关项目 再打包到$buildDir/magisk/$variantLowered
    val prepareMagiskFilesTask = task<Sync>("prepareMagiskFiles$variantCapped") {
        group = "LSPosed"
        dependsOn(
            "assemble$variantCapped",
            ":app:package$buildTypeCapped",
            ":daemon:package$buildTypeCapped",
            ":dex2oat:externalNativeBuild${buildTypeCapped}"
        )
        into(magiskDir)
        from("${rootProject.projectDir}/README.md")
        from("$projectDir/magisk_module") {
            exclude("riru.sh", "module.prop", "customize.sh", "daemon")
        }
        from("$projectDir/magisk_module") {
            include("module.prop")
            expand(
                "moduleId" to moduleId,
                "versionName" to "v$verName",
                "versionCode" to verCode,
                "authorList" to authors,
                "updateJson" to "https://lsposed.github.io/LSPosed/release/${flavorLowered}.json",
                "requirement" to when (flavorLowered) {
                    "riru" -> "Requires Riru $moduleMinRiruVersionName or above installed"
                    "zygisk" -> "Requires Magisk 24.0+ and Zygisk enabled"
                    else -> "No further requirements"
                },
                "api" to flavorCapped,
            )
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        from("$projectDir/magisk_module") {
            include("customize.sh", "daemon")
            val tokens = mapOf(
                "FLAVOR" to flavorLowered,
                "DEBUG" to if (buildTypeLowered == "debug") "true" else "false"
            )
            filter<ReplaceTokens>("tokens" to tokens)
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        if (flavorLowered == "riru") {
            from("${projectDir}/magisk_module") {
                include("riru.sh")
                val tokens = mapOf(
                    "RIRU_MODULE_LIB_NAME" to "lspd",
                    "RIRU_MODULE_API_VERSION" to moduleMaxRiruApiVersion.toString(),
                    "RIRU_MODULE_MIN_API_VERSION" to moduleMinRiruApiVersion.toString(),
                    "RIRU_MODULE_MIN_RIRU_VERSION_NAME" to moduleMinRiruVersionName,
                )
                filter<ReplaceTokens>("tokens" to tokens)
                filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
            }
        }
        //获取名为 :app:package$buildTypeCapped 这个构建任务的输出目录。
        from(project(":app").tasks.getByName("package$buildTypeCapped").outputs) {
            include("*.apk")
            rename(".*\\.apk", "manager.apk")
        }
        from(project(":daemon").tasks.getByName("package$buildTypeCapped").outputs) {
            include("*.apk")
            rename(".*\\.apk", "daemon.apk")
        }
        //拷贝zygisk 注入的so
        into("lib") {
            from("${buildDir}/intermediates/stripped_native_libs/$variantCapped/out/lib") {
                include("**/liblspd.so")
            }
        }
        //拷贝dex2oat 我们现在暂时没有用到
        into("bin") {
            from("${project(":dex2oat").buildDir}/intermediates/cmake/$buildTypeLowered/obj") {
                include("**/dex2oat")
            }
        }
        //拷贝需要加载的dex
        val dexOutPath = if (buildTypeLowered == "release")
            "$buildDir/intermediates/dex/$variantCapped/minify${variantCapped}WithR8" else
            "$buildDir/intermediates/dex/$variantCapped/mergeDex$variantCapped"
        into("framework") {
            from(dexOutPath)
            rename("classes.dex", "lspd.dex")
        }

        //计算 SHA-256
        val injected = objects.newInstance<Injected>(magiskDir)
        doLast {
            injected.factory.fileTree().from(injected.magiskDir).visit {
                if (isDirectory) return@visit
                val md = MessageDigest.getInstance("SHA-256")
                file.forEachBlock(4096) { bytes, size ->
                    md.update(bytes, 0, size)
                }
                File(file.path + ".sha256").writeText(Hex.encodeHexString(md.digest()))
            }
        }
    }

    //动态创建task 打包具体zip
    val zipTask = task<Zip>("zip${variantCapped}") {
        group = "LSPosed"
        dependsOn(prepareMagiskFilesTask)
        archiveFileName = zipFileName
        destinationDirectory = file("$projectDir/release")
        from(magiskDir)
    }

    //前面已经定义 这里动态添加zip task
    zipAll.dependsOn(zipTask)

    /**
     * 获取 Android SDK 工具中的 adb（Android Debug Bridge）工具的路径。
     * androidComponents 是一个 Android Gradle 插件提供的对象，用于访问 Android 组件，如 SDK 组件、构建工具等。
     * sdkComponents 是 androidComponents 中的一个属性，用于访问 SDK 相关的组件。
     * adb 是 adb 工具的一个路径，通过调用 get() 方法来获取其位置。
     * asFile 用于将 adb 工具的路径转换为文件对象。
     * absolutePath 是获取文件对象的绝对路径。
     */
    val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
    //动态创建task push
    val pushTask = task<Exec>("push${variantCapped}") {
        group = "LSPosed"
        dependsOn(zipTask)
        workingDir("${projectDir}/release")
        commandLine(adb, "push", zipFileName, "/data/local/tmp/")
    }
    //使用命令刷入zip
    val flashTask = task<Exec>("flash${variantCapped}") {
        group = "LSPosed"
        dependsOn(pushTask)
        commandLine(
            adb, "shell", "su", "-c",
            "magisk --install-module /data/local/tmp/${zipFileName}"
        )
    }
    //刷入后重启
    task<Exec>("flashAndReboot${variantCapped}") {
        group = "LSPosed"
        dependsOn(flashTask)
        commandLine(adb, "shell", "reboot")
    }
}

//在 Gradle 对项目的所有配置和依赖进行评估并准备好后执行。即动态生成上面的task
afterEvaluate {
    afterEval()
}

//获取 Android SDK 工具中的 adb（Android Debug Bridge）工具的路径。
val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath

//杀死名为 lspd 的进程
val killLspd = task<Exec>("killLspd") {
    group = "LSPosed"
    commandLine(adb, "shell", "su", "-c", "killall", "lspd")
    isIgnoreExitValue = true
}

//push daemon的java代码
val pushDaemon = task<Exec>("pushDaemon") {
    group = "LSPosed"
    dependsOn(":daemon:assembleDebug")
    workingDir("${project(":daemon").buildDir}/outputs/apk/debug")
    commandLine(adb, "push", "daemon-debug.apk", "/data/local/tmp/daemon.apk")
}

//push daemon对应平台的so
val pushDaemonNative = task<Exec>("pushDaemonNative") {
    group = "LSPosed"
    dependsOn(":daemon:assembleDebug")
    doFirst {
        val abi: String = ByteArrayOutputStream().use { outputStream ->
            exec {
                commandLine(adb, "shell", "getprop", "ro.product.cpu.abi")
                standardOutput = outputStream
            }
            outputStream.toString().trim()
        }
        workingDir("${project(":daemon").buildDir}/intermediates/stripped_native_libs/debug/out/lib/$abi")
    }
    commandLine(adb, "push", "libdaemon.so", "/data/local/tmp/libdaemon.so")
}

//重新启动daemon程序 这里直接执行了service.sh
val reRunDaemon = task<Exec>("reRunDaemon") {
    group = "LSPosed"
    dependsOn(pushDaemon, pushDaemonNative, killLspd)
    // tricky to pass a minus number to avoid the injection warning
    commandLine(
        adb, "shell", "ASH_STANDALONE=1", "su", "-mm", "-pc",
        "/data/adb/magisk/busybox sh /data/adb/modules/*_lsposed/service.sh --system-server-max-retry=-1&"
    )
    isIgnoreExitValue = true
}


val tmpApk = "/data/local/tmp/lsp.apk"
//push 管理应用App
val pushApk = task<Exec>("pushApk") {
    group = "LSPosed"
    dependsOn(":app:assembleDebug")
    workingDir("${project(":app").buildDir}/outputs/apk/debug")
    commandLine(adb, "push", "app-debug.apk", tmpApk)
}
//打开管理应用
val openApp = task<Exec>("openApp") {
    group = "LSPosed"
    commandLine(
        adb, "shell",
        "am", "start", "-c", "org.lsposed.manager.LAUNCH_MANAGER",
        "com.android.shell/.BugreportWarningActivity"
    )
}

//重启管理应用
task<Exec>("reRunApp") {
    group = "LSPosed"
    dependsOn(pushApk)
    commandLine(adb, "shell", "su", "-c", "mv -f $tmpApk /data/adb/lspd/manager.apk")
    isIgnoreExitValue = true
    finalizedBy(reRunDaemon)
}

//evaluationDependsOn 是 Gradle 构建脚本中的一个方法，
//用于指定在评估项目时要依赖于哪些子项目的评估。它可以确保被依赖的项目在当前项目之前被评估。
evaluationDependsOn(":app")
evaluationDependsOn(":daemon")
