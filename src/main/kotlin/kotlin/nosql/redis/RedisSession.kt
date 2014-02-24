package kotlin.nosql.redis

import kotlin.nosql.Session
import kotlin.nosql.TableSchema
import kotlin.nosql.AbstractColumn
import kotlin.nosql.Op
import kotlin.nosql.Query1
import kotlin.nosql.Template2
import kotlin.nosql.Query2
import redis.clients.jedis.Jedis
import kotlin.nosql.PKColumn
import java.util.HashMap
import kotlin.nosql.EqualsOp
import kotlin.nosql.LiteralOp
import kotlin.nosql.ColumnType
import kotlin.nosql.ColumnType.INTEGER
import kotlin.nosql.RangeQuery
import kotlin.nosql.NullableColumn
import kotlin.nosql.NotFoundException
import kotlin.nosql.KeyValueSchema
import kotlin.nosql.AbstractSchema
import kotlin.nosql.PKTableSchema
import kotlin.nosql.DocumentSchema
import java.util.ArrayList
import java.util.Arrays

class RedisSession(val jedis: Jedis) : Session() {
    override fun <T : DocumentSchema<P, V>, P, V> T.insert(v: () -> V): P {
        throw UnsupportedOperationException()
    }

    override fun <T: DocumentSchema<P, C>, P, C> T.filter(op: T.() -> Op): Iterator<C> {
        val op1 = op()
        if (op1 is EqualsOp && op1.expr1 is PKColumn<*, *>
        && op1.expr2 is LiteralOp) {
            val constructor = valueClass.getConstructors()[0]
            val constructorParamTypes = constructor.getParameterTypes()!!
            val constructorParamValues = Array<Any?>(constructor.getParameterTypes()!!.size, { index ->
                when (constructorParamTypes[index].getName()) {
                    "int" -> 0
                    "java.lang.String" -> ""
                    "java.util.List" -> listOf<Any>()
                    "java.util.Set" -> setOf<Any>()
                    else -> null
                }
            })
            val valueInstance = constructor.newInstance(*constructorParamValues) as C
            val schemaClass = this.javaClass
            val schemaFields = schemaClass.getDeclaredFields()
            for (schemaField in schemaFields) {
                if (javaClass<AbstractColumn<Any?, T, Any?>>().isAssignableFrom(schemaField.getType()!!)) {
                    val valueField = valueInstance.javaClass.getDeclaredField(schemaField.getName()!!.toLowerCase())
                    schemaField.setAccessible(true)
                    valueField.setAccessible(true)
                    val column = schemaField.get(this) as AbstractColumn<Any?, T, *>
                    val columnValue = when (column.columnType) {
                        ColumnType.INTEGER_LIST, ColumnType.STRING_LIST -> (column as AbstractColumn<List<*>, T, *>).filter(op).get({0..100})
                        else -> column.get(op1)
                    }
                    if (columnValue != null || column is NullableColumn<*, *>) {
                        valueField.set(valueInstance, columnValue)
                    } else {
                        throw NullPointerException()
                    }
                }
            }
            return Arrays.asList(valueInstance).iterator()
        }
        throw NullPointerException()
    }

    override fun <T : KeyValueSchema> T.next(c: T.() -> AbstractColumn<Int, T, *>): Int {
        val c = c()
        return jedis.incr(this.name + ":" + c.name)!!.toInt()
    }
    override fun <T : TableSchema> AbstractColumn<Int, T, *>.add(c: () -> Int): Int {
        val table = AbstractSchema.current<T>()
        return jedis.incrBy(table.name + ":" + name, c().toLong())!!.toInt()
    }

    override fun <T : TableSchema> T.create() {
        // TODO: Do nothing
    }
    override fun <T : TableSchema> T.drop() {
        // TODO: Do nothing
    }

    override fun <T : KeyValueSchema, C> T.get(c: T.() -> AbstractColumn<C, T, *>): C {
        val c = c()
        val v = jedis.get(this.name + ":" + c.name)
        if (v != null) {
            return when (c.columnType) {
                ColumnType.INTEGER -> Integer.parseInt(v) as C
                ColumnType.STRING -> v as C
                else -> throw UnsupportedOperationException()
            }
        } else {
            throw NotFoundException(this.name + ":" + c.name)
        }
    }

