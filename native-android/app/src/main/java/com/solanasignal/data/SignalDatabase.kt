package com.solanasignal.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertToken(item: TokenEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTrade(item: TradeEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMetric(item: MetricEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertProviderEvent(item: ProviderEventEntity): Long
    @Insert suspend fun insertScore(item: ScoreEntity)
    @Insert suspend fun insertSignal(item: SignalEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSystemEvent(item: SystemEventEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSetting(item: SettingEntity)
    @Query("SELECT * FROM signals ORDER BY timestamp DESC LIMIT 100") fun signals(): Flow<List<SignalEntity>>
    @Query("SELECT * FROM tokens ORDER BY firstSeenAt DESC LIMIT 100") fun tokens(): Flow<List<TokenEntity>>
    @Query("SELECT * FROM tokens WHERE mint = :mint LIMIT 1") suspend fun token(mint: String): TokenEntity?
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1") suspend fun setting(key: String): SettingEntity?
    @Query("SELECT * FROM metrics WHERE mint = :mint ORDER BY windowSeconds") suspend fun metrics(mint: String): List<MetricEntity>
    @Query("SELECT * FROM trades WHERE mint = :mint AND timestamp >= :since ORDER BY COALESCE(providerTimestamp, timestamp), receivedTimestamp, CASE WHEN providerSequence IS NULL THEN 1 ELSE 0 END, providerSequence, dedupeKey") suspend fun tradesSince(mint: String, since: Long): List<TradeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPaperAccount(item: PaperAccountEntity): Long
    @Query("SELECT * FROM paper_accounts WHERE accountId = :accountId LIMIT 1") suspend fun paperAccount(accountId: String = "default"): PaperAccountEntity?
    @Query("SELECT * FROM paper_accounts WHERE accountId = :accountId LIMIT 1") fun paperAccountFlow(accountId: String = "default"): Flow<PaperAccountEntity?>
    @Update suspend fun updatePaperAccount(item: PaperAccountEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPaperTrade(item: PaperTradeEntity): Long
    @Query("SELECT * FROM paper_trades WHERE signalId = :signalId LIMIT 1") suspend fun paperTradeBySignal(signalId: String): PaperTradeEntity?
    @Query("SELECT * FROM paper_trades WHERE status = 'OPEN' ORDER BY entryTimestamp, paperTradeId") suspend fun openPaperTrades(): List<PaperTradeEntity>
    @Query("SELECT * FROM paper_trades WHERE status = 'OPEN' AND mint = :mint ORDER BY entryTimestamp LIMIT 1") suspend fun openPaperTradeForMint(mint: String): PaperTradeEntity?
    @Query("SELECT * FROM paper_trades WHERE status = 'OPEN' ORDER BY entryTimestamp, paperTradeId") fun openPaperTradesFlow(): Flow<List<PaperTradeEntity>>
    @Query("SELECT * FROM paper_trades WHERE status IN ('CLOSED', 'CANCELLED', 'INVALIDATED') ORDER BY exitTimestamp DESC, entryTimestamp DESC LIMIT 200") fun closedPaperTradesFlow(): Flow<List<PaperTradeEntity>>
    @Query("SELECT * FROM paper_trades WHERE status IN ('CLOSED', 'CANCELLED', 'INVALIDATED') ORDER BY exitTimestamp DESC, entryTimestamp DESC LIMIT 200") suspend fun closedPaperTrades(): List<PaperTradeEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPaperTradeEvent(item: PaperTradeEventEntity): Long
    @Query("SELECT * FROM paper_trade_events WHERE eventId = :eventId LIMIT 1") suspend fun paperTradeEvent(eventId: String): PaperTradeEventEntity?
    @Query("SELECT * FROM paper_trade_events ORDER BY timestamp DESC, eventId DESC LIMIT 200") fun paperTradeEventsFlow(): Flow<List<PaperTradeEventEntity>>
    @Query("SELECT * FROM paper_trade_events ORDER BY timestamp DESC, eventId DESC LIMIT 200") suspend fun paperTradeEvents(): List<PaperTradeEventEntity>

    @Query("UPDATE paper_trades SET lastObservedPriceUsd = :lastObservedPriceUsd, currentPriceUsd = :currentPriceUsd, currentMarketCapUsd = :currentMarketCapUsd, currentLiquidityUsd = :currentLiquidityUsd, currentValueUsd = :currentValueUsd, unrealizedPnlUsd = :unrealizedPnlUsd, unrealizedPnlPct = :unrealizedPnlPct, valuationStatus = :valuationStatus, lastMarkTimestamp = :lastMarkTimestamp, lastMarketSnapshotRef = :lastMarketSnapshotRef, lastMarketStateJson = :lastMarketStateJson, currentSignalState = :currentSignalState, currentPumpPotential = :currentPumpPotential, currentCollapseRisk = :currentCollapseRisk, peakPriceUsd = :peakPriceUsd, troughPriceUsd = :troughPriceUsd, maximumFavorableExcursionPct = :maximumFavorableExcursionPct, maximumAdverseExcursionPct = :maximumAdverseExcursionPct, currentDrawdownFromPeakPct = :currentDrawdownFromPeakPct, maximumPositionDrawdownPct = :maximumPositionDrawdownPct WHERE paperTradeId = :paperTradeId AND status = 'OPEN'")
    suspend fun updateOpenPaperMark(
        paperTradeId: String, lastObservedPriceUsd: Double?, currentPriceUsd: Double?, currentMarketCapUsd: Double?, currentLiquidityUsd: Double?,
        currentValueUsd: Double?, unrealizedPnlUsd: Double?, unrealizedPnlPct: Double?, valuationStatus: String, lastMarkTimestamp: Long?,
        lastMarketSnapshotRef: String?, lastMarketStateJson: String?, currentSignalState: String?, currentPumpPotential: String?, currentCollapseRisk: String?,
        peakPriceUsd: Double, troughPriceUsd: Double, maximumFavorableExcursionPct: Double, maximumAdverseExcursionPct: Double?,
        currentDrawdownFromPeakPct: Double?, maximumPositionDrawdownPct: Double,
    ): Int

    @Query("UPDATE paper_trades SET status = 'CLOSED', lastObservedPriceUsd = :lastObservedPriceUsd, currentPriceUsd = :currentPriceUsd, currentMarketCapUsd = :currentMarketCapUsd, currentLiquidityUsd = :currentLiquidityUsd, currentValueUsd = :currentValueUsd, unrealizedPnlUsd = 0, unrealizedPnlPct = 0, valuationStatus = :valuationStatus, lastMarkTimestamp = :lastMarkTimestamp, lastMarketSnapshotRef = :lastMarketSnapshotRef, lastMarketStateJson = :lastMarketStateJson, currentSignalState = :currentSignalState, currentPumpPotential = :currentPumpPotential, currentCollapseRisk = :currentCollapseRisk, peakPriceUsd = :peakPriceUsd, troughPriceUsd = :troughPriceUsd, maximumFavorableExcursionPct = :maximumFavorableExcursionPct, maximumAdverseExcursionPct = :maximumAdverseExcursionPct, currentDrawdownFromPeakPct = :currentDrawdownFromPeakPct, maximumPositionDrawdownPct = :maximumPositionDrawdownPct, exitTimestamp = :exitTimestamp, exitReason = :exitReason, exitPriceUsd = :exitPriceUsd, exitMarketSnapshotRef = :exitMarketSnapshotRef, exitMarketStateJson = :exitMarketStateJson, exitSignalState = :exitSignalState, exitNotionalUsd = :exitNotionalUsd, exitSimulatedSlippageBps = :exitSimulatedSlippageBps, exitSimulatedSlippageUsd = :exitSimulatedSlippageUsd, effectiveExitPriceUsd = :effectiveExitPriceUsd, exitFeeUsd = :exitFeeUsd, grossPnlUsd = :grossPnlUsd, netPnlUsd = :netPnlUsd, grossPnlPct = :grossPnlPct, netPnlPct = :netPnlPct, holdingDurationMs = :holdingDurationMs, exitAmbiguous = :exitAmbiguous WHERE paperTradeId = :paperTradeId AND status = 'OPEN'")
    suspend fun closePaperTrade(
        paperTradeId: String, lastObservedPriceUsd: Double?, currentPriceUsd: Double?, currentMarketCapUsd: Double?, currentLiquidityUsd: Double?,
        currentValueUsd: Double?, valuationStatus: String, lastMarkTimestamp: Long?, lastMarketSnapshotRef: String?, lastMarketStateJson: String?,
        currentSignalState: String?, currentPumpPotential: String?, currentCollapseRisk: String?, peakPriceUsd: Double, troughPriceUsd: Double,
        maximumFavorableExcursionPct: Double, maximumAdverseExcursionPct: Double, currentDrawdownFromPeakPct: Double?, maximumPositionDrawdownPct: Double,
        exitTimestamp: Long, exitReason: String, exitPriceUsd: Double, exitMarketSnapshotRef: String, exitMarketStateJson: String,
        exitSignalState: String, exitNotionalUsd: Double, exitSimulatedSlippageBps: Double, exitSimulatedSlippageUsd: Double,
        effectiveExitPriceUsd: Double, exitFeeUsd: Double, grossPnlUsd: Double, netPnlUsd: Double, grossPnlPct: Double,
        netPnlPct: Double, holdingDurationMs: Long, exitAmbiguous: Boolean,
    ): Int
}

@Database(entities = [TokenEntity::class, TradeEntity::class, MetricEntity::class, ScoreEntity::class, SignalEntity::class, SignalOutcomeEntity::class, SystemEventEntity::class, SettingEntity::class, ProviderEventEntity::class, PaperAccountEntity::class, PaperTradeEntity::class, PaperTradeEventEntity::class], version = 3, exportSchema = false)
abstract class SignalDatabase : RoomDatabase() { abstract fun dao(): SignalDao
    companion object { @Volatile private var instance: SignalDatabase? = null
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tokens ADD COLUMN marketCapNative REAL")
                db.execSQL("ALTER TABLE tokens ADD COLUMN priceNative REAL")
                db.execSQL("ALTER TABLE tokens ADD COLUMN latestTokenAmount REAL")
                db.execSQL("ALTER TABLE tokens ADD COLUMN latestSolAmount REAL")
                db.execSQL("ALTER TABLE tokens ADD COLUMN holders INTEGER")
                listOf(
                    "latestTokenAmountAvailability", "latestSolAmountAvailability",
                    "marketCapNativeAvailability", "marketCapUsdAvailability", "priceNativeAvailability",
                    "priceUsdAvailability", "liquidityUsdAvailability", "holdersAvailability",
                ).forEach { db.execSQL("ALTER TABLE tokens ADD COLUMN `$it` TEXT NOT NULL DEFAULT 'UNKNOWN'") }
                db.execSQL("UPDATE tokens SET marketCapUsdAvailability = CASE WHEN marketCapUsd IS NULL THEN 'UNKNOWN' WHEN marketCapUsd = 0 THEN 'ZERO' ELSE 'KNOWN' END, priceUsdAvailability = CASE WHEN priceUsd IS NULL THEN 'UNKNOWN' WHEN priceUsd = 0 THEN 'ZERO' ELSE 'KNOWN' END, liquidityUsdAvailability = CASE WHEN liquidityUsd IS NULL THEN 'UNKNOWN' WHEN liquidityUsd = 0 THEN 'ZERO' ELSE 'KNOWN' END")
                db.execSQL("ALTER TABLE tokens ADD COLUMN providerTimestamp INTEGER")
                db.execSQL("ALTER TABLE tokens ADD COLUMN receivedTimestamp INTEGER")
                db.execSQL("ALTER TABLE tokens ADD COLUMN observationTimestamp INTEGER")
                db.execSQL("ALTER TABLE tokens ADD COLUMN freshnessMs INTEGER")
                db.execSQL("ALTER TABLE tokens ADD COLUMN isStale INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tokens ADD COLUMN lastEventId TEXT")

                db.execSQL("ALTER TABLE trades ADD COLUMN priceNative REAL")
                db.execSQL("ALTER TABLE trades ADD COLUMN marketCapNative REAL")
                db.execSQL("ALTER TABLE trades ADD COLUMN marketCapUsd REAL")
                listOf(
                    "solAmountAvailability", "tokenAmountAvailability", "priceNativeAvailability",
                    "priceUsdAvailability", "marketCapNativeAvailability", "marketCapUsdAvailability",
                ).forEach { db.execSQL("ALTER TABLE trades ADD COLUMN `$it` TEXT NOT NULL DEFAULT 'UNKNOWN'") }
                db.execSQL("UPDATE trades SET marketCapNative = marketCapSol, marketCapNativeAvailability = CASE WHEN marketCapSol IS NULL THEN 'UNKNOWN' WHEN marketCapSol = 0 THEN 'ZERO' ELSE 'KNOWN' END, priceNative = CASE WHEN solAmount IS NOT NULL AND tokenAmount > 0 THEN solAmount / tokenAmount ELSE NULL END, priceNativeAvailability = CASE WHEN solAmount IS NULL OR tokenAmount IS NULL OR tokenAmount <= 0 THEN 'UNKNOWN' WHEN solAmount = 0 THEN 'ZERO' ELSE 'KNOWN' END, solAmountAvailability = CASE WHEN solAmount IS NULL THEN 'UNKNOWN' WHEN solAmount = 0 THEN 'ZERO' ELSE 'KNOWN' END, tokenAmountAvailability = CASE WHEN tokenAmount IS NULL THEN 'UNKNOWN' WHEN tokenAmount = 0 THEN 'ZERO' ELSE 'KNOWN' END, priceUsdAvailability = CASE WHEN priceUsd IS NULL THEN 'UNKNOWN' WHEN priceUsd = 0 THEN 'ZERO' ELSE 'KNOWN' END, marketCapUsdAvailability = CASE WHEN marketCapUsd IS NULL THEN 'UNKNOWN' WHEN marketCapUsd = 0 THEN 'ZERO' ELSE 'KNOWN' END")
                db.execSQL("UPDATE trades SET dedupeKey = 'trade:' || signature || ':' || mint || ':' || lower(txType) WHERE signature IS NOT NULL AND TRIM(signature) != '' AND txType IS NOT NULL")
                db.execSQL("ALTER TABLE trades ADD COLUMN providerTimestamp INTEGER")
                db.execSQL("ALTER TABLE trades ADD COLUMN receivedTimestamp INTEGER")
                db.execSQL("ALTER TABLE trades ADD COLUMN observationTimestamp INTEGER")
                db.execSQL("ALTER TABLE trades ADD COLUMN freshnessMs INTEGER")
                db.execSQL("ALTER TABLE trades ADD COLUMN providerSequence INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `provider_events` (`eventId` TEXT NOT NULL, `mint` TEXT NOT NULL, `eventType` TEXT NOT NULL, `providerTimestamp` INTEGER, `receivedTimestamp` INTEGER NOT NULL, `observationTimestamp` INTEGER NOT NULL, `providerSequence` INTEGER, `eventTimestamp` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_events_mint` ON `provider_events` (`mint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_events_eventTimestamp` ON `provider_events` (`eventTimestamp`)")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `paper_accounts` (`accountId` TEXT NOT NULL, `startingBalanceUsd` REAL NOT NULL, `cashBalanceUsd` REAL NOT NULL, `reservedOpenPositionValueUsd` REAL NOT NULL, `realizedPnlUsd` REAL NOT NULL, `totalFeesUsd` REAL NOT NULL, `totalSimulatedSlippageUsd` REAL NOT NULL, `tradeCount` INTEGER NOT NULL, `closedTradeCount` INTEGER NOT NULL, `winningTrades` INTEGER NOT NULL, `losingTrades` INTEGER NOT NULL, `currentEquityUsd` REAL, `peakEquityUsd` REAL, `maximumDrawdownPct` REAL NOT NULL, `valuationStatus` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `paper_trades` (`paperTradeId` TEXT NOT NULL, `signalId` TEXT NOT NULL, `mint` TEXT NOT NULL, `tokenSymbol` TEXT, `status` TEXT NOT NULL, `entryTimestamp` INTEGER NOT NULL, `entryPriceUsd` REAL NOT NULL, `entryPriceObservedAt` INTEGER NOT NULL, `entryMarketCapUsd` REAL, `entryLiquidityUsd` REAL, `entrySignalState` TEXT NOT NULL, `entryPumpPotential` TEXT NOT NULL, `entryPumpEvidenceJson` TEXT NOT NULL, `entryCollapseRisk` TEXT NOT NULL, `entryCollapseEvidenceJson` TEXT NOT NULL, `entryMarketCapQuality` TEXT NOT NULL, `entryDataFreshness` TEXT NOT NULL, `entrySnapshotRef` TEXT NOT NULL, `entryMarketStateJson` TEXT NOT NULL, `entryFeaturesJson` TEXT NOT NULL, `entryNotionalUsd` REAL NOT NULL, `tokenQuantity` REAL NOT NULL, `entryFeeUsd` REAL NOT NULL, `entrySimulatedSlippageBps` REAL NOT NULL, `entrySimulatedSlippageUsd` REAL NOT NULL, `effectiveEntryPriceUsd` REAL NOT NULL, `lastObservedPriceUsd` REAL, `currentPriceUsd` REAL, `currentMarketCapUsd` REAL, `currentLiquidityUsd` REAL, `currentValueUsd` REAL, `unrealizedPnlUsd` REAL, `unrealizedPnlPct` REAL, `valuationStatus` TEXT NOT NULL, `lastMarkTimestamp` INTEGER, `lastMarketSnapshotRef` TEXT, `lastMarketStateJson` TEXT, `currentSignalState` TEXT, `currentPumpPotential` TEXT, `currentCollapseRisk` TEXT, `peakPriceUsd` REAL NOT NULL, `troughPriceUsd` REAL NOT NULL, `maximumFavorableExcursionPct` REAL NOT NULL, `maximumAdverseExcursionPct` REAL NOT NULL, `currentDrawdownFromPeakPct` REAL, `maximumPositionDrawdownPct` REAL NOT NULL, `exitTimestamp` INTEGER, `exitReason` TEXT, `exitPriceUsd` REAL, `exitMarketSnapshotRef` TEXT, `exitMarketStateJson` TEXT, `exitSignalState` TEXT, `exitNotionalUsd` REAL, `exitSimulatedSlippageBps` REAL, `exitSimulatedSlippageUsd` REAL, `effectiveExitPriceUsd` REAL, `exitFeeUsd` REAL, `grossPnlUsd` REAL, `netPnlUsd` REAL, `grossPnlPct` REAL, `netPnlPct` REAL, `holdingDurationMs` INTEGER, `exitAmbiguous` INTEGER NOT NULL, PRIMARY KEY(`paperTradeId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_paper_trades_mint_status` ON `paper_trades` (`mint`, `status`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_paper_trades_signal_id` ON `paper_trades` (`signalId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_paper_trades_entry_timestamp` ON `paper_trades` (`entryTimestamp`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `paper_trade_events` (`eventId` TEXT NOT NULL, `paperTradeId` TEXT NOT NULL, `mint` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `action` TEXT NOT NULL, `snapshotRef` TEXT NOT NULL, `observedPriceUsd` REAL, `observedPriceTimestamp` INTEGER, `signalState` TEXT, `reason` TEXT, `ambiguous` INTEGER NOT NULL, `eventSnapshotJson` TEXT NOT NULL, PRIMARY KEY(`eventId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_paper_trade_events_trade_time` ON `paper_trade_events` (`paperTradeId`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_paper_trade_events_timestamp` ON `paper_trade_events` (`timestamp`)")
            }
        }

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SignalDatabase::class.java, "solana-signal.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
