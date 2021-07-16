package com.wuyr.incrementalcompiler

import com.wuyr.incrementalcompiler.common.isLibrary
import com.wuyr.incrementalcompiler.common.println
import com.wuyr.incrementalcompiler.tasks.IncrementalCompiler
import com.wuyr.incrementalcompiler.tasks.IncrementalDexGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * @author wuyr
 * @github https://github.com/wuyr/incremental-compiler
 * @since 2021-06-08 下午5:18
 */
class CompileTaskRegister : Plugin<Project> {

    companion object {
        const val COMPILE_TASK_GROUP = "incremental"
        var moduleInvolvedCount = 0
        var dexPaths = ArrayList<String?>()
    }

    override fun apply(target: Project) {
        moduleInvolvedCount++
        target.tasks.register(IncrementalCompiler.TASK_NAME, IncrementalCompiler::class.java) {
            it.apply {
                group = COMPILE_TASK_GROUP
                generateRFileIfNeeded(this)
                doLast {
                    val compiledFiles = compileKotlin() + compileJava()
                    if (compiledFiles.isNotEmpty()) {
                        "Files involved in this compilation:\n${compiledFiles.joinToString("\n")}".println()
                    }
                }
            }
        }
        target.tasks.register(IncrementalDexGenerator.TASK_NAME, IncrementalDexGenerator::class.java) {
            it.apply {
                group = COMPILE_TASK_GROUP
                val compiler = target.tasks.findByName(IncrementalCompiler.TASK_NAME) as IncrementalCompiler
                generateRFileIfNeeded(compiler)
                doLast {
                    dexPaths.add(generate(compiler))
                    if (dexPaths.size == moduleInvolvedCount) {
                        dexPaths.filterNotNull().distinct().let { finalDexPaths ->
                            if (finalDexPaths.isNotEmpty()) {
                                if (mergeDex("classes.dex", finalDexPaths)) {
                                    "All incremental dex has been merged in: ${project.rootProject.buildDir}/outputs/merged_incremental_dex/classes.dex".println()
                                }
                            }
                        }
                        moduleInvolvedCount = 0
                        dexPaths.clear()
                    }
                }
            }
        }
    }

    private fun DefaultTask.generateRFileIfNeeded(compiler: IncrementalCompiler) {
        if (compiler.rFileNeeded) {
            dependsOn(":${project.name}:${if (project.isLibrary) "generateDebugRFile" else "processDebugResources"}")
        }
    }
}
