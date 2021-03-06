## 超快编译源文件的Gradle插件，支持Java和Kotlin

<br/>

### 博客详情：敬请期待。。。

<br/>

### 介绍：
插件源自WanAndroid每日一问：[极致的编译优化如何实现？](https://wanandroid.com/wenda/show/18453)

插件提供了2个Task：*incremental/incrementalCompile*（增量编译源文件）、*incremental/generateIncrementalDex*（增量编译 + 生成增量dex）；

每次只会编译有改动的源文件，增量编译过程中不依赖Android Gradle Plugin的任何一个Task（除了需要生成R文件和BuildConfig的时候）；

与Android Gradle Plugin的*assemble* Task共享编译记录（它编译过的文件且无改动的话，插件Task不会再编译，插件编译过的文件无改动的话它也不会再次编译）；


>需要clone代码进行本地测试的同学，我在`plugin/build.gradle.kts`里面已把详细步骤说明，这里就不赘述了。

<br/>

### 大致原理：
研究了*assembleDebug*的增量编译的大致原理（Java），发现它是通过JavaCompile的`compile`方法来完成对.java文件的编译的，这个方法有个叫`inputChanges`的参数，它描述了哪些文件在上一次编译后有改动，实现增量编译最重要就是这个参数了。

**那它是怎么知道哪些文件有改动的呢？**

是这样的，*assembleDebug*在每次编译完成之后，都会把本次参与编译的源文件的指纹（md5sum）记录在`project/.gradle/gradle-version/executionHistory/executionHistory.bin`文件里 。如果是第一次编译，那就是全部源文件了，后面每次只保存有变更的，因为没变更的不需要参与编译，它的md5也不会变。

指纹对比与直接对比修改日期有个优点就是，只要内容不变它的指纹都是不变的，而判断修改日期的话，可能那个文件刚开始有修改过，但在编译之前又撤销了修改，这样它的修改日期依然会变更。

**插件的做法是：**

初始化完成后直接拿到*compileDebugJavaWithJavac*和*compileDebugKotlin*（如果项目支持Kotlin的话）所对应的Compile（负责编译文件的类）；

在进行一些基本的配置之后，使用跟*assembleDebug*相同的方式来计算出本次变更的文件，并交给Compile处理；

Compile编译完成后，把这些参与编译的文件记录到*assembleDebug*这个Task所使用的ExecutionHistoryStore中；

如果运行的是*generateIncrementalDex*，还会借助D8工具（D8是SDK 28之后才存在`build-tools`里，这就是为什么要求*compileSdkVersion*不低于28的原因）来把这些类打包到dex里面。


<br/>

### 效果演示：
随意改动不同module的几个文件：

![preview](https://github.com/wuyr/incremental-compiler/raw/main/previews/0.png)

通过*generateIncrementalDex*来生成增量dex：

![preview](https://github.com/wuyr/incremental-compiler/raw/main/previews/5.png)

![preview](https://github.com/wuyr/incremental-compiler/raw/main/previews/1.png)

Task执行完毕后，在`project/build/outputs/merged_incremental_dex`目录下会生成`classes.dex`：

![preview](https://github.com/wuyr/incremental-compiler/raw/main/previews/2.png)

直接打开，会发现刚刚2个module中有改动的3个类都在里面了：

![preview](https://github.com/wuyr/incremental-compiler/raw/main/previews/3.png)

<br/>

### 使用方式：
#### 新版Gradle：
```groovy
plugins {
    ...
    id "com.github.wuyr.incrementalcompiler" version "1.0.0"
}
```
#### 旧版Gradle：
在项目下的`build.gradle`（注意不是`module/build.gradle`，是**项目根目录**下的`build.gradle`哦）加上maven地址和classpath，像这样：
```groovy
buildscript {
    ...
    repositories {
        ...
        maven { url "https://plugins.gradle.org/m2/" }    
    }

    dependencies {
        ...
        classpath "com.github.wuyr.incrementalcompiler:plugin:1.0.0"
    }
}
```

>如果上面的`plugins.gradle.org/m2`访问速度很慢，也可以换成国内的镜像地址，比如阿里云的：<br/>maven { url 'https://maven.aliyun.com/nexus/content/repositories/gradle-plugin' }

然后在目标module里加上：
```groovy
apply plugin: "com.github.wuyr.incrementalcompiler"
```
即可。

<br/>

#### 应用到所有module：
如果想为项目中所有module应用的话，可以在项目下的`build.gradle`（注意不是`module/build.gradle`，是**项目根目录**下的`build.gradle`哦）中直接遍历所有module，像这样：
```groovy
subprojects {
    apply plugin: 'com.github.wuyr.incrementalcompiler'
}
```

<br/>

### 要求：
 - *Gradle Version*不低于**6.1.1**

 - *Android Gradle Plugin Version*不低于**3.6.0**

 - *compileSdkVersion*/*buildToolsVersion*不低于**28**

<br/>

### 更新日志：
 - 1.0.0 完成基本功能。

<br/>

### 感谢：
感谢wanandroid交流群里的 "[Faded](https://github.com/custqqy)"、"亦梦" 帮忙测试各Gradle版本兼容性。