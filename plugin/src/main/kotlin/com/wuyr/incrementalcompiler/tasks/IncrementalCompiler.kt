package com.wuyr.incrementalcompiler.tasks

import com.wuyr.incrementalcompiler.common.FingerprinterRegistryDelegate
import com.wuyr.incrementalcompiler.common.isLibrary
import com.wuyr.incrementalcompiler.common.plugin
import com.wuyr.incrementalcompiler.common.println
import com.wuyr.incrementalcompiler.utils.*
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.changedetection.changes.ChangesOnlyIncrementalTaskInputs
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.collections.MinimalFileSet
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.properties.DefaultTaskProperties
import org.gradle.api.internal.tasks.properties.InputFilePropertySpec
import org.gradle.api.internal.tasks.properties.PropertyWalker
import org.gradle.api.internal.tasks.properties.TaskProperties
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import org.gradle.api.tasks.incremental.InputFileDetails
import org.gradle.cache.PersistentIndexedCache
import org.gradle.caching.internal.origin.OriginMetadata
import org.gradle.internal.execution.history.AfterPreviousExecutionState
import org.gradle.internal.execution.history.ExecutionHistoryStore
import org.gradle.internal.execution.history.changes.*
import org.gradle.internal.execution.history.impl.DefaultExecutionHistoryStore
import org.gradle.internal.fingerprint.FileCollectionFingerprint
import org.gradle.internal.fingerprint.impl.EmptyCurrentFileCollectionFingerprint
import org.gradle.internal.snapshot.impl.ImplementationSnapshot
import org.gradle.work.InputChanges
import java.io.File

/**
 * @author wuyr
 * @github https://github.com/wuyr/incremental-compiler
 * @since 2021-06-20 下午6:18
 */
open class IncrementalCompiler : DefaultTask() {

    companion object {
        const val TASK_NAME = "incrementalCompile"
        private const val COMPILE_KOTLIN_TASK = "compileDebugKotlin"
        private const val COMPILE_JAVA_TASK = "compileDebugJavaWithJavac"
    }

    /**
     * 是否需要生成R文件
     */
    @get: Internal
    open val rFileNeeded: Boolean
        get() = (project as ProjectInternal).run {
            // R file does not exist
            (((tasks.findByName(COMPILE_KOTLIN_TASK) as? AbstractCompile)?.classpath?.files ?: emptySet<File>())
                    + ((tasks.findByName(COMPILE_JAVA_TASK) as? AbstractCompile)?.classpath?.files ?: emptySet<File>()))
                .filter { it.name == "R.jar" }.any { !it.exists() }
                    // no local kotlin files compile records and the kotlin source code not empty
                    || (!services.get(ExecutionHistoryStore::class.java).load(":$name:$COMPILE_KOTLIN_TASK").isPresent
                    && (tasks.findByName(COMPILE_KOTLIN_TASK) as? AbstractCompile)?.source?.any { it.name.endsWith(".kt") } ?: false)
                    // no local java files compile records and the java source code not empty
                    || (!services.get(ExecutionHistoryStore::class.java).load(":$name:$COMPILE_JAVA_TASK").isPresent
                    && (tasks.findByName(COMPILE_JAVA_TASK) as? AbstractCompile)?.source?.any { it.name.endsWith(".java") } ?: false)
        }

    /**
     * 是否需要生成BuildConfig
     */
    private val buildConfigNeeded: Boolean
        get() = (project as ProjectInternal).run { !File(buildDir, "generated/source/buildConfig/debug").exists() }

    /**
     * 编译Kotlin源码
     */
    fun compileKotlin(): List<String> {
        if (buildConfigNeeded) {
            generateBuildConfig()
        }
        initCompileIfNeeded()
        var compiledFiles = emptyList<String>()
        val compileTask = (project.tasks.findByName(COMPILE_KOTLIN_TASK) as? AbstractCompile) ?: return emptyList()
        val sourcePostfix = ".kt"
        compileTask.doIncrementalCompile(COMPILE_KOTLIN_TASK, sourcePostfix) { inputChanges ->
            runCatching {
                compileTask::class.invokeVoid(
                    compileTask, "execute",
                    IncrementalTaskInputs::class to ChangesOnlyIncrementalTaskInputs(inputChanges.allFileChanges)
                )
            }.isSuccess.also { isSuccess ->
                if (isSuccess) {
                    val outputDir = compileTask.destinationDir
                    compiledFiles = inputChanges.allFileChanges.filter { it.file.name.endsWith(sourcePostfix) && !it.isRemoved }.map {
                        "${outputDir}/${(it as DefaultFileChange).normalizedPath.substringBeforeLast(".").replace("\\.", "/")}.class".run {
                            val classFile = File(this)
                            if (classFile.exists()) this else {
                                if (classFile.nameWithoutExtension.endsWith("Kt")) {
                                    "${classFile.parent}/${classFile.nameWithoutExtension.substringBeforeLast("Kt")}.class"
                                } else {
                                    "${classFile.parent}/${classFile.nameWithoutExtension}Kt.class"
                                }
                            }
                        }
                    }
                }
            }
        }
        return compiledFiles
    }

