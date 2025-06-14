package org.stellar.anchor.auth;

import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;
import static org.stellar.anchor.auth.JwtService.CLIENT_DOMAIN;
import static org.stellar.anchor.auth.JwtService.HOME_DOMAIN;

import com.google.gson.annotations.SerializedName;
import io.jsonwebtoken.Jwt;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.stellar.sdk.MuxedAccount;

@Getter
@Setter
@NoArgsConstructor
public abstract class WebAuthJwt extends AbstractJwt {
  @SerializedName(value = "client_domain")
  String clientDomain;

  @SerializedName(value = "home_domain")
  String homeDomain;

  String account;

  @SerializedName(value = "account_memo")
  String accountMemo;

  @SerializedName(value = "muxed_account")
  String muxedAccount;

  Long muxedAccountId;

  public String getTransactionId() {
    return this.jti;
  }

  public String getIssuer() {
    return this.iss;
  }

  public long getIssuedAt() {
    return this.iat;
  }

  public long getExpiresAt() {
    return this.exp;
  }

  public WebAuthJwt(Jwt jwt) {
    super(jwt);
    if (isNotEmpty(claims.get(CLIENT_DOMAIN)))
      this.clientDomain = claims.get(CLIENT_DOMAIN).toString();
    if (isNotEmpty(claims.get(HOME_DOMAIN))) this.homeDomain = claims.get(HOME_DOMAIN).toString();
    updateAccountAndMemo();
  }

  public WebAuthJwt(
      String iss,
      String sub,
      long iat,
      long exp,
      String jti,
      String clientDomain,
      String homeDomain) {
    this.iss = iss;
    this.sub = sub;
    this.iat = iat;
    this.exp = exp;
    this.jti = jti;
    this.clientDomain = clientDomain;
    this.homeDomain = homeDomain;
    updateAccountAndMemo();
  }

  void updateAccountAndMemo() {
    // Parse account & memo or muxedAccount & muxedAccountId:
    if (sub != null) {
      String[] subs = sub.split(":", 2);
      if (subs.length == 2) {
        this.account = subs[0];
        this.accountMemo = subs[1];
      } else {
        this.account = sub;
        this.accountMemo = null;

        try {
          MuxedAccount maybeMuxedAccount = new MuxedAccount(sub);
          if (maybeMuxedAccount.getMuxedId() != null) {
            this.muxedAccount = sub;
            this.account = maybeMuxedAccount.getAccountId();
            this.muxedAccountId = maybeMuxedAccount.getMuxedId().longValue();
          }
        } catch (Exception ignored) {
        }
      }
    }
  }
}
