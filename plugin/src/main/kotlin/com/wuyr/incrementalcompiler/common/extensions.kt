package com.wuyr.incrementalcompiler.common

import com.wuyr.incrementalcompiler.utils.get
import com.wuyr.incrementalcompiler.utils.invoke
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * @author wuyr
 * @github https://github.com/wuyr/incremental-compiler
 * @since 2021-07-06 下午4:47
 */

private const val APP_PLUGIN_NAME = "com.android.build.gradle.internal.plugins.AppPlugin"
private const val APP_PLUGIN_NAME_OLD = "com.android.build.gradle.AppPlugin"
private const val LIBRARY_PLUGIN_NAME = "com.android.build.gradle.internal.plugins.LibraryPlugin"
private const val LIBRARY_PLUGIN_NAME_OLD = "com.android.build.gradle.LibraryPlugin"

val Project.plugin: Plugin<*>
    get() = plugins.run {
        runCatching {
            val androidPlugin = findPlugin("android")!!
            androidPlugin::class.get<Project>(androidPlugin, "project")!!.plugins.find {
                it.javaClass.name == APP_PLUGIN_NAME || it.javaClass.name == APP_PLUGIN_NAME_OLD
            }!!
        }.getOrElse { find { it.javaClass.name == LIBRARY_PLUGIN_NAME || it.javaClass.name == LIBRARY_PLUGIN_NAME_OLD }!! }
    }

val Plugin<*>.isLibrary: Boolean get() = javaClass.name == LIBRARY_PLUGIN_NAME || javaClass.name == LIBRARY_PLUGIN_NAME_OLD

val Project.isLibrary: Boolean get() = plugin.isLibrary

val Project.buildToolsVersion: String
    get() = plugin.let { plugin ->
        val extension = plugin::class.get<Any>(plugin, "extension")!!
        extension::class.invoke<String>(extension, "getBuildToolsVersion")!!
    }

val Project.sdkDirectory: String
    get() = plugin.let { plugin ->
        val extension = plugin::class.get<Any>(plugin, "extension")!!
        extension::class.invoke<File>(extension, "getSdkDirectory")!!.absolutePath
    }

infix fun String.largerThan(target: String): Boolean {
    //对比索引记录
    var originCompareCursor = 0
    var targetCompareCursor = 0
    val digitRange = '0'..'9'
    while (originCompareCursor < length && targetCompareCursor < target.length) {
        //数字结束索引
        var originDigitSegmentEndIndex = originCompareCursor
        for (index in originCompareCursor until length) {
            if (this[index] !in digitRange) break
            originDigitSegmentEndIndex++
        }
        var targetDigitSegmentEndIndex = targetCompareCursor
        for (index in targetCompareCursor until target.length) {
            if (target[index] !in digitRange) break
            targetDigitSegmentEndIndex++
        }
        if (originDigitSegmentEndIndex > originCompareCursor && targetDigitSegmentEndIndex > targetCompareCursor) {
            //数字长度
            val originDigitSegmentCount = originDigitSegmentEndIndex - originCompareCursor
            val targetDigitSegmentCount = targetDigitSegmentEndIndex - targetCompareCursor
            if (originDigitSegmentCount != targetDigitSegmentCount) {
                //长度不相等
                return (originDigitSegmentCount - targetDigitSegmentCount) > 0
            }
            repeat(originDigitSegmentCount) { index ->
                if (this[originCompareCursor + index] != target[targetCompareCursor + index]) {
                    //数字不相等
                    return (this[originCompareCursor + index] - target[targetCompareCursor + index]) > 0
                }
            }
            originCompareCursor = originDigitSegmentEndIndex
            targetCompareCursor = targetDigitSegmentEndIndex
        } else {
            //其中一方无数字
            if (this[originCompareCursor] != target[targetCompareCursor]) {
                return (this[originCompareCursor] - target[targetCompareCursor]) > 0
            }
            originCompareCursor++
            targetCompareCursor++
        }
    }
    //部分内容完全相同
    return (length - target.length) > 0
}

fun Any?.println() = println(toString())