#!/usr/bin/env bash
# Regenerates the certificate-floor test fixtures in app/src/test/resources/tls.
#
# Every key here is made by this script for the tests and is a credential for nothing: the
# certificates name localhost and kydrive.test, and only a debuggable APK built with
# -PscreenshotCa=app/src/test/resources/tls/ca.pem ever trusts the CA. Release builds cannot:
# debug-overrides apply to debuggable builds only, and SourceRulesTest keeps fixtures out of
# src/main. Certificates last 20 years so a stale clone does not fail on expiry.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
out=app/src/test/resources/tls
days=7300
mkdir -p "$out"

ec() { openssl genpkey -out "$out/$1.key" -algorithm EC -pkeyopt "ec_paramgen_curve:$2" 2> /dev/null; }
rsa() { openssl genpkey -out "$out/$1.key" -algorithm RSA -pkeyopt "rsa_keygen_bits:$2" 2> /dev/null; }

# root <name> <cn> <digest>
root() {
  openssl req -x509 -new -key "$out/$1.key" -"$3" -days $days -subj "/CN=$2" \
    -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign \
    -out "$out/$1.pem" 2> /dev/null
}

# sign <name> <cn> <issuer> <digest> <extensions>
sign() {
  openssl req -new -key "$out/$1.key" -subj "/CN=$2" -out "$out/$1.csr" 2> /dev/null
  printf '%s\n' "$5" > "$out/$1.ext"
  openssl x509 -req -in "$out/$1.csr" -CA "$out/$3.pem" -CAkey "$out/$3.key" -CAcreateserial \
    -"$4" -days $days -extfile "$out/$1.ext" -out "$out/$1.pem" 2> /dev/null
  rm -f "$out/$1.csr" "$out/$1.ext"
}

serves='subjectAltName=DNS:localhost,IP:127.0.0.1
extendedKeyUsage=serverAuth'
elsewhere='subjectAltName=DNS:kydrive.test
extendedKeyUsage=serverAuth'
authority='basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign'

# The CA the tests trust, and one they never install.
ec ca P-256 && root ca kydrive-test-ca sha256
ec other-ca P-256 && root other-ca kydrive-other-test-ca sha256

# Leaves under the trusted CA: the key is the only difference between them.
ec strong P-256 && sign strong localhost ca sha256 "$serves"
ec ec384 P-384 && sign ec384 localhost ca sha256 "$serves"
ec ec521 P-521 && sign ec521 localhost ca sha256 "$serves"
ec ec224 P-224 && sign ec224 localhost ca sha256 "$serves"
rsa rsa2048 2048 && sign rsa2048 localhost ca sha256 "$serves"
rsa rsa2047 2047 && sign rsa2047 localhost ca sha256 "$serves"
openssl genpkey -out "$out/ed25519.key" -algorithm ED25519 2> /dev/null
sign ed25519 localhost ca sha256 "$serves"

# Same strong key, a SHA-1 signature.
ec sha1 P-256 && sign sha1 localhost ca sha1 "$serves"
# Same subject as its issuer, but a different key: self-issued is not a trust anchor.
ec self-issued-sha1 P-256 && sign self-issued-sha1 kydrive-test-ca ca sha1 "$serves"

# Strong and trusted, but for another name; and strong but under a CA nobody installed.
ec othername P-256 && sign othername kydrive.test ca sha256 "$elsewhere"
ec untrusted P-256 && sign untrusted localhost other-ca sha256 "$serves"

# A weak intermediate under the trusted CA, and a strong leaf under it.
rsa weak-int 1024 && sign weak-int kydrive-weak-intermediate ca sha256 "$authority"
ec under-weak-int P-256 && sign under-weak-int localhost weak-int sha256 "$serves"

# A weak root, and a root whose own signature is SHA-1 (not verified by anyone).
rsa weak-root 1024 && root weak-root kydrive-weak-root sha256
ec under-weak-root P-256 && sign under-weak-root localhost weak-root sha256 "$serves"
ec sha1-root P-256 && root sha1-root kydrive-sha1-root sha1
ec under-sha1-root P-256 && sign under-sha1-root localhost sha1-root sha256 "$serves"

rm -f "$out"/*.srl
chmod 644 "$out"/*.key "$out"/*.pem
