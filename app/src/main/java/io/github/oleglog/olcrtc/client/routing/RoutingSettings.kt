package io.github.oleglog.olcrtc.client.routing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal class RoutingSettings private constructor(
    private val store: DataStore<Preferences>,
) {
    fun get(): RoutingPolicy = runBlocking {
        val preferences = store.data.first()
        RoutingPolicy(
            preset = preferences[PRESET]
                ?.let { runCatching { RoutingPolicy.Preset.valueOf(it) }.getOrNull() }
                ?: RoutingPolicy.Preset.ALL_VPN,
            allowLan = preferences[ALLOW_LAN] ?: false,
        )
    }

    fun getDnsServer(): String? = runBlocking {
        store.data.first()[DNS_SERVER]
    }

    fun getBackgroundEffects(): Boolean = runBlocking {
        store.data.first()[BACKGROUND_EFFECTS] ?: false
    }

    fun getPerAppPolicy(): PerAppPolicy = runBlocking {
        val preferences = store.data.first()
        PerAppPolicy(
            mode = preferences[PER_APP_MODE]
                ?.let { runCatching { PerAppPolicy.Mode.valueOf(it) }.getOrNull() }
                ?: PerAppPolicy.Mode.ALL,
            packages = preferences[PER_APP_PACKAGES] ?: emptySet(),
        )
    }

    fun getVpnIntent(): VpnIntent = runBlocking {
        val preferences = store.data.first()
        val localProfileId = preferences[VPN_LOCAL_PROFILE_ID]?.takeIf { it > 0 }
        val subscriptionProfileId = preferences[VPN_SUBSCRIPTION_PROFILE_ID]?.takeIf(String::isNotBlank)
        VpnIntent(
            desiredConnected = preferences[VPN_DESIRED_CONNECTED] ?: false,
            localProfileId = localProfileId,
            subscriptionProfileId = subscriptionProfileId.takeIf { localProfileId == null },
        )
    }

    suspend fun setDnsServer(value: String?) {
        val normalized = value?.let { DnsEndpoint.parse(it).toString() }
        store.edit { preferences ->
            if (normalized == null) preferences.remove(DNS_SERVER) else preferences[DNS_SERVER] = normalized
        }
    }

    suspend fun setBackgroundEffects(enabled: Boolean) {
        store.edit { preferences -> preferences[BACKGROUND_EFFECTS] = enabled }
    }

    suspend fun set(policy: RoutingPolicy) {
        store.edit { preferences ->
            preferences[PRESET] = policy.preset.name
            preferences[ALLOW_LAN] = policy.allowLan
        }
    }

    suspend fun setPerAppPolicy(policy: PerAppPolicy) {
        store.edit { preferences ->
            preferences[PER_APP_MODE] = policy.mode.name
            preferences[PER_APP_PACKAGES] = policy.packages
        }
    }

    fun setVpnIntent(intent: VpnIntent) = runBlocking {
        store.edit { preferences ->
            preferences[VPN_DESIRED_CONNECTED] = intent.desiredConnected
            if (intent.localProfileId == null) {
                preferences.remove(VPN_LOCAL_PROFILE_ID)
            } else {
                preferences[VPN_LOCAL_PROFILE_ID] = intent.localProfileId
            }
            if (intent.subscriptionProfileId == null) {
                preferences.remove(VPN_SUBSCRIPTION_PROFILE_ID)
            } else {
                preferences[VPN_SUBSCRIPTION_PROFILE_ID] = intent.subscriptionProfileId
            }
        }
    }

    data class VpnIntent(
        val desiredConnected: Boolean,
        val localProfileId: Long? = null,
        val subscriptionProfileId: String? = null,
    ) {
        init {
            require(localProfileId == null || localProfileId > 0) { "Profile ID must be positive" }
            require(subscriptionProfileId == null || subscriptionProfileId.isNotBlank()) {
                "Subscription profile ID must not be blank"
            }
            require(localProfileId == null || subscriptionProfileId == null) {
                "Only one active profile may be selected"
            }
        }
    }

    companion object {
        private const val FILE_NAME = "routing"
        private val PRESET = stringPreferencesKey("preset")
        private val ALLOW_LAN = booleanPreferencesKey("allow_lan")
        private val DNS_SERVER = stringPreferencesKey("dns_server")
        private val BACKGROUND_EFFECTS = booleanPreferencesKey("background_effects")
        private val PER_APP_MODE = stringPreferencesKey("per_app_mode")
        private val PER_APP_PACKAGES = stringSetPreferencesKey("per_app_packages")
        private val VPN_DESIRED_CONNECTED = booleanPreferencesKey("vpn_desired_connected")
        private val VPN_LOCAL_PROFILE_ID = longPreferencesKey("vpn_local_profile_id")
        private val VPN_SUBSCRIPTION_PROFILE_ID = stringPreferencesKey("vpn_subscription_profile_id")

        @Volatile
        private var instance: RoutingSettings? = null

        fun open(context: Context): RoutingSettings = instance ?: synchronized(this) {
            instance ?: RoutingSettings(
                MultiProcessDataStoreFactory.create(
                    serializer = PreferencesFileSerializer,
                    produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
                ),
            ).also { instance = it }
        }
    }
}
