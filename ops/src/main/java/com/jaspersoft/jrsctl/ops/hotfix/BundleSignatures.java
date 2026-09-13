package com.jaspersoft.jrsctl.ops.hotfix;

/**
 * The two ways a bundle can fail signature verification, worded once for planning and for the
 * verify step (spec §8.2 step 1). Invariant: a signature that is present but verifies against no
 * trusted key is never waived, because the bundle carries no signer id and an unknown signer cannot
 * be told from a bundle altered after signing; {@code --allow-unsigned} covers only a missing
 * signature (assessment item H3).
 */
final class BundleSignatures {

  static final String MISSING = "the bundle carries no SIGNATURE";

  static final String FAILING =
      "the bundle signature verifies against no trusted key: the signer's key is not in the ring,"
          + " or the bundle was altered after it was signed";

  private BundleSignatures() {}
}
