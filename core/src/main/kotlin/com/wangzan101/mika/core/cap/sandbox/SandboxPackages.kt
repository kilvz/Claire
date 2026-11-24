package com.wangzan101.mika.core.cap.sandbox

/**
 * Packages installed in the runtime: Python and Node, plus what the skill script manifests need from `apk:`
 * (py3-pypdf: extracting text from PDFs; no bundled skill uses py3-pillow right now, but the package is kept for now), and tzdata, needed to follow the phone's timezone.
 * All of them are in Alpine 3.22's main/community, and the build installs them into the bundled runtime
 * (WANT in tools/sandbox/build-runtime.py matches this, verified by BundledRuntimeTest).
 */
val SANDBOX_PACKAGES: List<String> = listOf(
    "python3", "nodejs", "npm", "py3-pillow", "py3-pypdf", "tzdata",
    "py3-pip", "py3-setuptools", "py3-wheel", "py3-packaging",
    "poppler-utils", "font-wqy-zenhei", "fontconfig",
    "curl", "unzip", "zip", "git", "jq",
)
