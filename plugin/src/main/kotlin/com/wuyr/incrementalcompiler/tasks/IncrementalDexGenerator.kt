package com.wuyr.incrementalcompiler.tasks

import com.wuyr.incrementalcompiler.common.buildToolsVersion
import com.wuyr.incrementalcompiler.common.println
import com.wuyr.incrementalcompiler.common.sdkDirectory
import org.gradle.api.DefaultTask
import org.gradle.internal.os.OperatingSystem
import java.io.File

/**
 * @author wuyr
 * @github https://github.com/wuyr/incremental-compiler
 * @since 2021-07-03 下午12:22
 */
open class IncrementalDexGenerator : DefaultTask() {
    companion object {
        const val TASK_NAME = "generateIncrementalDex"
    }

    /**
     * 生成增量dex
     */
    fun generate(compiler: IncrementalCompiler): String? {
        val compiledFiles = compiler.compileKotlin() + compiler.compileJava()
        if (compiledFiles.isEmpty()) return null
        if (compiledFiles.isNotEmpty()) {
            "Files involved in this compilation:\n${compiledFiles.joinToString("\n")}".println()
            val dexOutputDir = "${project.buildDir}/outputs/incremental_dex"
            val dexName = "classes.dex"
            if (makeDexByD8(dexOutputDir, dexName, compiledFiles)) {
                val dexFileDir = "$dexOutputDir/$dexName"
                "Incremental dex has been saved in: $dexFileDir".println()
                return dexFileDir
            }
        }
        return null
    }

    /**
     * 合并dex
     * @param dexName dex名称
     * @param finalDexPaths 需要进行合并的dex路径
     */
    fun mergeDex(dexName: String, finalDexPaths: List<String>) =
        makeDexByD8("${project.rootProject.buildDir}/outputs/merged_incremental_dex", dexName, finalDexPaths)

    /**
     * 生成dex文件
     * @param destinationDir 输出路径
     * @param dexName dex名称
     * @param inputFiles 目标文件
     */
    private fun makeDexByD8(destinationDir: String, dexName: String, inputFiles: List<String>): Boolean {
        val dexOutputDir = File(destinationDir).apply { mkdirs() }
        val d8Command = StringBuilder("${project.sdkDirectory}/build-tools/${project.buildToolsVersion}/d8")
            .append(" --output \"").append(dexOutputDir).append("\" ").append(" --debug ")
            .append(inputFiles.joinToString("\" \"", "\"", "\""))
        val platformArgs = (if (OperatingSystem.current().isWindows) arrayOf("cmd", "/C") else arrayOf("/bin/bash", "-c")).plus(d8Command.toString())
        val dexDir = File("$dexOutputDir/$dexName").apply { delete() }
        val process = Runtime.getRuntime().exec(platformArgs).apply { waitFor() }
        return dexDir.exists().also { isSuccess ->
            if (isSuccess) {
                process.destroy()
            } else {
                val errorMessage = process.errorStream.reader().readText().also { process.destroy() }
                if (errorMessage.isNotEmpty()) {
                    throw IllegalStateException(errorMessage)
                }
            }
        }
    }
}