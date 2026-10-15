package org.stellar.anchor.sep38;

import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.stellar.anchor.auth.WebAuthJwt;

/**
 * Who created a SEP-38 quote. One definition shared by quote creation, {@code GET /quote} and the
 * SEP-6, SEP-24 and SEP-31 flows that consume a quote.
 */
public final class Sep38QuoteOwnership {
  private Sep38QuoteOwnership() {}

  /**
   * The identity a JWT carries: the muxed account when present, otherwise the account plus its
   * {@code id} memo when the token has one.
   */
  public record Identity(String account, String memo, String memoType) {}

  /**
   * @return the identity of the token, or null when the token is null or carries no account
   */
  public static Identity identityOf(WebAuthJwt token) {
    if (token == null) {
      return null;
    }
    if (!Objects.toString(token.getMuxedAccount(), "").isEmpty()) {
      return new Identity(token.getMuxedAccount(), null, null);
    }
    if (!Objects.toString(token.getAccount(), "").isEmpty()) {
      return token.getAccountMemo() != null
          ? new Identity(token.getAccount(), token.getAccountMemo(), "id")
          : new Identity(token.getAccount(), null, null);
    }
    return null;
  }

  /**
   * @return true when the token's identity equals the quote's creator in account, memo and memo
   *     type
   */
  public static boolean isCreatedBy(Sep38Quote quote, WebAuthJwt token) {
    Identity caller = identityOf(token);
    return caller != null
        && StringUtils.equals(quote.getCreatorAccountId(), caller.account())
        && StringUtils.equals(quote.getCreatorMemo(), caller.memo())
        && StringUtils.equals(quote.getCreatorMemoType(), caller.memoType());
  }
}
