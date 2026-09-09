package ksp.table

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

class TableProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(GenerateCode::class.qualifiedName!!)
        val unprocessed = symbols.filter { !it.validate() }.toList()

        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .forEach {
                val classDeclaration = it as KSClassDeclaration
                processDataClass(classDeclaration)
            }

        return unprocessed
    }

    private fun processDataClass(classDeclaration: KSClassDeclaration) {
        if (!classDeclaration.modifiers.contains(Modifier.DATA)) {
            logger.warn("@GenerateCode can only be applied to data classes: ${classDeclaration.simpleName.asString()}")
            return
        }

        val className = classDeclaration.simpleName.asString()
        val packageName = classDeclaration.packageName.asString()
        val tableClassName = className.replace("Data", "Table")

        val primaryConstructor = classDeclaration.primaryConstructor
        if (primaryConstructor == null) {
            logger.error("Data class ${classDeclaration.simpleName.asString()} does not have a primary constructor")
            return
        }

        val constructorParams = primaryConstructor.parameters
        val constructorParamNames = constructorParams.map { it.name?.asString() ?: "" }.toSet()

        val properties = classDeclaration.getAllProperties()
            .filter { property -> constructorParamNames.contains(property.simpleName.asString()) }
            .toList()

        val db = classDeclaration.annotations.find { it.shortName.asString() == "GenerateCode" }
            ?.arguments
            ?.find { it.name?.asString() == "db" }
            ?.value
            ?.toString()
            ?.removeSurrounding("\"", "'")
            ?: ""

        val fileSpec = createFileSpec(packageName, className, tableClassName, properties, db)

        val fileName = "${className}Extensions"
        val dependencies = Dependencies(false, classDeclaration.containingFile!!)

        codeGenerator.createNewFile(
            dependencies,
            packageName,
            fileName
        ).use { outputStream ->
            outputStream.write(fileSpec.toByteArray())
        }
    }

    private fun createFileSpec(
        packageName: String,
        className: String,
        tableClassName: String,
        properties: List<KSPropertyDeclaration>,
        db: String
    ): String {
        val sb = StringBuilder()

        sb.append("package $packageName\n\n")
        sb.append("import org.jetbrains.exposed.v1.core.ResultRow\n")
        sb.append("import org.jetbrains.exposed.v1.r2dbc.Query\n")
        sb.append("import org.jetbrains.exposed.v1.r2dbc.selectAll\n")
        sb.append("import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction\n")
        sb.append("import org.jetbrains.exposed.v1.r2dbc.update\n")
        sb.append("import essential.common.database.table.$tableClassName\n")
        sb.append("import $packageName.$className\n")
        if (db.isNotEmpty()) {
            sb.append("import essential.common.database.$db\n")
        }
        sb.append("import kotlinx.serialization.json.Json\n")
        sb.append("import kotlinx.serialization.encodeToString\n")
        sb.append("import kotlinx.serialization.decodeFromString\n")
        sb.append("import kotlinx.coroutines.flow.*\n")
        sb.append("import org.jetbrains.exposed.v1.core.eq\n")
        sb.append("import kotlin.time.ExperimentalTime\n\n")
        sb.append("private val generatedJson = Json { ignoreUnknownKeys = true; isLenient = true }\n\n")

        fun isSimpleType(typeDecl: KSClassDeclaration?): Boolean {
            val qn = typeDecl?.qualifiedName?.asString() ?: return true
            return qn in setOf(
                "kotlin.Int", "kotlin.UInt", "kotlin.Short", "kotlin.UShort",
                "kotlin.Byte", "kotlin.UByte", "kotlin.Long", "kotlin.ULong",
                "kotlin.Float", "kotlin.Double", "kotlin.Boolean", "kotlin.String",
                "kotlin.Char", "kotlin.UInt?", "kotlin.UByte?",
                "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Map",
                "kotlin.Array",
                "kotlinx.datetime.LocalDateTime"
            )
        }

        fun isSerializableType(typeDecl: KSClassDeclaration?): Boolean {
            if (typeDecl == null) return false
            return typeDecl.annotations.any {
                val name = it.shortName.asString()
                name == "Serializable" || it.annotationType.resolve().declaration.qualifiedName?.asString() == "kotlinx.serialization.Serializable"
            }
        }

        fun needsJson(property: KSPropertyDeclaration): Boolean {
            val typeDecl = property.type.resolve().declaration as? KSClassDeclaration
            return !isSimpleType(typeDecl) && isSerializableType(typeDecl)
        }

        val columns = properties.filter { it.simpleName.asString() != "id" }
        // A class whose columns are all `val` can never differ from the row it was read from, so
        // there is nothing to narrow and it keeps the plain full-row update.
        val tracked = columns.any { it.isMutable }
        val hasJsonColumn = columns.any { needsJson(it) }

        fun constructorCall(deepCopyJson: Boolean): String {
            val body = StringBuilder("$className(\n")
            properties.forEachIndexed { index, property ->
                val name = property.simpleName.asString()
                val value = if (deepCopyJson && needsJson(property)) {
                    val typeName = (property.type.resolve().declaration as KSClassDeclaration).simpleName.asString()
                    "generatedJson.decodeFromString<$typeName>(Json.encodeToString(this.$name))"
                } else {
                    "this.$name"
                }
                body.append("    $name = $value")
                if (index < properties.size - 1) body.append(",")
                body.append("\n")
            }
            body.append(")")
            return body.toString()
        }

        if (tracked) {
            sb.append("/**\n")
            sb.append(" * The row as it was last read from or written to the database. A serialised column is\n")
            sb.append(" * copied rather than shared, so mutating one in place still counts as a change.\n")
            sb.append(" */\n")
            sb.append("@OptIn(ExperimentalTime::class)\n")
            sb.append("private fun $className.snapshotOfRow(): $className = ")
            sb.append(constructorCall(deepCopyJson = true))
            sb.append("\n\n")
        }

        // toData extension for Table
        sb.append("/**\n")
        sb.append(" * Converts a ResultRow to a $className instance.\n")
        sb.append(" * This function is generated automatically by the @GenerateCode annotation.\n")
        sb.append(" */\n")
          sb.append("@OptIn(ExperimentalTime::class)\n")
        sb.append("fun $tableClassName.toData(row: ResultRow): $className {\n")

        properties.forEach { property ->
            val propertyName = property.simpleName.asString()
            val typeDecl = property.type.resolve().declaration as? KSClassDeclaration
            val needsJson = !isSimpleType(typeDecl) && isSerializableType(typeDecl)
            if (needsJson) {
                val typeName = typeDecl!!.simpleName.asString()
                sb.append("    val $propertyName = generatedJson.decodeFromString<$typeName>(row[$tableClassName.$propertyName])\n")
            } else {
                sb.append("    val $propertyName = row[$tableClassName.$propertyName]\n")
            }
        }

        sb.append("\n    return $className(\n")

        properties.forEachIndexed { index, property ->
            val propertyName = property.simpleName.asString()
            sb.append("        $propertyName = $propertyName")
            if (index < properties.size - 1) {
                sb.append(",")
            }
            sb.append("\n")
        }

        sb.append("    )")
        sb.append(if (tracked) ".also { it.dbSnapshot = it.snapshotOfRow() }\n" else "\n")
        sb.append("}\n\n")

        // mapToClassNameList
        sb.append("/**\n")
        sb.append(" * Maps query results to a list of $className instances.\n")
        sb.append(" * This is a convenience method for the table class.\n")
        sb.append(" * This function is generated automatically by the @GenerateCode annotation.\n")
        sb.append(" */\n")
        sb.append("suspend fun Query.mapTo${className}List(): List<$className> {\n")
        sb.append("    return this.map { $tableClassName.toData(it) }.toList()\n")
        sb.append("}\n\n")

        // update
        sb.append("/**\n")
        sb.append(" * Updates a database record using a $className instance.\n")
        sb.append(" * This function is generated automatically by the @GenerateCode annotation.\n")
        sb.append(" */\n")
        sb.append("@OptIn(ExperimentalTime::class)")
        sb.append("suspend fun $className.update(): Boolean {\n")
        sb.append("    val data = this\n")
        if (tracked) {
            sb.append("    // Only the columns this server actually changed are written. These rows are shared by\n")
            sb.append("    // several servers, and a full-row write from a copy loaded at join time silently reverted\n")
            sb.append("    // whatever another server had written to the other columns in the meantime. A row this\n")
            sb.append("    // process built itself carries no snapshot and is still written whole.\n")
            sb.append("    val base = data.dbSnapshot\n")
            if (!hasJsonColumn) {
                sb.append("    if (base != null && base == data) return true\n")
            }
            sb.append("    // The values actually sent, taken before the round trip: this object goes on being\n")
            sb.append("    // mutated from other threads while the write is in flight, and recording the state it\n")
            sb.append("    // reached afterwards as persisted would drop whatever changed in between.\n")
            sb.append("    val written = data.snapshotOfRow()\n")
        }
        // Untracked classes have no snapshot, so they write straight from the live object as before.
        val source = if (tracked) "written" else "data"
        if (db.isNotEmpty()) {
            sb.append("    val rows = suspendTransaction(db = $db) {\n")
        } else {
            sb.append("    val rows = suspendTransaction {\n")
        }
        sb.append("        $tableClassName.update({ $tableClassName.id eq data.id }) {\n")

        columns.forEach { property ->
            val propertyName = property.simpleName.asString()
            val assignment = if (needsJson(property)) {
                "it[$tableClassName.$propertyName] = Json.encodeToString($source.$propertyName)"
            } else {
                "it[$tableClassName.$propertyName] = $source.$propertyName"
            }
            // A serialised column is mutated in place by whoever holds it, so the snapshot cannot see
            // that its contents moved and it is always rewritten.
            if (tracked && !needsJson(property)) {
                sb.append("            if (base == null || base.$propertyName != written.$propertyName) $assignment\n")
            } else {
                sb.append("            $assignment\n")
            }
        }

        sb.append("        }\n")
        sb.append("    }\n")
        if (tracked) {
            sb.append("    // The statement committed, so these values are what the row holds now whether or not the\n")
            sb.append("    // server counted a change: MySQL reports rows changed rather than rows matched, so a\n")
            sb.append("    // sibling that had already written the same value would otherwise leave this baseline\n")
            sb.append("    // stale for good.\n")
            sb.append("    data.dbSnapshot = written\n")
        }
        sb.append("    return rows > 0\n")
        sb.append("}\n")

        return sb.toString()
    }
}

class TableProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return TableProcessor(environment.codeGenerator, environment.logger)
    }
}
