package io.github.oleglog.olcrtc.client.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.oleglog.olcrtc.client.routing.RoutingRule

@Entity(
    tableName = "olcrtc_profiles",
    indices = [Index(value = ["identityHash"])],
)
internal data class OlcrtcProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityHash: String?,
    val name: String,
    val provider: String,
    val transport: String,
    @ColumnInfo(defaultValue = "'legacy'") val compatibilityMode: String,
    val roomId: String,
    val roomPassword: ByteArray?,
    val clientId: String,
    val keyHex: ByteArray,
    val dnsServer: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int,
    val keepaliveIntervalSeconds: Int,
)

@Dao
internal interface OlcrtcProfileDao {
    @Query("SELECT * FROM olcrtc_profiles WHERE id = :id")
    fun get(id: Long): OlcrtcProfileEntity?

    @Query("SELECT * FROM olcrtc_profiles ORDER BY name, id")
    fun getAll(): List<OlcrtcProfileEntity>

    @Query("SELECT * FROM olcrtc_profiles WHERE identityHash = :identityHash ORDER BY id LIMIT 1")
    fun findByIdentity(identityHash: String): OlcrtcProfileEntity?

    @Query("DELETE FROM olcrtc_profiles WHERE id = :id")
    fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(profile: OlcrtcProfileEntity): Long

    @Update
    fun update(profile: OlcrtcProfileEntity)
}

@Entity(
    tableName = "openflux_profiles",
    indices = [Index(value = ["identityHash"])],
)
internal data class OpenFluxProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityHash: String?,
    val name: String,
    val documentUrl: ByteArray,
    val transport: String,
    val dnsServer: String,
)

@Dao
internal interface OpenFluxProfileDao {
    @Query("SELECT * FROM openflux_profiles WHERE id = :id")
    fun get(id: Long): OpenFluxProfileEntity?

    @Query("SELECT * FROM openflux_profiles ORDER BY name, id")
    fun getAll(): List<OpenFluxProfileEntity>

    @Query("SELECT * FROM openflux_profiles WHERE identityHash = :identityHash ORDER BY id LIMIT 1")
    fun findByIdentity(identityHash: String): OpenFluxProfileEntity?

    @Query("DELETE FROM openflux_profiles WHERE id = :id")
    fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(profile: OpenFluxProfileEntity): Long

    @Update
    fun update(profile: OpenFluxProfileEntity)
}

@Entity(
    tableName = "standard_profiles",
    indices = [Index(value = ["identityHash"])],
)
internal data class StandardProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityHash: String?,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val secret: ByteArray,
)

@Dao
internal interface StandardProfileDao {
    @Query("SELECT * FROM standard_profiles WHERE id = :id")
    fun get(id: Long): StandardProfileEntity?

    @Query("SELECT * FROM standard_profiles ORDER BY name, id")
    fun getAll(): List<StandardProfileEntity>

    @Query("SELECT * FROM standard_profiles WHERE identityHash = :identityHash ORDER BY id LIMIT 1")
    fun findByIdentity(identityHash: String): StandardProfileEntity?

    @Query("DELETE FROM standard_profiles WHERE id = :id")
    fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(profile: StandardProfileEntity): Long

    @Update
    fun update(profile: StandardProfileEntity)
}

@Entity(tableName = "profile_groups")
internal data class ProfileGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val subscriptionId: Long?,
    val sortOrder: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = ProfileGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId"], unique = true)],
)
internal data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
    val kind: String,
    val encryptedUrl: ByteArray,
    val serverVersion: String?,
    val encryptedMirrorType: ByteArray?,
    val encryptedMirrorUrl: ByteArray?,
    val encryptedMirrorKey: ByteArray?,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val lastErrorCode: String?,
    val updateIntervalHours: Int,
    val etag: String?,
    val lastModified: String?,
    val enabled: Boolean,
)

@Entity(
    tableName = "subscription_profiles",
    foreignKeys = [
        ForeignKey(
            entity = ProfileGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["groupId", "identityHash"], unique = true),
    ],
)
internal data class SubscriptionProfileEntity(
    @PrimaryKey val id: String,
    val groupId: Long,
    val type: String,
    val name: String,
    @ColumnInfo(defaultValue = "'legacy'") val compatibilityMode: String,
    val encryptedConfigJson: ByteArray,
    val encryptedUpstreamConfigJson: ByteArray?,
    val identityHash: String,
    val isLocallyModified: Boolean,
    val favorite: Boolean,
    val sortOrder: Int,
    val lastLatencyMs: Long?,
    val lastCheckedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isDeleted: Boolean = false,
)

