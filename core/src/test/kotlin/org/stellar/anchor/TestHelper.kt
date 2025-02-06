package org.stellar.anchor

import io.mockk.every
import javax.crypto.SecretKey
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.util.KeyUtil
import org.stellar.anchor.util.KeyUtil.toSecretKeySpecOrNull

class TestHelper {
  companion object {
    const val TEST_ACCOUNT = "GBJDSMTMG4YBP27ZILV665XBISBBNRP62YB7WZA2IQX2HIPK7ABLF4C2"
    const val TEST_MEMO = "123456"

    fun createSep10Jwt(
      account: String = TEST_ACCOUNT,
      accountMemo: String? = null,
      hostUrl: String = "",
      clientDomain: String = "vibrant.stellar.org",
      homeDomain: String = "test.stellar.org",
    ): WebAuthJwt {
      val issuedAt: Long = System.currentTimeMillis() / 1000L
      return WebAuthJwt.of(
        "$hostUrl/auth",
        if (accountMemo == null) account else "$account:$accountMemo",
        issuedAt,
        issuedAt + 60,
        "",
        clientDomain,
        homeDomain,
      )
    }
  }
}

// TODO: remove copypaste below
fun String.toSecretKey(): SecretKey {
  return toSecretKeySpecOrNull(this)
}

const val TEST_SIGNING_SEED = "SBVEOFAHGJCKGR4AAM7RTDRCP6RMYYV5YUV32ZK7ZD3VPDGGHYLXTZRZ"

fun SecretConfig.setupMock(block: (() -> Any)? = null) {
  val cfg = this

  every { cfg.sep6MoreInfoUrlJwtSecret } returns
    "jwt_secret_sep_6_more_info_url_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
  every { cfg.sep10JwtSecretKey } returns
    "jwt_secret_sep_10_secret_key_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
  every { cfg.sep10SigningSeed } returns TEST_SIGNING_SEED.also { KeyUtil.validateJWTSecret(it) }
  every { cfg.sep24InteractiveUrlJwtSecret } returns
    "jwt_secret_sep_24_interactive_url_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
  every { cfg.sep24MoreInfoUrlJwtSecret } returns
    "jwt_secret_sep_24_more_info_url_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
  every { cfg.callbackAuthSecret } returns
    "callback_auth_secret_key____________________________".also { KeyUtil.validateJWTSecret(it) }
  every { cfg.platformAuthSecret } returns
    "platform_auth_secret_key____________________________".also { KeyUtil.validateJWTSecret(it) }

  block?.invoke()
}

fun CustodySecretConfig.setupMock() {
  val cfg = this
  every { cfg.custodyAuthSecret } returns
    "custody_auth_secret_key_________________________".also { KeyUtil.validateJWTSecret(it) }
}
