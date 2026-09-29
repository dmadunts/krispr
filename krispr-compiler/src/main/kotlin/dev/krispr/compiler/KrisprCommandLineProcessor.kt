package dev.krispr.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object KrisprConfigurationKeys {
    val MANIFEST: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("krispr manifest path")
    val ROOT: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("krispr root directory")
    val EXCLUDE_DIRS: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey.create("krispr excluded source directories")
    val MUTATE_ARID: CompilerConfigurationKey<Set<AridCategory>> = CompilerConfigurationKey.create("krispr arid code to mutate anyway")
    val OPERATORS: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey.create("krispr operators")
    val EXTREME: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("krispr extreme mode")
    val PROBE: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("krispr value probes")
}

class KrisprCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = OPTION_MANIFEST,
            valueDescription = "<path>",
            description = "Where to write the mutants.json manifest",
            required = true,
        ),
        CliOption(
            optionName = OPTION_ROOT,
            valueDescription = "<path>",
            description = "Directory that mutant ids hash file paths relative to (the root project)",
            required = false,
        ),
        CliOption(
            optionName = OPTION_EXCLUDE_DIR,
            valueDescription = "<path>",
            description = "Source files under this directory get no mutants (generated sources, e.g. the build directory)",
            required = false,
            allowMultipleOccurrences = true,
        ),
        CliOption(
            optionName = OPTION_MUTATE,
            valueDescription = AridCategory.entries.joinToString("|") { it.option },
            description = "Mutate this kind of arid code, which is skipped by default",
            required = false,
            allowMultipleOccurrences = true,
        ),
        CliOption(
            optionName = OPTION_OPERATOR,
            valueDescription = "DEFAULTS|" + Operator.entries.filter { it != Operator.REMOVE_BODY }.joinToString("|") { it.name },
            description = "Apply this operator (DEFAULTS: the default set); unset applies the default set",
            required = false,
            allowMultipleOccurrences = true,
        ),
        CliOption(
            optionName = OPTION_MODE,
            valueDescription = "default|extreme",
            description = "extreme: one mutant per function, which returns a default value in place of the whole body",
            required = false,
        ),
        CliOption(
            optionName = OPTION_PROBE,
            valueDescription = "true|false",
            description = "Emit a value probe at every mutation site, for showChanges (Mutants.isProbed/observe)",
            required = false,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            OPTION_MANIFEST -> configuration.put(KrisprConfigurationKeys.MANIFEST, value)
            OPTION_ROOT -> configuration.put(KrisprConfigurationKeys.ROOT, value)
            OPTION_EXCLUDE_DIR -> configuration.put(
                KrisprConfigurationKeys.EXCLUDE_DIRS,
                configuration.get(KrisprConfigurationKeys.EXCLUDE_DIRS).orEmpty() + value,
            )
            OPTION_MUTATE -> {
                val category = AridCategory.fromOption(value) ?: throw CliOptionProcessingException(
                    "Unknown krispr $OPTION_MUTATE value '$value'; expected one of ${AridCategory.entries.joinToString { it.option }}",
                )
                configuration.put(KrisprConfigurationKeys.MUTATE_ARID, configuration.get(KrisprConfigurationKeys.MUTATE_ARID).orEmpty() + category)
            }
            OPTION_OPERATOR -> {
                Operator.select(listOf(value)) ?: throw CliOptionProcessingException(
                    "Unknown krispr $OPTION_OPERATOR value '$value'; expected DEFAULTS or one of " +
                        Operator.entries.filter { it != Operator.REMOVE_BODY }.joinToString { it.name },
                )
                configuration.put(KrisprConfigurationKeys.OPERATORS, configuration.get(KrisprConfigurationKeys.OPERATORS).orEmpty() + value)
            }
            OPTION_MODE -> when (value) {
                "default" -> configuration.put(KrisprConfigurationKeys.EXTREME, false)
                "extreme" -> configuration.put(KrisprConfigurationKeys.EXTREME, true)
                else -> throw CliOptionProcessingException("Unknown krispr $OPTION_MODE '$value'; expected default or extreme")
            }
            OPTION_PROBE -> configuration.put(KrisprConfigurationKeys.PROBE, value.toBooleanStrictOrNull()
                ?: throw CliOptionProcessingException("Unknown krispr $OPTION_PROBE '$value'; expected true or false"))
            else -> throw CliOptionProcessingException("Unknown krispr option: ${option.optionName}")
        }
    }

    companion object {
        const val PLUGIN_ID = "dev.krispr"
        const val OPTION_MANIFEST = "manifest"
        const val OPTION_ROOT = "root"
        const val OPTION_EXCLUDE_DIR = "excludeDir"
        const val OPTION_MUTATE = "mutate"
        const val OPTION_OPERATOR = "operator"
        const val OPTION_MODE = "mode"
        const val OPTION_PROBE = "probe"
    }
}