    override fun <T : KeyValueSchema, C> T.set(c: () -> AbstractColumn<C, T, *>, v: C) {
        val c = c()
        val v = jedis.set(this.name + ":" + c.name, c.toString())
    }

    override fun <T : AbstractSchema> insert(columns: Array<Pair<AbstractColumn<*, T, *>, *>>) {
        val table = AbstractSchema.current<T>()
        if (columns.isNotEmpty()) {
            var key: String? = null
            for (column in columns) {
                if (column.component1() is PKColumn<*, *>) {
                    key = column.component2().toString()
                }
            }
            if (key == null) {
                throw IllegalArgumentException()
            }
            val hash = HashMap<String, String>()
            for (column in columns) {
                if (!(column.component1() is PKColumn<*, *>)) {
                    if (column.component1().columnType == ColumnType.INTEGER
                    || column.component1().columnType == ColumnType.STRING) {
                        hash.put(column.component1().name, column.component2().toString())
                    } else if (column.component2() is Set<*>) {
                        for (v in column.component2() as Set<*>) {
                            jedis.sadd(table.name + ":" + key + ":" + column.component1().name, v.toString())
                        }
                    } else if (column.component2() is List<*>) {
                        for (v in column.component2() as List<*>) {
                            jedis.rpush(table.name + ":" + key + ":" + column.component1().name, v.toString())
                        }
                    }
                }
            }
            if (key != null) {
                jedis.hmset(table.name + ":" + key, hash)
            }
        }
    }

    override fun <T : TableSchema, C> RangeQuery<T, C>.forEach(st: (c: C) -> Unit) {
        val table = AbstractSchema.current<T>()
        val op1 = this.query.op
        if (op1 is EqualsOp && op1.expr1 is PKColumn<*, *>
        && op1.expr2 is LiteralOp) {
            val values = jedis.lrange(table.name + ":" + op1.expr2.value.toString() + ":" + query.a.name, range.start.toLong(), range.end.toLong())
            if (values != null) {
                for (s in values) {
                    st(when (query.a.columnType) {
                        ColumnType.INTEGER_LIST -> Integer.parseInt(s) as C
                        ColumnType.STRING_LIST -> s as C
                        else ->
                            throw UnsupportedOperationException()
                    })
                }
            }
        }
    }

    override fun <T : AbstractSchema> delete(table: T, op: Op) {
        throw UnsupportedOperationException()
    }

    override fun <T : TableSchema, C> Query1<T, C>.set(c: () -> C) {
        throw UnsupportedOperationException()
    }

