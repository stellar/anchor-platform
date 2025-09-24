package org.stellar.anchor.platform.testutil

import javax.crypto.SecretKey
import org.stellar.anchor.util.KeyUtil

fun String.toSecretKey(): SecretKey {
  return KeyUtil.toSecretKeySpecOrNull(this)
}
