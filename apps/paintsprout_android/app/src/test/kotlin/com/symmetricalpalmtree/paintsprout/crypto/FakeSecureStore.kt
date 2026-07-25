package com.symmetricalpalmtree.paintsprout.crypto

/** An in-memory [SecureStore], so the logic built on it is testable off-device. */
class FakeSecureStore : SecureStore {
    val values = linkedMapOf<String, Any>()

    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun keys(): Set<String> = values.keys.toSet()
    override fun clear() = values.clear()
}
