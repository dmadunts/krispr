package dev.krispr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

// Kotlin 2.2.x: symbol lookup through referenceClass; no CompilerPluginRegistrar.pluginId.

class KrisprCompilerPluginRegistrar : KrisprRegistrarBase() {
}

internal fun findClass(context: IrPluginContext, file: IrFile, id: ClassId): IrClassSymbol? = context.referenceClass(id)

internal fun findFunctions(context: IrPluginContext, file: IrFile, id: CallableId): Collection<IrSimpleFunctionSymbol> =
    context.referenceFunctions(id)
