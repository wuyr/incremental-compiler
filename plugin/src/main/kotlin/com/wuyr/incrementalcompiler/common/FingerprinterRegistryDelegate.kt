package com.wuyr.incrementalcompiler.common

import com.wuyr.incrementalcompiler.utils.findMethod
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.properties.InputFilePropertySpec
import org.gradle.api.tasks.FileNormalizer
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint
import org.gradle.util.GradleVersion
import java.lang.reflect.Method

/**
 * @author wuyr
 * @github https://github.com/wuyr/incremental-compiler
 * @since 2021-07-13 上午11:48
 */
class FingerprinterRegistryDelegate private constructor(private val fingerprinterRegistry: Any) {

    companion object {

        fun create(project: ProjectInternal) = FingerprinterRegistryDelegate(project.services.get(FileCollectionFingerprinterRegistryClass))

        // package moved begin at 7.0.2, latest is 7.1.1
        private val LARGER_THAN_7_0_2 = GradleVersion.current().version largerThan "7.0.2"

        // method getFingerprinter params changes begin at 6.8.0
        private val LARGER_THAN_6_7_1 = GradleVersion.current().version largerThan "6.7.1"

        private val CLASS_PREFIX = if (LARGER_THAN_7_0_2) "org.gradle.internal.execution.fingerprint" else "org.gradle.internal.fingerprint"

        private val FileCollectionFingerprinterRegistryClass = Class.forName("$CLASS_PREFIX.FileCollectionFingerprinterRegistry")

        private val getFingerprinterMethod: Method by lazy {
            FileCollectionFingerprinterRegistryClass.getMethod(
                "getFingerprinter", if (LARGER_THAN_6_7_1) Class.forName("$CLASS_PREFIX.FileNormalizationSpec") else Class::class.java
            )
        }

        private val fingerprintMethod: Method by lazy {
            Class.forName("$CLASS_PREFIX.FileCollectionFingerprinter").getMethod("fingerprint", FileCollection::class.java)
        }

        private val fromMethod: Method by lazy {
            Class.forName("$CLASS_PREFIX.impl.DefaultFileNormalizationSpec").getMethod(
                "from", Class::class.java, Class.forName("org.gradle.internal.fingerprint.DirectorySensitivity")
            )
        }

        private val getDirectorySensitivityMethod: Method by lazy { InputFilePropertySpec::class.java.getMethod("getDirectorySensitivity") }

        private val getPropertyFilesMethod: Method by lazy { InputFilePropertySpec::class.java.findMethod("getPropertyFiles") }

        private fun Any.getFingerprinter(spec: InputFilePropertySpec) = getFingerprinterMethod.invoke(
            this, fromMethod.invoke(null, spec.normalizer, getDirectorySensitivityMethod.invoke(spec))
        )

        private fun Any.getFingerprinter(type: Class<out FileNormalizer>) = getFingerprinterMethod.invoke(this, type)

        private fun Any.fingerprint(fileCollection: FileCollection) =
            fingerprintMethod.invoke(this, fileCollection) as CurrentFileCollectionFingerprint

        private val InputFilePropertySpec.properties: FileCollection get() = getPropertyFilesMethod.invoke(this) as FileCollection
    }

    /**
     *  计算文件指纹
     */
    fun fingerprint(spec: InputFilePropertySpec) =
        // method getFingerprinter params changes begin at 6.8.0
        (if (LARGER_THAN_6_7_1) {
            fingerprinterRegistry.getFingerprinter(spec)
        } else {
            fingerprinterRegistry.getFingerprinter(spec.normalizer)
        }).fingerprint(spec.properties)

}