plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "1.4.0"
    id("org.jetbrains.dokka") version "1.5.0"
    id("com.gradle.plugin-publish") version "0.15.0"
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

val githubAddress = "https://github.com/wuyr/incremental-compiler"
val pluginName = "incrementalcompiler"
val pluginId = "com.github.wuyr.incrementalcompiler"
group = pluginId
version = "1.0.0"

gradlePlugin {
    plugins {
        create(pluginName) {
            id = pluginId
            displayName = "Incremental Compiler"
            implementationClass = "com.wuyr.incrementalcompiler.CompileTaskRegister"
        }
    }
}

pluginBundle {
    mavenCoordinates {
        artifactId = "plugin"
        groupId = pluginId
        version = version
    }
    website = githubAddress
    vcsUrl = githubAddress
    description =
        "A Gradle plugin for Android project, used to incrementally compile class and generate incremental DEX, much faster than the [assembleDebug] task."
    tags = listOf("incremental", "compile", "dex")
}

val dokkaHtml by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(dokkaHtml)
    archiveClassifier.set("javadoc")
    from(dokkaHtml.outputDirectory)
}

tasks {
    val sourcesJar by creating(Jar::class) {
        dependsOn(JavaPlugin.CLASSES_TASK_NAME)
        archiveClassifier.convention("sources")
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }
    artifacts {
        add("archives", sourcesJar)
        add("archives", javadocJar)
    }
}

///////////////////////////////////////////////////////////////////////////
// 如需进行本地测试，请注释上面的所有代码，取消注释下面的代码
// 2. 填写下面的本地发布路径
// 3. Sync Now
// 4. 通过执行 publishing/publish 这个Task来发布到本地
// 5. 在目标Project里的根build.gradle的buildscript/repositories节点里加上 maven { url "你填写的路径" }
// 6. 在目标Project里的根build.gradle的buildscript/dependencies节点里加上 classpath "com.wuyr.incrementalcompiler:plugin:插件版本号"
// 7. 在目标Module的build.gradle中加上 apply plugin: 'com.wuyr.incrementalcompiler'
// 8. Sync完成之后即可在Gradle窗口中看到 incremental/generateIncrementalDex 和 incremental/incrementalCompile 这个两Task
///////////////////////////////////////////////////////////////////////////

//plugins {
//    `java-gradle-plugin`
//    `maven-publish`
//    id("org.jetbrains.kotlin.jvm") version "1.4.0"
//}
//
//repositories { mavenCentral() }
//
//dependencies {
//    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
//    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
//}
//
//val pluginName = "incrementalcompiler"
//val pluginId = "com.wuyr.incrementalcompiler"
//group = pluginId
//version = "1.0.0"
//
//gradlePlugin {
//    plugins {
//        create(pluginName) {
//            id = pluginId
//            implementationClass = "com.wuyr.incrementalcompiler.CompileTaskRegister"
//        }
//    }
//}
//
//publishing {
//    repositories {
//        maven {
//            ///////////////////////////////////////////////////////////////////////////
//            // 在这里填写插件发布的本地路径
//            ///////////////////////////////////////////////////////////////////////////
//            url = uri("/home/wuyr/Desktop/IncrementalCompiler")
//        }
//    }
//}