package app.novaiptv.core.data.importer

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Runs a suspending block in one database transaction; an exception thrown by it rolls back. */
interface TransactionRunner {
    suspend fun <R> inTransaction(block: suspend () -> R): R
}

/** [TransactionRunner] backed by Room's `withTransaction`. */
class RoomTransactionRunner(private val database: RoomDatabase) : TransactionRunner {
    override suspend fun <R> inTransaction(block: suspend () -> R): R = database.withTransaction { block() }
}
