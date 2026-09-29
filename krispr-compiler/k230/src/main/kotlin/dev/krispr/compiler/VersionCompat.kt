package dev.krispr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

// Kotlin 2.3.0 to 2.3.10: CompilerPluginRegistrar.pluginId exists (2.3.0+); symbol lookup through referenceClass,
// since 2.3.0 and 2.3.10 have no finderForSource.

class KrisprCompilerPluginRegistrar : KrisprRegistrarBase() {
    override val pluginId: String = KrisprCommandLineProcessor.PLUGIN_ID
}

@Suppress("DEPRECATION")
internal fun findClass(context: IrPluginContext, file: IrFile, id: ClassId): IrClassSymbol? = context.referenceClass(id)

@Suppress("DEPRECATION")
internal fun findFunctions(context: IrPluginContext, file: IrFile, id: CallableId): Collection<IrSimpleFunctionSymbol> =
    context.referenceFunctions(id)
