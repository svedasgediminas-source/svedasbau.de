package app.novaiptv.core.data.importer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImportTest {

    /** Fake "database": a committed table plus a staging copy that exists only inside a transaction. */
    private class FakeDb(initial: List<Int>) : TransactionRunner {
        var table: List<Int> = initial
        var staged: MutableList<Int>? = null
        var transactions = 0

        override suspend fun <R> inTransaction(block: suspend () -> R): R {
            transactions++
            staged = table.toMutableList()
            try {
                val r = block()
                table = staged!!.toList()
                return r
            } finally {
                staged = null
            }
        }
    }

    private sealed interface Res {
        data class Ok(val n: Int) : Res
        data class Err(val why: String) : Res
    }

    private fun run(
        db: FakeDb,
        produce: suspend ((List<Int>) -> Unit) -> Res,
    ): BatchImportOutcome<Res> = runBlocking {
        importInBatches(
            transactions = db,
            produce = produce,
            isFailure = { it is Res.Err },
            begin = { db.staged!!.clear() },
            save = { b -> db.staged!!.addAll(b) },
        )
    }

    @Test
    fun committedReplacesOldRows() {
        val db = FakeDb(listOf(-1, -2))
        val out = run(db, { emit ->
            for (i in 0 until 10) emit(listOf(i * 2, i * 2 + 1))
            Res.Ok(20)
        })
        assertEquals(BatchImportOutcome.Committed(Res.Ok(20)), out)
        assertEquals((0 until 20).toList(), db.table)
        assertEquals(1, db.transactions)
    }

    @Test
    fun noBatchesKeepsOldRowsAndOpensNoTransaction() {
        val db = FakeDb(listOf(1, 2, 3))
        val out = run(db, { Res.Ok(0) })
        assertEquals(BatchImportOutcome.NoBatches(Res.Ok(0)), out)
        assertEquals(listOf(1, 2, 3), db.table)
        assertEquals(0, db.transactions)
    }

    @Test
    fun failureAfterBatchesRollsBack() {
        val db = FakeDb(listOf(1, 2, 3))
        val out = run(db, { emit ->
            emit(listOf(10, 11))
            emit(listOf(12))
            Res.Err("truncated")
        })
        assertEquals(BatchImportOutcome.RolledBack(Res.Err("truncated")), out)
        assertEquals(listOf(1, 2, 3), db.table)
    }

    @Test
    fun producerExceptionRollsBackAndPropagates() {
        val db = FakeDb(listOf(1))
        try {
            run(db, { emit ->
                emit(listOf(5))
                throw IllegalStateException("boom")
            })
            throw AssertionError("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf(1), db.table)
    }

    @Test
    fun saveExceptionStopsBlockedProducer() {
        val db = FakeDb(listOf(1))
        var emitted = 0
        try {
            runBlocking {
                importInBatches<List<Int>, Res>(
                    transactions = db,
                    produce = { emit ->
                        try {
                            repeat(1_000) { emit(listOf(it)); emitted++ }
                        } catch (e: Exception) {
                            // emit fails once the consumer gave up; the parse aborts here.
                        }
                        Res.Ok(emitted)
                    },
                    isFailure = { it is Res.Err },
                    begin = { db.staged!!.clear() },
                    save = { b -> if (b.first() == 3) throw IllegalArgumentException("disk full") else db.staged!!.addAll(b) },
                )
            }
            throw AssertionError("expected exception")
        } catch (e: IllegalArgumentException) {
            assertEquals("disk full", e.message)
        }
        assertEquals(listOf(1), db.table)
        assertTrue("producer must stop early, emitted=$emitted", emitted < 10)
    }

    @Test
    fun producerIsThrottledByConsumer() {
        val db = FakeDb(emptyList())
        var produced = 0
        var consumed = 0
        var maxAhead = 0
        runBlocking {
            importInBatches<List<Int>, Res>(
                transactions = db,
                produce = { emit ->
                    repeat(200) {
                        emit(listOf(it))
                        synchronized(this@BatchImportTest) {
                            produced++
                            maxAhead = maxOf(maxAhead, produced - consumed)
                        }
                    }
                    Res.Ok(200)
                },
                isFailure = { it is Res.Err },
                begin = {},
                save = { b ->
                    Thread.sleep(1)
                    synchronized(this@BatchImportTest) { consumed++ }
                    db.staged!!.addAll(b)
                },
            )
        }
        assertEquals(200, db.table.size)
        assertTrue("producer ran $maxAhead batches ahead", maxAhead <= 3)
    }
}