    /**
     * 编译Java源码
     */
    fun compileJava(): List<String> {
        if (buildConfigNeeded) {
            generateBuildConfig()
        }
        initCompileIfNeeded()
        var compiledFiles = emptyList<String>()
        val compileTask = (project.tasks.findByName(COMPILE_JAVA_TASK) as? AbstractCompile)?.apply {
            outputs.setPreviousOutputFiles(
                project.createFileCollection(name, HashSet<File>().apply {
                    add(File(project.buildDir, "generated/ap_generated_sources/debug/out").apply { mkdirs() })
                    add(File(project.buildDir, "intermediates/javac/debug/classes").apply { mkdirs() })
                })
            )
        } ?: return emptyList()
        val sourcePostfix = ".java"
        compileTask.doIncrementalCompile(COMPILE_JAVA_TASK, sourcePostfix) { inputChanges ->
            runCatching {
                JavaCompile::class.invokeVoid(compileTask, "compile", InputChanges::class to inputChanges)
            }.isSuccess.also { isSuccess ->
                if (isSuccess) {
                    val outputDir = compileTask.destinationDir
                    compiledFiles = inputChanges.allFileChanges.filter { it.file.name.endsWith(sourcePostfix) && !it.isRemoved }.map {
                        "${outputDir}/${(it as DefaultFileChange).normalizedPath.substringBeforeLast(".").replace("\\.", "/")}.class"
                    }
                }
            }
        }
        return compiledFiles
    }

    private fun generateBuildConfig() = project.tasks.findByName("generateDebugBuildConfig")?.let { it::class.invokeVoid(it, "doTaskAction") }

    private var androidTasksCreated = false

    private fun initCompileIfNeeded() {
        if (!androidTasksCreated) {
            project.plugin.run {
                if (!isLibrary) {
                    initCompileSdkVersion()
                    createAndroidTasks()
                    androidTasksCreated = true
                }
            }
        }
    }

    private inline fun AbstractCompile.doIncrementalCompile(taskName: String, sourcePostfix: String, doCompile: (InputChangesInternal) -> Boolean) {
        if (source.isEmpty || source.none { it.name.endsWith(sourcePostfix) }) {
            "${project.name}:$taskName empty source, skipped compile.".println()
            return
        }
        val (inputChanges, currentInputFileProperties) =
            if (taskName == COMPILE_JAVA_TASK) computeJavaChanges() else computeKotlinChanges()
        val fileChanges = inputChanges.allFileChanges
        if (!fileChanges.iterator().hasNext() || fileChanges.none { it.file.name.endsWith(sourcePostfix) }) {
            "${project.name}:$taskName no source has changes, skipped compile.".println()
            return
        }
        fileChanges.logChanges(taskName)
        if (doCompile(inputChanges)) {
            currentInputFileProperties.saveCompileRecords(taskName)
        }
    }

    /**
     * 更新本次编译记录
     */
    private fun Map<String, FileCollectionFingerprint>.saveCompileRecords(taskName: String) {
        val storeKey = ":${project.name}:$taskName"
        val defaultExecutionHistoryStore = (project as ProjectInternal).services.get(ExecutionHistoryStore::class.java)
        val optional = defaultExecutionHistoryStore.load(storeKey)
        if (!optional.isPresent) {
            throw IllegalStateException("Please run 'assembleDebug' task first!")
        }
        val lastState = optional.get()
        lastState::class.java.set(lastState, "inputFileProperties", this)
        defaultExecutionHistoryStore::class.get<PersistentIndexedCache<String, AfterPreviousExecutionState>>(
            defaultExecutionHistoryStore, "store"
        )!!.put(storeKey, lastState)
        "$storeKey compile records has saved".println()
    }

    private fun MutableIterable<InputFileDetails>.logChanges(taskName: String) {
        "============================".println()
        "${project.name}:$taskName file changes:".println()
        forEach { it.println() }
        "============================".println()
    }

    private fun AbstractCompile.computeJavaChanges() = computeChanges(COMPILE_JAVA_TASK)

    private fun AbstractCompile.computeKotlinChanges() = computeChanges(COMPILE_KOTLIN_TASK)

