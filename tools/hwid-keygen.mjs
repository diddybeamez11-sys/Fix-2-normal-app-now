#!/usr/bin/env node
/*
 * Generates the keypair that signs the HWID whitelist.
 *
 * Run this ONCE, on your own PC. It writes the private key to a file next to the script and prints the
 * public key as a single base64 line - that line is the only part of this that goes into the app
 * (LicenseConfig.SIGNING_PUBLIC_KEY_BASE64). The private key never leaves the machine and is never
 * committed anywhere: it is what makes a whitelist file authentic, so anybody who has it can add
 * themselves to the list.
 *
 *   node tools/hwid-keygen.mjs                       # writes ./hwid-signing-key.pem
 *   node tools/hwid-keygen.mjs --out /media/usb/hwid-key.pem
 *
 * ECDSA P-256 (prime256v1 / secp256r1) + SHA-256. Not Ed25519, for a boring reason: the JCA that ships
 * with Android only knows Ed25519 from API 33 up, and this app supports API 28. P-256 is available on
 * every Android version it runs on and is signed by `openssl` as well as by tools/hwid-sign.mjs.
 *
 * The equivalent with openssl (if you would rather not use Node):
 *   openssl ecparam -name prime256v1 -genkey -noout -out hwid-signing-key.pem
 *   openssl ec -in hwid-signing-key.pem -pubout -out hwid-signing-key.pub.pem
 *   # then paste the base64 between the BEGIN/END lines of hwid-signing-key.pub.pem
 */

import { generateKeyPairSync } from 'node:crypto';
import { writeFileSync, existsSync } from 'node:fs';

function parseArgs(argv) {
  const args = { out: 'hwid-signing-key.pem', force: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--out' || arg === '-o') args.out = argv[++i];
    else if (arg === '--force' || arg === '-f') args.force = true;
    else if (arg === '--help' || arg === '-h') args.help = true;
    else throw new Error(`unknown argument: ${arg}`);
  }
  return args;
}

function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exit(2);
  }

  if (args.help) {
    console.log('usage: node tools/hwid-keygen.mjs [--out <private key file>] [--force]');
    return;
  }

  if (existsSync(args.out) && !args.force) {
    console.error(`refusing to overwrite ${args.out} (pass --force if you really mean it)`);
    process.exit(1);
  }

  const { privateKey, publicKey } = generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    publicKeyEncoding: { type: 'spki', format: 'pem' },
  });

  writeFileSync(args.out, privateKey, { mode: 0o600 });

  const publicKeyBase64 = publicKey
    .replace(/-----BEGIN PUBLIC KEY-----/, '')
    .replace(/-----END PUBLIC KEY-----/, '')
    .replace(/\s+/g, '');

  console.log(`private key written to : ${args.out}   (mode 600 - keep this file, never commit it)`);
  console.log('');
  console.log('public key (paste this into');
  console.log('app/src/main/java/dev/sora/protohax/hwid/LicenseConfig.kt,');
  console.log('between the quotes of SIGNING_PUBLIC_KEY_BASE64):');
  console.log('');
  console.log(publicKeyBase64);
  console.log('');
  console.log('keep a backup of the private key: without it you cannot update the whitelist any more.');
}

main();