internal data class SubscriptionGroupRow(
    @ColumnInfo(name = "group_id") val groupId: Long,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Long,
)

@Entity(
    tableName = "connection_sessions",
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["startedAt"]),
    ],
)
internal data class ConnectionSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String?,
    val profileNameSnapshot: String,
    val protocolSnapshot: String,
    val startedAt: Long,
    val endedAt: Long?,
    val bytesUp: Long,
    val bytesDown: Long,
    val disconnectReason: String?,
    val networkType: String,
)

internal data class ConnectionSessionTotals(
    val count: Int,
    val durationMillis: Long,
    val bytesUp: Long,
    val bytesDown: Long,
)

@Entity(tableName = "app_routing_entries")
internal data class AppRoutingEntryEntity(
    @PrimaryKey val packageName: String,
    val selected: Boolean,
)

@Dao
internal interface AppRoutingEntryDao {
    @Query("SELECT * FROM app_routing_entries ORDER BY packageName")
    fun getAll(): List<AppRoutingEntryEntity>

    @Query("DELETE FROM app_routing_entries")
    fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<AppRoutingEntryEntity>)
}

@Entity(
    tableName = "routing_rules",
    indices = [Index(value = ["matchType", "value"], unique = true)],
)
internal data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchType: String,
    val value: String,
    val action: String,
    val enabled: Boolean,
    val sortOrder: Int,
)

@Dao
internal interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY sortOrder, id")
    fun getAll(): List<RoutingRuleEntity>

    @Query("SELECT * FROM routing_rules WHERE id = :id")
    fun get(id: Long): RoutingRuleEntity?

    @Query("SELECT * FROM routing_rules WHERE matchType = :matchType AND value = :value LIMIT 1")
    fun find(matchType: String, value: String): RoutingRuleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(rule: RoutingRuleEntity): Long

    @Update
    fun update(rule: RoutingRuleEntity): Int

    @Query("UPDATE routing_rules SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean): Int

    @Query("DELETE FROM routing_rules WHERE id = :id")
    fun delete(id: Long): Int
}

