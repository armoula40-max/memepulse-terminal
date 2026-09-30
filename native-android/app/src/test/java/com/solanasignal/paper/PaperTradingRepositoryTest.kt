package com.solanasignal.paper

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.solanasignal.data.SignalDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PaperTradingRepositoryTest {
    private val config = PaperTradingConfig(
        startingBalanceUsd = 10_000.0,
        positionSizeFraction = 0.01,
        feeRateBps = 0.0,
        fixedSlippageBps = 0.0,
        liquidityAwareSlippage = false,
        takeProfitFraction = 0.20,
        stopLossFraction = 0.10,
        maximumHoldingTimeMs = null,
    )

    @Test fun accountTradesAndAuditSurviveDatabaseReopenAndEntrySnapshotStaysImmutable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "paper-restart-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        var firstDb = openDatabase(context, name)
        var firstRepository = PaperTradingRepository(firstDb, config)
        val input = PaperTradingFixtures.input()
        firstRepository.initialize(PaperTradingFixtures.T0)
        val entry = firstRepository.process(input)
        assertEquals(PaperDecisionStatus.ENTERED, entry.decision)
        val original = firstDb.dao().paperTradeBySignal(input.signalId)!!
        assertEquals(1, firstDb.dao().paperTradeEvents().size)
        firstDb.close()

        var reopenedDb = openDatabase(context, name)
        var reopenedRepository = PaperTradingRepository(reopenedDb, config)
        var persistedAccount = reopenedDb.dao().paperAccount()!!
        assertEquals(1, persistedAccount.tradeCount)
        assertEquals(10_000.0, persistedAccount.startingBalanceUsd, 0.0)
        assertEquals(original.entryMarketStateJson, reopenedDb.dao().paperTradeBySignal(input.signalId)!!.entryMarketStateJson)

        val repeated = reopenedRepository.process(input)
        assertEquals(PaperDecisionStatus.IGNORED, repeated.decision)
        assertEquals("duplicate_signal_id", repeated.reason)
        assertEquals(1, reopenedDb.dao().paperTradeEvents().size)
        assertEquals(persistedAccount.cashBalanceUsd, reopenedDb.dao().paperAccount()!!.cashBalanceUsd, 0.0)

        val markInput = PaperTradingFixtures.input(
            at = PaperTradingFixtures.T0 + 5_000,
            priceUsd = 1.05,
            signalState = com.solanasignal.engineb.EngineBSignalState.WATCH,
            id = "persisted-mark",
        )
        assertEquals(PaperDecisionStatus.MARKED, reopenedRepository.process(markInput).decision)
        val afterMark = reopenedDb.dao().paperTradeBySignal(input.signalId)!!
        assertEquals(original.entryTimestamp, afterMark.entryTimestamp)
        assertEquals(original.entryPriceUsd, afterMark.entryPriceUsd, 0.0)
        assertEquals(original.entryFeaturesJson, afterMark.entryFeaturesJson)
        assertNotNull(afterMark.currentPriceUsd)
        reopenedDb.close()

        reopenedDb = openDatabase(context, name)
        reopenedRepository = PaperTradingRepository(reopenedDb, config)
        persistedAccount = reopenedDb.dao().paperAccount()!!
        assertEquals(1, persistedAccount.tradeCount)
        assertEquals(2, reopenedDb.dao().paperTradeEvents().size)
        val exitInput = PaperTradingFixtures.input(
            at = PaperTradingFixtures.T0 + 20_000,
            priceUsd = 1.25,
            signalState = com.solanasignal.engineb.EngineBSignalState.WATCH,
            id = "persisted-take-profit",
        )
        assertEquals(PaperDecisionStatus.EXITED, reopenedRepository.process(exitInput).decision)
        val closed = reopenedDb.dao().paperTradeBySignal(input.signalId)!!
        assertEquals("CLOSED", closed.status)
        assertEquals(PaperExitReason.TAKE_PROFIT.name, closed.exitReason)
        assertEquals(original.entryFeaturesJson, closed.entryFeaturesJson)
        assertEquals(4, reopenedDb.dao().paperTradeEvents().size)
        assertEquals(1, reopenedDb.dao().paperAccount()!!.closedTradeCount)
        reopenedDb.close()

        val finalDb = openDatabase(context, name)
        val restored = finalDb.dao().paperTradeBySignal(input.signalId)
        assertNotNull(restored)
        assertEquals(PaperExitReason.TAKE_PROFIT.name, restored!!.exitReason)
        assertEquals(25.0, restored.netPnlUsd!!, 0.000001)
        assertEquals(4, finalDb.dao().paperTradeEvents().size)
        finalDb.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test fun v2ToV3MigrationAddsOnlyPaperTablesAndPreservesExistingRows() {
        val raw = SQLiteDatabase.create(null)
        raw.execSQL("CREATE TABLE tokens (mint TEXT PRIMARY KEY NOT NULL, symbol TEXT)")
        raw.execSQL("INSERT INTO tokens (mint, symbol) VALUES ('legacy-mint', 'OLD')")
        raw.execSQL("CREATE TABLE trades (dedupeKey TEXT PRIMARY KEY NOT NULL, mint TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        raw.execSQL("INSERT INTO trades (dedupeKey, mint, timestamp) VALUES ('legacy-trade', 'legacy-mint', 123)")
        val supportDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args!![0] as String
                    if (args.size > 1) raw.execSQL(sql, args[1] as Array<Any?>) else raw.execSQL(sql)
                    null
                }
                "toString" -> "SupportSQLiteDatabase(migration-test)"
                else -> throw UnsupportedOperationException("Unexpected migration database call: ${method.name}")
            }
        } as SupportSQLiteDatabase

        try {
            SignalDatabase.MIGRATION_2_3.migrate(supportDb)
            val oldToken = raw.rawQuery("SELECT symbol FROM tokens WHERE mint='legacy-mint'", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getString(0)
            }
            val oldTradeCount = raw.rawQuery("SELECT COUNT(*) FROM trades WHERE dedupeKey='legacy-trade'", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getInt(0)
            }
            assertEquals("OLD", oldToken)
            assertEquals(1, oldTradeCount)
            val tables = raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(tables.containsAll(setOf("paper_accounts", "paper_trades", "paper_trade_events")))
            val newAccounts = raw.rawQuery("SELECT COUNT(*) FROM paper_accounts", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            assertEquals(0, newAccounts)
            val accountColumns = raw.rawQuery("PRAGMA table_info(paper_accounts)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(accountColumns.containsAll(setOf("startingBalanceUsd", "cashBalanceUsd", "closedTradeCount", "maximumDrawdownPct")))
        } finally {
            raw.close()
        }
    }

    private fun openDatabase(context: android.content.Context, name: String): SignalDatabase =
        Room.databaseBuilder(context, SignalDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(SignalDatabase.MIGRATION_1_2, SignalDatabase.MIGRATION_2_3)
            .build()
}
