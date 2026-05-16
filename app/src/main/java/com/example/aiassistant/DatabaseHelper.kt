package com.example.aiassistant

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// --- Entities ---

@Entity
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String,
    val state: String,
    val actionType: String,
    val actionDetail: String,
    val success: Boolean
)

@Entity
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val keyword: String,
    val actionType: String = "CLICK",
    val enabled: Boolean = true
)

@Entity(
    indices = [
        Index(value = ["state", "actionText"], unique = true),
        Index(value = ["state", "count"]),
        Index(value = ["packageName", "count"])
    ]
)
data class UserPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val state: String,
    val packageName: String,
    val actionText: String,
    val actionType: String,
    val count: Int = 1,
    val lastSeen: Long = System.currentTimeMillis()
)

@Entity(
    indices = [
        Index(value = ["screenState", "settingName", "newValue"], unique = true),
        Index(value = ["screenState", "count"])
    ]
)
data class SystemPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val screenState: String,
    val matchedKeywords: String,
    val settingName: String,
    val oldValue: String,
    val newValue: String,
    val count: Int = 1,
    val lastSeen: Long = System.currentTimeMillis()
)

// --- DAOs ---

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogEntity)

    @Query("SELECT * FROM LogEntity ORDER BY timestamp DESC")
    suspend fun getAll(): List<LogEntity>

    @Query("SELECT * FROM LogEntity ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LogEntity>

    /** Efficient query — avoids loading all rows just to extract package names. */
    @Query("SELECT DISTINCT packageName FROM LogEntity ORDER BY packageName ASC")
    suspend fun getDistinctPackages(): List<String>

    @Query("DELETE FROM LogEntity")
    suspend fun clearAll()
}

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(rule: RuleEntity)

    @Update
    suspend fun update(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    @Query("SELECT * FROM RuleEntity")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM RuleEntity WHERE enabled = 1")
    suspend fun getEnabled(): List<RuleEntity>

    @Query("SELECT COUNT(*) FROM RuleEntity")
    suspend fun count(): Int
}

@Dao
interface UserPatternDao {
    @Query("SELECT * FROM UserPatternEntity WHERE state = :state ORDER BY count DESC")
    suspend fun getByState(state: String): List<UserPatternEntity>

    @Query("SELECT * FROM UserPatternEntity WHERE state = :state ORDER BY count DESC LIMIT 1")
    suspend fun getTopByState(state: String): UserPatternEntity?

    @Query("SELECT * FROM UserPatternEntity WHERE state = :state AND actionText = :actionText LIMIT 1")
    suspend fun get(state: String, actionText: String): UserPatternEntity?

    /**
     * Fallback used when the current screen's stableState doesn't match any stored
     * pattern (e.g. after a rotation produces a different structural fingerprint).
     * Returns confident patterns learned in the same app, highest-count first, so
     * the caller can verify the target text is still visible on the new layout.
     */
    @Query("SELECT * FROM UserPatternEntity WHERE packageName = :packageName AND count >= :minCount ORDER BY count DESC")
    suspend fun getConfidentByPackage(packageName: String, minCount: Int): List<UserPatternEntity>

    @Insert
    suspend fun insert(pattern: UserPatternEntity)

    @Query("UPDATE UserPatternEntity SET count = count + 1, lastSeen = :now WHERE id = :id")
    suspend fun incrementCount(id: Int, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM UserPatternEntity")
    suspend fun totalPatterns(): Int

    @Query("SELECT COUNT(DISTINCT packageName) FROM UserPatternEntity")
    suspend fun distinctApps(): Int

    @Query("SELECT * FROM UserPatternEntity ORDER BY packageName ASC, count DESC")
    suspend fun getAll(): List<UserPatternEntity>

    @Delete
    suspend fun delete(pattern: UserPatternEntity)

    @Query("DELETE FROM UserPatternEntity")
    suspend fun deleteAll()

    /**
     * Reduces a pattern's confidence by 1 (floor 0) when the user overrides it.
     */
    @Query("UPDATE UserPatternEntity SET count = MAX(0, count - 1) WHERE state = :state AND actionText = :actionText")
    suspend fun decrementCount(state: String, actionText: String)

    /**
     * Removes all patterns whose confidence has dropped to zero.
     */
    @Query("DELETE FROM UserPatternEntity WHERE count = 0")
    suspend fun deleteZeroCount()

    /**
     * Reduces confidence by 1 (floor 0) for every pattern not seen since [cutoffMs].
     * Used by the periodic decay sweep.
     */
    @Query("UPDATE UserPatternEntity SET count = MAX(0, count - 1) WHERE lastSeen < :cutoffMs")
    suspend fun decrementStalePatterns(cutoffMs: Long)
}

@Dao
interface SystemPatternDao {
    @Query("SELECT * FROM SystemPatternEntity WHERE screenState = :screenState ORDER BY count DESC")
    suspend fun getByScreenState(screenState: String): List<SystemPatternEntity>