    override fun <T : TableSchema> Query1<T, Int>.add(c: () -> Int): Int {
        val table = AbstractSchema.current<T>()
        val op1 = op!!
        if (op1 is EqualsOp && op1.expr1 is PKColumn<*, *>
        && op1.expr2 is LiteralOp) {
            return jedis.hincrBy(table.name + ":" + op1.expr2.value.toString(), a.name, c().toLong())!!.toInt()
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun <T : TableSchema, C, CC : Collection<*>> Query1<T, CC>.add(c: () -> C) {
        val table = AbstractSchema.current<T>()
        val op1 = op!!
        if (op1 is EqualsOp && op1.expr1 is PKColumn<*, *>
        && op1.expr2 is LiteralOp) {
            if (a.columnType == ColumnType.INTEGER_LIST
            || a.columnType == ColumnType.STRING_LIST) {
                jedis.rpush(table.name + ":" + op1.expr2.value.toString() + ":" + a.name, c().toString())
            } else {
                jedis.sadd(table.name + ":" + op1.expr2.value.toString() + ":" + a.name, c().toString())
            }
        }
    }

    override fun <T : TableSchema, C> AbstractColumn<C, T, *>.forEach(statement: (C) -> Unit) {
        throw UnsupportedOperationException()
    }
    override fun <T : TableSchema, C> AbstractColumn<C, T, *>.iterator(): Iterator<C> {
        throw UnsupportedOperationException()
    }

    override fun <T : TableSchema, C, M> AbstractColumn<C, T, *>.map(statement: (C) -> M): List<M> {
        throw UnsupportedOperationException()
    }

    override fun <T : PKTableSchema<P>, P, C> AbstractColumn<C, T, *>.get(id: () -> P): C {
        val table = AbstractSchema.current<T>()
        return get(table.pk eq id())
    }

    private fun <T : TableSchema, C> AbstractColumn<C, T, *>.get(where: Op): C {
        val table = AbstractSchema.current<T>()
        if (where is EqualsOp && where.expr1 is PKColumn<*, *> && where.expr2 is LiteralOp) {
            if (this is PKColumn<*, *>) {
                return where.expr2.value as C
            }
            if (columnType == ColumnType.INTEGER || columnType == ColumnType.STRING) {
                val v = jedis.hget(table.name + ":" + where.expr2.value.toString(), name)
                if (v != null) {
                    when (columnType) {
                        ColumnType.INTEGER -> {
                            return Integer.parseInt(v) as C
                        }
                        ColumnType.STRING -> return v as C
                        else -> throw UnsupportedOperationException()
                    }
                } else {
                    if (this is NullableColumn<*, *>) {
                        return null as C
                    } else {
                        throw NotFoundException(table.name + ":" + where.expr2.value.toString() + " " + name)
                    }
                }
            } else if (columnType == ColumnType.INTEGER_SET || columnType == ColumnType.STRING_SET) {
                val v = jedis.smembers(table.name + ":" + where.expr2.value.toString() + ":" + name)
                if (v != null) {
                    return v.map {
                        when (columnType) {
                            ColumnType.INTEGER_SET -> {
                                Integer.parseInt(it)
                            }
                            ColumnType.STRING_SET -> it
                            else -> throw UnsupportedOperationException()
                        }
                    }.toSet() as C
                } else {
                    throw NotFoundException(where.expr2.value)
                }
            } else {
                throw UnsupportedOperationException()
            }
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun <T : PKTableSchema<P>, P, A, B> Template2<T, A, B>.get(id: () -> P): Pair<A, B> {
        var result: Pair<A, B>? = null
        find(id).get { a, b ->
            result = Pair(a, b)
        }
        if (result != null) {
            return result!!
        } else {
            throw NullPointerException()
        }
    }
    override fun <T : TableSchema, A, B> Template2<T, A, B>.forEach(statement: (A, B) -> Unit) {
        throw UnsupportedOperationException()
    }
    override fun <T : TableSchema, A, B> Template2<T, A, B>.iterator(): Iterator<Pair<A, B>> {
        throw UnsupportedOperationException()
    }
    override fun <T : TableSchema, A, B, M> Template2<T, A, B>.map(statement: (A, B) -> M): List<M> {
        throw UnsupportedOperationException()
    }
    override fun <T : TableSchema, A, B> Query2<T, A, B>.forEach(statement: (A, B) -> Unit) {

    }
    private fun <C> convert(st: String?, columnType: ColumnType): C {
        if (st == null) {
            return null as C
        } else {
            return when (columnType) {
                ColumnType.INTEGER -> Integer.parseInt(st) as C
                ColumnType.STRING -> st as C
                else -> throw UnsupportedOperationException()
            }
        }
    }
    override fun <T : TableSchema, A, B> Query2<T, A, B>.get(statement: (A, B) -> Unit) {
        val table = AbstractSchema.current<T>()
        val op = op!!
        if (op is EqualsOp && op.expr1 is PKColumn<*, *> && op.expr2 is LiteralOp) {
            var av: String?
            var bv: String?
            if (a.columnType == ColumnType.INTEGER || a.columnType == ColumnType.STRING) {
                av = jedis.hget(table.name + ":" + op.expr2.value.toString(), a.name)
            } else {
                throw UnsupportedOperationException()
            }
            if (b.columnType == ColumnType.INTEGER || b.columnType == ColumnType.STRING) {
                bv = jedis.hget(table.name + ":" + op.expr2.value.toString(), b.name)
            } else {
                throw UnsupportedOperationException()
            }
            if ((av != null || a is NullableColumn<*, *>)
            && (bv != null || b is NullableColumn<*, *>)) {
                statement(convert<A>(av, a.columnType), convert<B>(bv, b.columnType))
            } else {
                throw NullPointerException()
            }
        }
    }
    override fun <T : TableSchema, A, B> Query2<T, A, B>.iterator(): Iterator<Pair<A, B>> {
        throw UnsupportedOperationException()
    }

}