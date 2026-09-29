package dev.krispr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

// Kotlin 2.3.20 to 2.3.x: symbol lookup through finderForSource (new in 2.3.20).

class KrisprCompilerPluginRegistrar : KrisprRegistrarBase() {
    override val pluginId: String = KrisprCommandLineProcessor.PLUGIN_ID
}

internal fun findClass(context: IrPluginContext, file: IrFile, id: ClassId): IrClassSymbol? =
    context.finderForSource(file).findClass(id)

internal fun findFunctions(context: IrPluginContext, file: IrFile, id: CallableId): Collection<IrSimpleFunctionSymbol> =
    context.finderForSource(file).findFunctions(id)
