#!/usr/bin/env node
/*
 * Signs the HWID whitelist file.
 *
 * Run this on your own PC after every edit of devices.json and commit the result next to it:
 *
 *   node tools/hwid-sign.mjs --file <path to devices.json> --key <path to hwid-signing-key.pem>
 *
 * writes   <path to devices.json>.sig
 *
 * That is the whole admin workflow on the signing side - no server, no CI, no GitHub Action. The app
 * fetches `devices.json` and `devices.json.sig` from the raw URL and verifies the second against the public
 * key compiled into it.
 *
 * The signature is ECDSA P-256 / SHA-256 over the EXACT bytes of the file, DER-encoded, base64-encoded
 * into the .sig file (one line). Consequence: any edit of devices.json - including reformatting it or
 * changing a newline - invalidates the signature and the app refuses to run until you sign again. That
 * is the point, and it is why this script prints a reminder to commit both files.
 *
 * The same thing with openssl, if you prefer:
 *
 *   openssl dgst -sha256 -sign hwid-signing-key.pem -out devices.json.sig.der devices.json
 *   openssl base64 -A -in devices.json.sig.der -out devices.json.sig
 *
 * Verify what you just produced (either toolchain):
 *
 *   openssl dgst -sha256 -verify hwid-signing-key.pub.pem -signature devices.json.sig.der devices.json
 *   node tools/hwid-sign.mjs --file devices.json --key hwid-signing-key.pem --verify-only
 */

import { createPrivateKey, createPublicKey, createSign, createVerify, sign as signRaw } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';

function parseArgs(argv) {
  const args = { file: null, key: null, sig: null, verifyOnly: false, quiet: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--file' || arg === '-f') args.file = argv[++i];
    else if (arg === '--key' || arg === '-k') args.key = argv[++i];
    else if (arg === '--sig' || arg === '-s') args.sig = argv[++i];
    else if (arg === '--verify-only') args.verifyOnly = true;
    else if (arg === '--quiet' || arg === '-q') args.quiet = true;
    else if (arg === '--help' || arg === '-h') args.help = true;
    else throw new Error(`unknown argument: ${arg}`);
  }
  return args;
}

function usage() {
  console.log(`usage:
  node tools/hwid-sign.mjs --file devices.json --key hwid-signing-key.pem
  node tools/hwid-sign.mjs --file devices.json --key hwid-signing-key.pem --verify-only

  --file   the whitelist to sign (the file you commit)
  --key    the private key from tools/hwid-keygen.mjs
  --sig    the signature file to write/read (default: <file>.sig)
  --verify-only   check an existing signature instead of creating one`);
}

function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    usage();
    process.exit(2);
  }

  if (args.help) {
    usage();
    return;
  }
  if (!args.file || !args.key) {
    usage();
    process.exit(2);
  }
  if (!existsSync(args.file)) {
    console.error(`no such file: ${args.file}`);
    process.exit(1);
  }
  if (!existsSync(args.key)) {
    console.error(`no such key: ${args.key}`);
    process.exit(1);
  }

  const sigPath = args.sig || `${args.file}.sig`;
  const payload = readFileSync(args.file);
  const privateKey = createPrivateKey(readFileSync(args.key));

  if (privateKey.asymmetricKeyType !== 'ec') {
    console.error(`the key is ${privateKey.asymmetricKeyType}, expected an EC (P-256) key`);
    process.exit(1);
  }

  if (args.verifyOnly) {
    if (!existsSync(sigPath)) {
      console.error(`no such signature file: ${sigPath}`);
      process.exit(1);
    }
    const signature = Buffer.from(readFileSync(sigPath, 'utf8').replace(/\s+/g, ''), 'base64');
    const verifier = createVerify('SHA256');
    verifier.update(payload);
    const ok = verifier.verify(createPublicKey(privateKey), signature);
    console.log(ok
      ? `OK: ${sigPath} is a valid signature of ${args.file}`
      : `FAILED: ${sigPath} does NOT match ${args.file} (or was made with a different key)`);
    process.exit(ok ? 0 : 1);
  }

  // `sign` with an EC key returns the DER-encoded ECDSA-Sig-Value, which is what JCA's
  // "SHA256withECDSA" on Android expects, and what `openssl dgst -sign` produces.
  const signer = createSign('SHA256');
  signer.update(payload);
  const signature = signer.sign(privateKey);

  writeFileSync(sigPath, Buffer.from(signature).toString('base64') + '\n');

  if (!args.quiet) {
    console.log(`signed   : ${args.file} (${payload.length} bytes)`);
    console.log(`signature: ${sigPath} (${Buffer.from(signature).toString('base64').length} base64 chars, ECDSA P-256 / SHA-256, DER)`);
    console.log('');
    console.log(`now commit BOTH files: ${args.file} and ${sigPath}`);
    console.log('the app refuses to run if they do not match, so commit them in the same commit.');
  }
}

main();
