package io.ethers.abigen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.ethers.abigen.reader.JsonAbiReaderRegistry
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.net.URLClassLoader
import kotlin.reflect.KClass

/**
 * Handles building and compiling contract wrappers generated by [AbiContractBuilder].
 * */
object AbigenCompiler {
    private const val GENERATED_CLASS_PACKAGE = "io.ethers.abigen.test"
    private val GENERATED_CLASS_DEST_DIR = GENERATED_CLASS_PACKAGE.replace('.', '/')
    private val ABIGEN_DIRECTORY = File(
        // Keep the property name in sync with the one in build.gradle.kts
        System.getProperty("abigen.directory") ?: throw IllegalStateException("abigen.directory not set"),
    )

    private val classLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { compileAll() }

    /**
     * Load the contract wrapper class generated by [AbiContractBuilder] for the given contract name.
     * */
    fun getContract(contractName: String): KClass<*> {
        return classLoader.loadClass("$GENERATED_CLASS_PACKAGE.$contractName").kotlin
    }

    /**
     * Build and compile contracts wrapper of ABI's located at `src/test/resources/abi` folder, and return the
     * class loader.
     * */
    @OptIn(ExperimentalCompilerApi::class)
    private fun compileAll(): URLClassLoader {
        val abis = AbigenCompiler::class.java.getResource("/abi")!!
            .file
            .let(::File)
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".json") }

        val errorLoaderBuilder = ErrorLoaderBuilder("Test", ABIGEN_DIRECTORY)

        val genSources = abis.map {
            val resourceName = it.name
            val abi = JsonAbiReaderRegistry.readAbi(it.toURI().toURL())
                ?: throw IllegalArgumentException("Invalid ABI: $resourceName")

            val contractName = resourceName.removeSuffix(".json").split("/").last()
            val outFile = File(ABIGEN_DIRECTORY, "$GENERATED_CLASS_DEST_DIR/$contractName.kt")

            errorLoaderBuilder.addContract(
                AbiContractBuilder(
                    contractName,
                    GENERATED_CLASS_PACKAGE,
                    ABIGEN_DIRECTORY,
                    abi,
                    emptyMap(),
                ).build(errorLoaderBuilder.canonicalName),
            )

            SourceFile.fromPath(outFile)
        }.toMutableList()

        errorLoaderBuilder.build()

        val loaderOutFile = File(ABIGEN_DIRECTORY, "${errorLoaderBuilder.canonicalName.replace('.', '/')}.kt")
        genSources.add(SourceFile.fromPath(loaderOutFile))

        val result = KotlinCompilation().apply {
            sources = genSources
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()

        if (result.exitCode != KotlinCompilation.ExitCode.OK) {
            throw IllegalStateException("Compilation failed: ${result.messages}")
        }

        return result.classLoader
    }
}