    @Query("SELECT * FROM SystemPatternEntity WHERE screenState = :screenState AND settingName = :settingName AND newValue = :newValue LIMIT 1")
    suspend fun get(screenState: String, settingName: String, newValue: String): SystemPatternEntity?

    @Insert
    suspend fun insert(pattern: SystemPatternEntity)

    @Query("UPDATE SystemPatternEntity SET count = count + 1, lastSeen = :now WHERE id = :id")
    suspend fun incrementCount(id: Int, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM SystemPatternEntity ORDER BY packageName ASC, count DESC")
    suspend fun getAll(): List<SystemPatternEntity>

    @Delete
    suspend fun delete(pattern: SystemPatternEntity)

    @Query("DELETE FROM SystemPatternEntity")
    suspend fun deleteAll()
}

// --- Database ---

@Database(
    entities = [LogEntity::class, RuleEntity::class, UserPatternEntity::class, SystemPatternEntity::class],
    version = 6,
    exportSchema = true  // Schema files written to app/schemas/ for migration tracking.
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun ruleDao(): RuleDao
    abstract fun userPatternDao(): UserPatternDao
    abstract fun systemPatternDao(): SystemPatternDao
}

// --- Helper ---

object DatabaseHelper {
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_UserPatternEntity_state_count " +
                        "ON UserPatternEntity(state, count)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_SystemPatternEntity_screenState_count " +
                        "ON SystemPatternEntity(screenState, count)"
            )
        }
    }

    /** Adds a (packageName, count) index to support orientation-agnostic pattern lookup. */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_UserPatternEntity_packageName_count " +
                        "ON UserPatternEntity(packageName, count)"
            )
        }
    }

    /**
     * @Volatile ensures the cached instance is always read from main memory,
     * preventing a second thread from seeing a partially-constructed object.
     * The synchronized block inside getDB() prevents a race where two threads
     * both see null and each try to create a new database instance.
     */
    @Volatile
    private var db: AppDatabase? = null

    fun getDB(context: Context): AppDatabase {
        return db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ai_db"
            )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
            .also { db = it }
        }
    }

    suspend fun logAction(
        context: Context,
        packageName: String,
        state: String,
        actionType: String,
        actionDetail: String,
        success: Boolean
    ) {
        getDB(context).logDao().insert(
            LogEntity(
                packageName = packageName,
                state = state,
                actionType = actionType,
                actionDetail = actionDetail,
                success = success
            )
        )
    }

    /**
     * Record or increment a user behavior pattern.
     */
    suspend fun recordPattern(
        context: Context,
        state: String,
        packageName: String,
        actionText: String,
        actionType: String
    ) {
        val dao = getDB(context).userPatternDao()
        val existing = dao.get(state, actionText)
        if (existing != null) {
            dao.incrementCount(existing.id)
        } else {
            dao.insert(
                UserPatternEntity(
                    state = state,
                    packageName = packageName,
                    actionText = actionText,
                    actionType = actionType
                )
            )
        }
    }

    /**
     * Called when the user overrides an action the assistant just performed on the
     * same screen. Reduces that pattern's confidence; deletes it if it hits zero.
     */
    suspend fun recordCorrection(context: Context, state: String, actionText: String) {
        val dao = getDB(context).userPatternDao()
        dao.decrementCount(state, actionText)
        dao.deleteZeroCount()
    }

    /**
     * Decays patterns that haven't been observed in [decayAfterDays] days by
     * subtracting 1 from their confidence, then removes those that reach zero.
     * Safe to call repeatedly — only affects genuinely stale patterns.
     */
    suspend fun decayOldPatterns(context: Context, decayAfterDays: Int = 30) {
        val dao = getDB(context).userPatternDao()
        val cutoffMs = System.currentTimeMillis() - decayAfterDays.toLong() * 24 * 60 * 60 * 1_000
        dao.decrementStalePatterns(cutoffMs)
        dao.deleteZeroCount()
    }

    suspend fun seedDefaultRules(context: Context) {
        val dao = getDB(context).ruleDao()
        if (dao.count() == 0) {
            dao.insert(RuleEntity(keyword = "skip", actionType = "CLICK"))
            dao.insert(RuleEntity(keyword = "allow", actionType = "CLICK"))
            dao.insert(RuleEntity(keyword = "ok", actionType = "CLICK"))
            dao.insert(RuleEntity(keyword = "accept", actionType = "CLICK"))
            dao.insert(RuleEntity(keyword = "continue", actionType = "CLICK"))
            dao.insert(RuleEntity(keyword = "dismiss", actionType = "CLICK"))
        }
    }
}
