"""Packages produced by plugins/build.py must install into the app.

The app's installer (PluginInstaller.checkName) treats entries containing backslashes or colons as unsafe paths and rejects the whole package;
and on Windows os.path.relpath returns exactly those backslashes. Also, __pycache__/.pyc and Finder's
.DS_Store are not package content but get picked up by os.walk.

Run with: python3 -m unittest discover -s plugins -v
"""
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402


def make_plugin(root: str, name: str = "com.probe.demo", version: str = "1.0.0") -> str:
    """Build a minimal plugin with a script, a bytecode cache and a Finder record."""
    src = os.path.join(root, name)
    os.makedirs(os.path.join(src, "scripts"))
    os.makedirs(os.path.join(src, "__pycache__"))
    manifest = '{"schema":1,"id":"%s","name":"probe","version":"%s","kind":"capability"}\n' % (name, version)
    with open(os.path.join(src, "manifest.json"), "w", encoding="utf-8") as f:
        f.write(manifest)
    with open(os.path.join(src, "scripts", "run.sh"), "w", encoding="utf-8") as f:
        f.write("#!/bin/sh\necho hi\n")
    with open(os.path.join(src, "__pycache__", "run.cpython-312.pyc"), "wb") as f:
        f.write(b"\x00")
    with open(os.path.join(src, ".DS_Store"), "wb") as f:
        f.write(b"\x00")
    return src


class EntryNameTest(unittest.TestCase):
    def test_windows_relative_path_becomes_forward_slashes(self):
        self.assertEqual("scripts/rewrite_prompt.sh", build.entry_name("scripts\\rewrite_prompt.sh"))

    def test_posix_relative_path_is_left_alone(self):
        self.assertEqual("scripts/rewrite_prompt.sh", build.entry_name("scripts/rewrite_prompt.sh"))


class PackageTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tmp.name
        make_plugin(self.root)
        module_root, module_dist = build.ROOT, build.DIST
        build.ROOT = self.root
        build.DIST = os.path.join(self.root, "dist")
        self.addCleanup(self._restore, module_root, module_dist)

    @staticmethod
    def _restore(module_root, module_dist):
        build.ROOT, build.DIST = module_root, module_dist

    def _names(self):
        zips = [f for f in os.listdir(build.DIST) if f.endswith(".zip")]
        self.assertEqual(1, len(zips), zips)
        with zipfile.ZipFile(os.path.join(build.DIST, zips[0])) as zf:
            return zf.namelist()

    def test_manifest_sits_at_the_zip_root(self):
        self.assertEqual(0, build.build())
        names = self._names()
        self.assertIn("manifest.json", names)
        self.assertIn("scripts/run.sh", names)

    def test_cache_and_finder_files_stay_out_of_the_package(self):
        self.assertEqual(0, build.build())
        names = self._names()
        left_overs = [n for n in names if "__pycache__" in n or n.endswith(".pyc") or n == ".DS_Store"]
        self.assertEqual([], left_overs)

    def test_windows_separators_never_reach_the_zip(self):
        # The installer rejects entries containing backslashes, and on Windows os.path.relpath returns backslashes.
        # Stub it here to return Windows-style values so the real packaging path runs end to end.
        real = build.os.path.relpath

        def windows_relpath(path, start):
            return real(path, start).replace("/", "\\")

        build.os.path.relpath = windows_relpath
        self.addCleanup(setattr, build.os.path, "relpath", real)
        self.assertEqual(0, build.build())
        names = self._names()
        self.assertEqual([], [n for n in names if "\\" in n])
        self.assertIn("scripts/run.sh", names)

    def test_every_entry_is_a_relative_slash_path(self):
        # checkName rejects absolute paths, backslashes, colons and `..` segments
        self.assertEqual(0, build.build())
        for name in self._names():
            self.assertNotIn("\\", name)
            self.assertNotIn(":", name)
            self.assertFalse(name.startswith("/"), name)
            self.assertNotIn("..", name.split("/"))


if __name__ == "__main__":
    unittest.main()
