package app.novaiptv.core.data.importer


/** Runs a suspending block in one database transaction; an exception thrown by it rolls back. */
interface TransactionRunner {
    suspend fun <R> inTransaction(block: suspend () -> R): R
}

