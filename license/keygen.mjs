/**
 * BEE-VPN license keypair generator.
 *
 *   node keygen.mjs
 *
 * Prints the two base64url values the whole license system shares:
 *   LICENSE_ED25519_PRIV_B64  -> wrangler secret put LICENSE_ED25519_PRIV_B64
 *                                (license-worker only; NEVER in the repo or APK)
 *   LICENSE_ED25519_PUB_B64   -> license/wrangler.toml [vars] AND the GitHub
 *                                secret BEE_LICENSE_PUBKEY_B64 (goes into the APK)
 *
 * Run it once, store the output safely, then paste the public value into
 * license/wrangler.toml before deploying.
 */
import { generateKeyPairSync } from 'node:crypto';

const { publicKey, privateKey } = generateKeyPairSync('ed25519');
const pubJwk = publicKey.export({ format: 'jwk' });
const privJwk = privateKey.export({ format: 'jwk' });

console.log('──────────────────────────────────────────────────────');
console.log('BEE-VPN license keypair — generated', new Date().toISOString());
console.log('──────────────────────────────────────────────────────');
console.log();
console.log('PRIVATE (secret on the license Worker, keep offline):');
console.log('  LICENSE_ED25519_PRIV_B64 =', privJwk.d);
console.log();
console.log('PUBLIC (wrangler.toml [vars] + GitHub secret BEE_LICENSE_PUBKEY_B64):');
console.log('  LICENSE_ED25519_PUB_B64  =', pubJwk.x);
console.log();
console.log('Copy the PRIVATE value now and store it in a password manager.');
console.log('Losing it means re-issuing every installed license token.');
