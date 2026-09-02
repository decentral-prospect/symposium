# Security Policy

## Supported versions

The latest stable Symposium Android release receives security fixes. The most
recent prerelease is supported on a best-effort basis while it is being tested.
Older releases are unsupported unless a release note explicitly says otherwise.

Users should update both the Android client and installed Symposium Relay
instances promptly after a security release.

## Reporting a vulnerability

Use [GitHub Private Vulnerability Reporting](https://github.com/decentral-prospect/symposium/security/advisories/new)
for confidential reports. Do not disclose suspected vulnerabilities in public
Issues, Discussions, pull requests, or social media before a coordinated fix is
available.

Include as much of the following as possible:

- affected Android and relay versions;
- device model, Android version, and installation method;
- a clear description of the impact and required attacker capabilities;
- reproducible steps, logs, proof-of-concept code, or packet captures with
  credentials and personal data removed;
- whether exploitation has been observed in the wild;
- any suggested mitigation or remediation;
- a safe way to contact you and whether you want public credit.

We aim to acknowledge a report within three business days, provide an initial
assessment within seven business days, and send progress updates at least every
fourteen days while remediation is active. These are response targets, not
guarantees; complex or cross-project issues can require more time.

## Security scope

Reports are especially useful when they affect:

- media end-to-end encryption, key derivation, authentication, or downgrade
  resistance;
- invitation and deep-link parsing, room secrets, or moderator secrets;
- SSH installation, privilege boundaries, service hardening, or randomized
  deployment paths;
- SSH host-key verification and pin persistence;
- server credentials, admin tokens, certificates, or private-key handling;
- relay binary checksum, provenance, bundled fallback, or execution controls;
- Android and relay update, build, signing, dependency, or release supply chains;
- telemetry consent, data minimization, authentication, or privacy leakage;
- WebRTC signaling, media routing, ICE, DTLS-SRTP, or frame encryption;
- Android Keystore use and local secret storage;
- authentication bypasses, privilege escalation, or unauthorized moderation.

Issues that exist only in Symposium Relay should normally be reported through
that repository's private security channel. If the boundary is unclear, report
it here and the maintainers will coordinate privately.

## Coordinated disclosure and good-faith research

Give maintainers a reasonable opportunity to investigate, patch, and notify
users before public disclosure. The project will coordinate disclosure timing
with reporters and affected upstream projects where practical.

Good-faith research should avoid privacy violations, service disruption,
destruction of data, persistence on systems you do not own, and access beyond
what is necessary to demonstrate the issue. Test against systems and accounts
you own or have explicit permission to use. Do not retain or disclose secrets or
personal data obtained during research.
