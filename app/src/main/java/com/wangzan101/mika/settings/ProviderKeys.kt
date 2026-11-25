package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.KeyPoolManager

// Provider storage: adding, removing and rotating API keys, and allowance connections
/** All configured keys of the provider (KeyPool). */
fun ProviderStore.providerKeys(providerId: String): List<String> {
    val raw = providerKey(providerId) ?: return emptyList()
    return KeyPoolManager.shared.parseKeys(raw)
}

/** Appends a new key to the provider's key pool. */
fun ProviderStore.addProviderKey(providerId: String, newKey: String) {
    synchronized(this) {
        val current = providerKeys(providerId)
        val added = KeyPoolManager.shared.parseKeys(newKey)
        val combined = (current + added).distinct()
        setKey(providerId, combined.joinToString(", "))
    }
}

/** Removes the given key from the provider's key pool. */
fun ProviderStore.removeProviderKey(providerId: String, keyToRemove: String) {
    synchronized(this) {
        val current = providerKeys(providerId)
        val remaining = current.filterNot { it == keyToRemove }
        setKey(providerId, remaining.joinToString(", "))
    }
}

/** The provider's currently active usable key (preferring automatic rotation in the KeyPool, avoiding rate-limited keys). */
fun ProviderStore.activeApiKey(providerId: String): String? {
    val raw = providerKey(providerId) ?: return null
    KeyPoolManager.shared.register(providerId, raw)
    return KeyPoolManager.shared.getActiveKey(providerId) ?: providerKeys(providerId).firstOrNull()
}

/**
 * The provider's key for features that take a plain API key (speech, images, trial calls): null for account sign-ins, whose stored value is a session
 * (ChatGPT, xAI, OpenCode Go) and must never be sent as an API key.
 */
fun ProviderStore.plainApiKey(providerId: String): String? = when (provider(providerId)?.kind) {
    ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai, ProviderConfig.Kind.OpenCodeGo -> null
    else -> activeApiKey(providerId)
}

/** The key of the provider this model config belongs to. Dynamically prefers returning a healthy active key from the KeyPool. */
fun ProviderStore.apiKey(configId: String): String? =
    _configs.value.firstOrNull { it.id == configId }?.let { activeApiKey(it.providerId) }

/** The provider's raw key. When it cannot be decrypted (e.g. the system cleared the keystore) it counts as having no key. */
fun ProviderStore.providerKey(providerId: String): String? {
    val enc = synchronized(this) { keys[providerId] } ?: return null
    return runCatching { String(cipher.decrypt(decode(enc))) }.getOrNull()
}

/** Fetches a key only for a provider config that still matches this allowance query. */
internal fun ProviderStore.allowanceKey(provider: Provider, revision: Long): String? = synchronized(this) {
    if (matchesAllowanceConnection(provider, revision)) providerKey(provider.id) else null }

internal fun ProviderStore.matchesAllowanceConnection(provider: Provider, revision: Long): Boolean {
    val current = provider(provider.id) ?: return false
    return current.baseUrl == provider.baseUrl && current.kind == provider.kind && current.signIn == provider.signIn &&
        (_providerKeyRevisions.value[provider.id] ?: 0L) == revision
}

/** Whether this provider has a key stored (local services may leave it blank). */
fun ProviderStore.hasKey(providerId: String): Boolean = synchronized(this) { providerId in keys }

internal fun ProviderStore.setKey(providerId: String, apiKey: String) {
    keys = if (apiKey.isEmpty()) keys - providerId else keys + (providerId to encode(cipher.encrypt(apiKey.toByteArray())))
    writeAtomically(keyFile, keysJson())
    val revisions = _providerKeyRevisions.value
    _providerKeyRevisions.value = revisions + (providerId to ((revisions[providerId] ?: 0L) + 1))
    KeyPoolManager.shared.register(providerId, apiKey)
}

/** Replaces a provider's key (all models under it use this one). */
fun ProviderStore.updateProviderKey(providerId: String, apiKey: String) {
    synchronized(this) {
        if (provider(providerId) != null) setKey(providerId, apiKey)
    }
}
