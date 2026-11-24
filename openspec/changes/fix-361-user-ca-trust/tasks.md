## 1. Config and guard test

- [ ] 1.1 Branch `fix/361-user-ca-trust` off the latest main (using a worktree, without touching the user's uncommitted changes in their workspace)
- [ ] 1.2 `network_security_config.xml`: add `<certificates src="user"/>` to the trust-anchors, and update the comments to explain the reason (same origin as the cleartext http precedent)
- [ ] 1.3 Add a guard test in `app/src/test`: parse that XML and assert that the trust-anchors contain both `system` and `user`, and that `cleartextTrafficPermitted` is still true

## 2. Verification

- [ ] 2.1 `./gradlew :app:testDebugUnitTest` all green
- [ ] 2.2 On-device verification: start a self-signed HTTPS endpoint on the local machine → connecting to it from the App fails before the CA is installed → succeeds after installing the user CA (the phone must be unlocked); keep screenshots/UI dumps as evidence in `openspec/changes/fix-361-user-ca-trust/evidence/`
- [ ] 2.3 Public endpoint regression: connections to normal providers are unaffected (connect to a model once or open an https page in the built-in browser)

## 3. Wrap-up

- [ ] 3.1 Commit in Chinese, write the PR description (linked to #361, with measured evidence on the first screen, stating the security trade-off and Non-Goals), and after user confirmation push to the fork and open the PR