@Dao
internal interface ConnectionSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(session: ConnectionSessionEntity): Long

    @Query(
        "UPDATE connection_sessions SET " +
            "endedAt = :endedAt, " +
            "bytesUp = :bytesUp, " +
            "bytesDown = :bytesDown, " +
            "disconnectReason = :disconnectReason " +
            "WHERE id = :id",
    )
    fun finish(
        id: Long,
        endedAt: Long,
        bytesUp: Long,
        bytesDown: Long,
        disconnectReason: String?,
    ): Int

    @Query("SELECT * FROM connection_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun getActive(): ConnectionSessionEntity?

    @Query("SELECT * FROM connection_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun getRecent(limit: Int): List<ConnectionSessionEntity>

    @Query("DELETE FROM connection_sessions WHERE endedAt IS NOT NULL")
    fun clear(): Int

    @Query(
        "SELECT COUNT(*) AS count, " +
            "COALESCE(SUM(COALESCE(endedAt, :now) - startedAt), 0) AS durationMillis, " +
            "COALESCE(SUM(bytesUp), 0) AS bytesUp, COALESCE(SUM(bytesDown), 0) AS bytesDown " +
            "FROM connection_sessions WHERE startedAt >= :since",
    )
    fun totalsSince(since: Long, now: Long): ConnectionSessionTotals
}

@Dao
internal abstract class SubscriptionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertGroup(group: ProfileGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSubscription(subscription: SubscriptionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertProfiles(profiles: List<SubscriptionProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOlcrtcProfiles(profiles: List<OlcrtcProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertStandardProfiles(profiles: List<StandardProfileEntity>)

    @Update
    protected abstract fun updateSubscription(subscription: SubscriptionEntity)

    @Update
    protected abstract fun updateProfiles(profiles: List<SubscriptionProfileEntity>)

    @Query("DELETE FROM subscription_profiles WHERE id IN (:ids)")
    protected abstract fun deleteProfiles(ids: List<String>)

    @Query("DELETE FROM profile_groups WHERE id = :groupId")
    protected abstract fun deleteGroup(groupId: Long)

    @Query("UPDATE profile_groups SET subscriptionId = :subscriptionId WHERE id = :groupId")
    protected abstract fun attachSubscription(groupId: Long, subscriptionId: Long)

    @Query("SELECT * FROM profile_groups WHERE id = :id")
    abstract fun getGroup(id: Long): ProfileGroupEntity?

    @Query("SELECT * FROM profile_groups WHERE type = 'subscription' ORDER BY sortOrder, id")
    abstract fun getSubscriptionGroups(): List<ProfileGroupEntity>

    @Query("SELECT * FROM profile_groups WHERE type = 'local' LIMIT 1")
    abstract fun getLocalGroup(): ProfileGroupEntity?

    @Query("SELECT * FROM subscriptions WHERE groupId = :groupId")
    abstract fun getSubscription(groupId: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    abstract fun getSubscriptionById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE enabled = 1")
    abstract fun getEnabledSubscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions")
    abstract fun getAllSubscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription_profiles WHERE id = :id AND isDeleted = 0")
    abstract fun getProfile(id: String): SubscriptionProfileEntity?

    @Query("SELECT * FROM subscription_profiles WHERE groupId = :groupId AND isDeleted = 0 ORDER BY sortOrder, id")
    abstract fun getProfiles(groupId: Long): List<SubscriptionProfileEntity>

    @Query("SELECT * FROM subscription_profiles WHERE groupId = :groupId ORDER BY sortOrder, id")
    abstract fun getAllProfiles(groupId: Long): List<SubscriptionProfileEntity>

    @Query("SELECT * FROM subscription_profiles WHERE isDeleted = 0 ORDER BY groupId, sortOrder, id")
    abstract fun getAllActiveProfiles(): List<SubscriptionProfileEntity>

    @Query("SELECT * FROM subscription_profiles WHERE isDeleted = 0 AND (favorite = 1 OR groupId = (SELECT id FROM profile_groups WHERE type = 'local' LIMIT 1)) ORDER BY groupId, sortOrder, id")
    abstract fun getQuickProfiles(): List<SubscriptionProfileEntity>

    @Query("UPDATE subscriptions SET enabled = :enabled WHERE id = :id")
    abstract fun setSubscriptionEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE subscription_profiles SET favorite = :favorite WHERE id = :id")
    abstract fun setProfileFavorite(id: String, favorite: Boolean)

    @Query("UPDATE subscription_profiles SET sortOrder = :sortOrder WHERE id = :id")
    abstract fun setProfileSortOrder(id: String, sortOrder: Int)

    @Query("UPDATE subscription_profiles SET isDeleted = 1, updatedAt = :updatedAt WHERE id = :id")
    abstract fun markProfileDeleted(id: String, updatedAt: Long)

    @Query("SELECT profile_groups.id AS group_id, subscriptions.id AS subscription_id FROM profile_groups INNER JOIN subscriptions ON subscriptions.groupId = profile_groups.id WHERE profile_groups.type = 'subscription'")
    abstract fun getSubscriptionGroupRows(): List<SubscriptionGroupRow>

    @Transaction
    open fun createSubscription(
        name: String,
        encryptedUrl: ByteArray,
        updateIntervalHours: Int,
        profiles: List<SubscriptionProfileEntity>,
        now: Long,
        serverVersion: String? = null,
        encryptedMirrorType: ByteArray? = null,
        encryptedMirrorUrl: ByteArray? = null,
        encryptedMirrorKey: ByteArray? = null,
    ): Long {
        val maxSort = getSubscriptionGroups().maxOfOrNull { it.sortOrder } ?: 0
        val groupId = insertGroup(
            ProfileGroupEntity(
                name = name,
                type = "subscription",
                subscriptionId = null,
                sortOrder = maxSort + 1,
                createdAt = now,
            ),
        )
        val subId = insertSubscription(
            SubscriptionEntity(
                groupId = groupId,
                name = name,
                kind = "plain",
                encryptedUrl = encryptedUrl,
                serverVersion = serverVersion,
                encryptedMirrorType = encryptedMirrorType,
                encryptedMirrorUrl = encryptedMirrorUrl,
                encryptedMirrorKey = encryptedMirrorKey,
                lastSuccessAt = now,
                lastAttemptAt = now,
                lastErrorCode = null,
                updateIntervalHours = updateIntervalHours,
                etag = null,
                lastModified = null,
                enabled = true,
            ),
        )
        attachSubscription(groupId, subId)
        if (profiles.isNotEmpty()) {
            insertProfiles(profiles.map { it.copy(groupId = groupId) })
        }
        return subId
    }

    @Transaction
    open fun replaceSubscriptionProfiles(
        groupId: Long,
        newProfiles: List<SubscriptionProfileEntity>,
        retainedOlcrtcProfiles: List<OlcrtcProfileEntity> = emptyList(),
        retainedStandardProfiles: List<StandardProfileEntity> = emptyList(),
    ) {
        val existing = getProfiles(groupId)
        val deleteIds = existing.map { it.id }
        if (deleteIds.isNotEmpty()) {
            deleteProfiles(deleteIds)
        }
        if (newProfiles.isNotEmpty()) {
            insertProfiles(newProfiles.map { it.copy(groupId = groupId) })
        }
        if (retainedOlcrtcProfiles.isNotEmpty()) {
            insertOlcrtcProfiles(retainedOlcrtcProfiles)
        }
        if (retainedStandardProfiles.isNotEmpty()) {
            insertStandardProfiles(retainedStandardProfiles)
        }
    }

    @Transaction
    open fun deleteSubscription(id: Long) {
        val sub = getSubscriptionById(id) ?: return
        deleteGroup(sub.groupId)
    }

    @Transaction
    open fun updateSubscriptionMetadata(
        id: Long,
        serverVersion: String?,
        encryptedMirrorType: ByteArray?,
        encryptedMirrorUrl: ByteArray?,
        encryptedMirrorKey: ByteArray?,
        etag: String?,
        lastModified: String?,
        lastAttemptAt: Long?,
        lastSuccessAt: Long?,
        lastErrorCode: String?,
    ) {
        val sub = getSubscriptionById(id) ?: return
        updateSubscription(
            sub.copy(
                serverVersion = serverVersion ?: sub.serverVersion,
                encryptedMirrorType = encryptedMirrorType ?: sub.encryptedMirrorType,
                encryptedMirrorUrl = encryptedMirrorUrl ?: sub.encryptedMirrorUrl,
                encryptedMirrorKey = encryptedMirrorKey ?: sub.encryptedMirrorKey,
                etag = etag ?: sub.etag,
                lastModified = lastModified ?: sub.lastModified,
                lastAttemptAt = lastAttemptAt ?: sub.lastAttemptAt,
                lastSuccessAt = lastSuccessAt ?: sub.lastSuccessAt,
                lastErrorCode = lastErrorCode,
            ),
        )
    }
}

internal val localGroupCallback = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """INSERT INTO profile_groups (id, name, type, subscriptionId, sortOrder, createdAt)
               VALUES (1, 'Saved', 'local', NULL, 0, strftime('%s', 'now') * 1000)""",
        )
    }
}

internal class AppRoutingRepository(private val db: ClientDatabase) {
    private val dao = db.appRoutingEntries()

    fun getAll(): Set<String> = dao.getAll().mapTo(LinkedHashSet()) { it.packageName }

    fun replaceAll(packageNames: Set<String>) {
        val entries = packageNames.map { AppRoutingEntryEntity(it, true) }
        db.runInTransaction {
            dao.clear()
            dao.insertAll(entries)
        }
    }
}

internal class RoutingRuleRepository(db: ClientDatabase) {
    private val rules = db.routingRules()

    fun list(): List<RoutingRule> = rules.getAll()
        .map { it.toRule() }
        .sortedWith(
            compareBy<RoutingRule> { it.action != RoutingRule.Action.DIRECT }
                .thenByDescending(RoutingRule::specificity)
                .thenBy(RoutingRule::sortOrder)
                .thenBy(RoutingRule::id),
        )

    fun save(rule: RoutingRule): Long {
        val normalized = RoutingRule.create(
            id = rule.id,
            matchType = rule.matchType,
            value = rule.value,
            action = rule.action,
            enabled = rule.enabled,
            sortOrder = rule.sortOrder,
        )
        val entity = normalized.toEntity()
        val existing = rules.find(entity.matchType, entity.value)
        return when {
            normalized.id > 0 -> {
                require(rules.update(entity) == 1) { "Routing rule not found" }
                normalized.id
            }
            existing != null -> {
                check(rules.update(entity.copy(id = existing.id)) == 1)
                existing.id
            }
            else -> rules.insert(entity)
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        require(rules.setEnabled(id, enabled) == 1) { "Routing rule not found" }
    }

    fun delete(id: Long) {
        require(rules.delete(id) == 1) { "Routing rule not found" }
    }

    private val RoutingRule.specificity: Int
        get() = when (matchType) {
            RoutingRule.MatchType.IP -> 1_000
            RoutingRule.MatchType.CIDR -> 500 + value.substringAfter('/').toInt()
            RoutingRule.MatchType.DOMAIN -> 400 + value.length
            RoutingRule.MatchType.DOMAIN_SUFFIX -> 300 + value.length
        }

    private fun RoutingRuleEntity.toRule(): RoutingRule = RoutingRule(
        id = id,
        matchType = RoutingRule.MatchType.valueOf(matchType),
        value = value,
        action = RoutingRule.Action.valueOf(action),
        enabled = enabled,
        sortOrder = sortOrder,
    )

    private fun RoutingRule.toEntity(): RoutingRuleEntity = RoutingRuleEntity(
        id = id,
        matchType = matchType.name,
        value = value,
        action = action.name,
        enabled = enabled,
        sortOrder = sortOrder,
    )
}

@Database(
    entities = [
        OlcrtcProfileEntity::class,
        OpenFluxProfileEntity::class,
        StandardProfileEntity::class,
        ProfileGroupEntity::class,
        SubscriptionEntity::class,
        SubscriptionProfileEntity::class,
        ConnectionSessionEntity::class,
        AppRoutingEntryEntity::class,
        RoutingRuleEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
internal abstract class ClientDatabase : RoomDatabase() {
    abstract fun olcrtcProfiles(): OlcrtcProfileDao
    abstract fun openfluxProfiles(): OpenFluxProfileDao
    abstract fun standardProfiles(): StandardProfileDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun connectionSessions(): ConnectionSessionDao
    abstract fun appRoutingEntries(): AppRoutingEntryDao
    abstract fun routingRules(): RoutingRuleDao

    companion object {
        internal fun inMemory(context: Context): ClientDatabase = Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            ClientDatabase::class.java,
        ).addCallback(localGroupCallback).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `standard_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `protocol` TEXT NOT NULL, `address` TEXT NOT NULL, `port` INTEGER NOT NULL, `secret` BLOB NOT NULL)""",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `olcrtc_profiles` ADD COLUMN `identityHash` TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_olcrtc_profiles_identityHash` ON `olcrtc_profiles` (`identityHash`)")
                database.execSQL("ALTER TABLE `standard_profiles` ADD COLUMN `identityHash` TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_standard_profiles_identityHash` ON `standard_profiles` (`identityHash`)")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `connection_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` TEXT, `profileNameSnapshot` TEXT NOT NULL, `protocolSnapshot` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `bytesUp` INTEGER NOT NULL, `bytesDown` INTEGER NOT NULL, `disconnectReason` TEXT, `networkType` TEXT NOT NULL)""",
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_sessions_profileId` ON `connection_sessions` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_sessions_startedAt` ON `connection_sessions` (`startedAt`)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `serverVersion` TEXT")
                database.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `encryptedMirrorType` BLOB")
                database.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `encryptedMirrorUrl` BLOB")
                database.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `encryptedMirrorKey` BLOB")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `olcrtc_profiles` ADD COLUMN `compatibilityMode` TEXT NOT NULL DEFAULT 'legacy'")
                database.execSQL("ALTER TABLE `subscription_profiles` ADD COLUMN `compatibilityMode` TEXT NOT NULL DEFAULT 'legacy'")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `app_routing_entries` (`packageName` TEXT NOT NULL, `selected` INTEGER NOT NULL, PRIMARY KEY(`packageName`))""",
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `subscription_profiles` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `routing_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `matchType` TEXT NOT NULL, `value` TEXT NOT NULL, `action` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)""",
                )
                database.execSQL(
                    """CREATE UNIQUE INDEX IF NOT EXISTS `index_routing_rules_matchType_value` ON `routing_rules` (`matchType`, `value`)""",
                )
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `openflux_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `identityHash` TEXT, `name` TEXT NOT NULL, `documentUrl` BLOB NOT NULL, `transport` TEXT NOT NULL, `dnsServer` TEXT NOT NULL)""",
                )
                database.execSQL(
                    """CREATE INDEX IF NOT EXISTS `index_openflux_profiles_identityHash` ON `openflux_profiles` (`identityHash`)""",
                )
            }
        }
    }
}
