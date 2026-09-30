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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSetting(item: SettingEntity)
    @Query("SELECT * FROM signals ORDER BY timestamp DESC LIMIT 100") fun signals(): Flow<List<SignalEntity>>
    @Query("SELECT * FROM tokens ORDER BY firstSeenAt DESC LIMIT 100") fun tokens(): Flow<List<TokenEntity>>
    @Query("SELECT * FROM tokens WHERE mint = :mint LIMIT 1") suspend fun token(mint: String): TokenEntity?
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1") suspend fun setting(key: String): SettingEntity?
    @Query("SELECT * FROM metrics WHERE mint = :mint ORDER BY windowSeconds") suspend fun metrics(mint: String): List<MetricEntity>
    @Query("SELECT * FROM trades WHERE mint = :mint AND timestamp >= :since ORDER BY COALESCE(providerTimestamp, timestamp), receivedTimestamp, CASE WHEN providerSequence IS NULL THEN 1 ELSE 0 END, providerSequence, dedupeKey") suspend fun tradesSince(mint: String, since: Long): List<TradeEntity>
}

@Database(entities = [TokenEntity::class, TradeEntity::class, MetricEntity::class, ScoreEntity::class, SignalEntity::class, SignalOutcomeEntity::class, SystemEventEntity::class, SettingEntity::class, ProviderEventEntity::class], version = 2, exportSchema = false)
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

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SignalDatabase::class.java, "solana-signal.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