    /**
     * 根据文件指纹计算有变更的文件
     */
    private fun AbstractCompile.computeChanges(taskName: String): Pair<InputChangesInternal, Map<String, FileCollectionFingerprint>> {
        val target = project as ProjectInternal
        var optional = target.services.get(ExecutionHistoryStore::class.java).load(":${project.name}:$taskName")
        if (!optional.isPresent || isFullCompilation) {
            initStore(taskName)
            optional = target.services.get(ExecutionHistoryStore::class.java).load(":${project.name}:$taskName")
        }
        val lastState = optional.get()
        val lastInputFileProperties = lastState::class.java.get<Map<String, FileCollectionFingerprint>>(lastState, "inputFileProperties")!!
        val currentInputFilePropertiesBuilder = lastInputFileProperties::class.invoke<Any>(null, "naturalOrder")!!
        val immutableBiMapClass = lastInputFileProperties::class.java.classLoader.loadClass("com.google.common.collect.ImmutableBiMap")
        val immutableBiMapBuilder = immutableBiMapClass.invoke<Any>(null, "builder")!!

        fun Any.put(key: Any, value: Any) = this::class.invokeVoid(this, "put", Any::class to key, Any::class to value)

        val fingerPrinter = FingerprinterRegistryDelegate.create(target)
        DefaultTaskProperties::class.get<Set<InputFilePropertySpec>>(compileTaskProperties, "inputFileProperties")!!.onEach {
            val value = it.value
            val incremental = it.isIncremental || it.isSkipWhenEmpty
            val propertyName = it.propertyName
            val fingerprint = fingerPrinter.fingerprint(it)
            if (incremental && value != null) {
                immutableBiMapBuilder.put(propertyName, value)
            }
            currentInputFilePropertiesBuilder.put(propertyName, fingerprint)
        }
        val immutableBiMap = immutableBiMapBuilder::class.invoke<Map<String, Any>>(immutableBiMapBuilder, "build")!!
        val currentInputFileProperties =
            currentInputFilePropertiesBuilder::class.invoke<Map<String, FileCollectionFingerprint>>(currentInputFilePropertiesBuilder, "build")!!
        val incrementalInputProperties = DefaultIncrementalInputProperties::class.newInstance<DefaultIncrementalInputProperties>(
            immutableBiMapClass.kotlin to immutableBiMap::class.java.cast(immutableBiMap)
        )!!
        val inputFileChanges = incrementalInputProperties::class.invoke<InputFileChanges>(
            incrementalInputProperties, "incrementalChanges",
            lastInputFileProperties::class to lastInputFileProperties,
            currentInputFileProperties::class to currentInputFileProperties
        )!!
        return IncrementalInputChanges::class.newInstance<InputChangesInternal>(
            InputFileChanges::class to inputFileChanges, IncrementalInputProperties::class to incrementalInputProperties
        )!! to currentInputFileProperties
    }

    private fun initStore(taskName: String) {
        val target = project as ProjectInternal
        DefaultExecutionHistoryStore::class.java.methods.find { it.name == "store" }?.let {
            val parameterTypes = it.parameterTypes
            val classLoader = parameterTypes[3].classLoader
            val immutableListClass = Class.forName("com.google.common.collect.ImmutableList", true, classLoader)
            val immutableSortedMapClass = Class.forName("com.google.common.collect.ImmutableSortedMap", true, classLoader)
            it.invoke(
                target.services.get(ExecutionHistoryStore::class.java),
                ":${project.name}:$taskName",
                OriginMetadata("", 0),
                ImplementationSnapshot.of("", null),
                immutableListClass.cast(
                    Class.forName("com.google.common.collect.RegularImmutableList", true, classLoader).get<Any>(null, "EMPTY")!!
                ),
                immutableSortedMapClass.cast(immutableSortedMapClass.get(null, "NATURAL_EMPTY_MAP")!!),
                immutableSortedMapClass.invoke(
                    null, "copyOf", java.util.Map::class to mapOf(
                        "source" to EmptyCurrentFileCollectionFingerprint("CLASSPATH"),
                        "stableSources" to EmptyCurrentFileCollectionFingerprint("CLASSPATH")
                    )
                )!!,
                immutableSortedMapClass.cast(immutableSortedMapClass.get(null, "NATURAL_EMPTY_MAP")!!),
                false
            )
        }
    }

    // output dir does not exist or empty
    private val AbstractCompile.isFullCompilation: Boolean
        get() = outputs.files.files.none { it.exists() || it.isDirectory && it.list()?.isNotEmpty() ?: false }

    private val DefaultTask.compileTaskProperties: TaskProperties
        get() = DefaultTaskProperties.resolve(
            inputs::class.get<PropertyWalker>(inputs, "propertyWalker")!!,
            inputs::class.get<FileCollectionFactory>(inputs, "fileCollectionFactory")!!, this
        )

    private fun Plugin<*>.createAndroidTasks() = runCatching {
        (project as ProjectInternal).state.configured()
        this::class.java.invokeVoid(project.plugin, "createAndroidTasks")
    }.isSuccess

    @Suppress("PrivateApi")
    private fun Plugin<*>.initCompileSdkVersion() {
        val basePluginClass = this::class.java
        val sdkVersion = basePluginClass.invoke<String>(this, "findHighestSdkInstalled")!!
        val extension = basePluginClass.get<Any>(this, "extension")!!
        val baseExtensionClass = extension::class.java.classLoader.loadClass("com.android.build.gradle.BaseExtension")
        if (baseExtensionClass.invoke<String?>(extension, "getCompileSdkVersion") == null) {
            baseExtensionClass.invokeVoid(extension, "setCompileSdkVersion", String::class to sdkVersion)
        }
    }

    private fun Project.createFileCollection(name: String, content: Set<File>) =
        (this as ProjectInternal).services.get(FileCollectionFactory::class.java).create(object : MinimalFileSet {
            override fun getFiles() = content
            override fun getDisplayName() = name
        })
}