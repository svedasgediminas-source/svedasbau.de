package app.novaiptv.core.data.importer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope

/** Outcome of [importInBatches]. [result] is what the producer returned. */
sealed interface BatchImportOutcome<out P> {
    val result: P

    /** The producer emitted nothing; no transaction was opened and the database is untouched. */
    data class NoBatches<P>(override val result: P) : BatchImportOutcome<P>

    /** Every batch was saved and committed. */
    data class Committed<P>(override val result: P) : BatchImportOutcome<P>

    /** The producer reported failure after emitting batches; the transaction was rolled back. */
    data class RolledBack<P>(override val result: P) : BatchImportOutcome<P>
}

/**
 * Streams batches from a blocking producer (e.g. a parser running inside an HTTP download
 * callback) into the database without ever holding the whole data set in memory.
 *
 * - [produce] runs on [producerDispatcher] and calls `emit` for each batch; `emit` blocks while the
 *   consumer is busy (at most one batch is buffered), so memory stays bounded to ~3 batches.
 * - Nothing is written until the first batch arrives. Then ONE transaction is opened: [begin] runs
 *   (e.g. delete the old rows), every batch is passed to [save], and finally the producer's result
 *   is checked with [isFailure]; a failure rolls the transaction back, so the previous data is kept.
 * - An exception from [produce], [begin] or [save] also rolls back and propagates.
 */
suspend fun <B : Any, P> importInBatches(
    transactions: TransactionRunner,
    produce: suspend (emit: (B) -> Unit) -> P,
    isFailure: (P) -> Boolean,
    begin: suspend () -> Unit,
    save: suspend (B) -> Unit,
    producerDispatcher: CoroutineDispatcher = Dispatchers.IO,
): BatchImportOutcome<P> = coroutineScope {
    val pipe = Channel<B>(capacity = 1)
    val producer = async(producerDispatcher) {
        try {
            // getOrThrow: once the consumer gave up (pipe cancelled) the parse is aborted.
            produce { batch -> pipe.trySendBlocking(batch).getOrThrow() }
        } finally {
            pipe.close()
        }
    }
    try {
        val first = pipe.receiveCatching().getOrNull()
            ?: return@coroutineScope BatchImportOutcome.NoBatches(producer.await())
        try {
            transactions.inTransaction {
                begin()
                var batch: B? = first
                while (batch != null) {
                    save(batch)
                    batch = pipe.receiveCatching().getOrNull()
                }
                val result = producer.await()
                if (isFailure(result)) throw RollbackSignal(result)
                BatchImportOutcome.Committed(result)
            }
        } catch (signal: RollbackSignal) {
            @Suppress("UNCHECKED_CAST")
            BatchImportOutcome.RolledBack(signal.result as P)
        }
    } finally {
        // Unblocks a producer stuck in emit() when the consumer failed; no-op after a normal end.
        pipe.cancel()
    }
}

/** Thrown inside the transaction to roll it back; never escapes [importInBatches]. */
private class RollbackSignal(val result: Any?) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}
