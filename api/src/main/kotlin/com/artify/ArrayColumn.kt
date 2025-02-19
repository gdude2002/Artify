package com.artify

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.CustomStringFunction
import org.jetbrains.exposed.sql.EqOp
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.statements.jdbc.JdbcPreparedStatementImpl
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.io.Serializable
import kotlin.Array
import java.sql.Array as SQLArray

/**
 * Creates a text array column with [name].
 *
 * @param size an optional size of the array
 */
public fun Table.textArray(name: String, size: Int? = null): Column<Array<String>> =
    array(name, currentDialect.dataTypeProvider.textType(), size)

private fun <T : Serializable> Table.array(name: String, underlyingType: String, size: Int?) =
    registerColumn<Array<T>>(name, ArrayColumnType<T>(underlyingType, size))

/**
 * Checks whether this string is in the [other] expression.
 *
 * Example:
 * ```kotlin
 * productService.find { "tag" eqAny ProductsTable.tags }
 * ```
 *
 * @see any
 */
public infix fun String.equalsAny(other: Expression<Array<String>>): EqOp =
    stringLiteral(this) eqAny other

/**
 * Invokes the `ANY` function on [expression].
 */
public fun <T : Serializable> any(
    expression: Expression<Array<T>>,
): ExpressionWithColumnType<String?> = CustomStringFunction("ANY", expression)

private infix fun <T : Serializable> Expression<T>.eqAny(other: Expression<Array<T>>): EqOp = EqOp(this, any(other))

/**
 * Implementation of [ColumnType] for the SQL `ARRAY` type.
 *
 * @property underlyingType the type of the array
 * @property size an optional size of the array
 */
public class ArrayColumnType<T : Serializable>(
    private val underlyingType: String, private val size: Int?
) : ColumnType() {
    override fun sqlType(): String = "$underlyingType ARRAY${size?.let { "[$it]" } ?: ""}"

    override fun notNullValueToDB(value: Any): Any = when (value) {
        is Array<*> -> value
        is Collection<*> -> value.toTypedArray()
        else -> error("Got unexpected array value of type: ${value::class.qualifiedName} ($value)")
    }

    override fun valueFromDB(value: Any): Any = when (value) {
        is SQLArray -> value.array as Array<*>
        is Array<*> -> value
        is Collection<*> -> value.toTypedArray()
        else -> error("Got unexpected array value of type: ${value::class.qualifiedName} ($value)")
    }

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        if (value == null) {
            stmt.setNull(index, this)
        } else {
            val preparedStatement = stmt as? JdbcPreparedStatementImpl ?: error("Currently only JDBC is supported")
            val array = preparedStatement.statement.connection.createArrayOf(underlyingType, value as Array<*>)
            stmt[index] = array
        }
    }
}
