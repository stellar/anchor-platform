package org.stellar.anchor.sep31

/**
 * An in-memory owner store with the comparison semantics of `JdbcSep31CustomerIdOwnerStore`: the
 * first claim wins, and a later caller matches when the owner account is equal and, unless the
 * caller ignores the memo, the owner memo is equal too. Services under test run against this
 * instead of a mock so a test asserts who is refused, not which argument was passed.
 */
class InMemoryCustomerIdOwnerStore : Sep31CustomerIdOwnerStore {
  private data class Owner(val account: String?, val memo: String?)

  private val owners = mutableMapOf<String, Owner>()

  override fun verifyOrClaim(
    customerId: String,
    creatorAccount: String?,
    creatorMemo: String?,
    ignoreMemo: Boolean,
  ): Boolean {
    if (owners.putIfAbsent(customerId, Owner(creatorAccount, creatorMemo)) == null) return true
    return matches(customerId, creatorAccount, creatorMemo, ignoreMemo)
  }

  override fun isClaimed(customerId: String): Boolean = owners.containsKey(customerId)

  override fun verify(
    customerId: String,
    creatorAccount: String?,
    creatorMemo: String?,
    ignoreMemo: Boolean,
  ): Boolean = matches(customerId, creatorAccount, creatorMemo, ignoreMemo)

  override fun getCreatorMemo(customerId: String): String? = owners[customerId]?.memo

  override fun reconcileLegacyKey(
    customerId: String,
    legacyCreatorAccount: String?,
    legacyCreatorMemo: String?,
    newCreatorAccount: String?,
    newCreatorMemo: String?,
  ): Boolean = false

  private fun matches(
    customerId: String,
    creatorAccount: String?,
    creatorMemo: String?,
    ignoreMemo: Boolean,
  ): Boolean {
    val owner = owners[customerId] ?: return false
    return owner.account == creatorAccount && (ignoreMemo || owner.memo == creatorMemo)
  }
}
