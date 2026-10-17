"""Tests for coverage.py. Run: python3 -I -m unittest discover -s scripts/coverage -p 'test_*.py'"""

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # -I leaves the script directory off sys.path.

import coverage  # noqa: E402

REPORT_XML = """<?xml version="1.0" ?>
<report name="Kover">
<package name="a"><counter type="LINE" missed="1" covered="1"/></package>
<counter type="INSTRUCTION" missed="10" covered="30"/>
<counter type="BRANCH" missed="{bm}" covered="{bc}"/>
<counter type="LINE" missed="{lm}" covered="{lc}"/>
</report>
"""


def make_module(root, name, lc, lm, bc=0, bm=0, with_branch=True):
    module = Path(root) / name
    (module / coverage.REPORT.parent).mkdir(parents=True)
    (module / "build.gradle.kts").write_text("")
    text = REPORT_XML.format(lc=lc, lm=lm, bc=bc, bm=bm)
    if not with_branch:
        text = "\n".join(line for line in text.splitlines() if "BRANCH" not in line)
    (module / coverage.REPORT).write_text(text)


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = coverage.main(list(argv))
    return code, out.getvalue(), err.getvalue()


class ParseTest(unittest.TestCase):
    def test_report_level_counters_are_used_not_package_ones(self):
        with tempfile.TemporaryDirectory() as root:
            make_module(root, "core-a", lc=3, lm=1, bc=1, bm=1)
            self.assertEqual(coverage.collect(root), {"core-a": {"line": 75.0, "branch": 50.0}})

    def test_missing_counter_and_empty_module_are_none(self):
        with tempfile.TemporaryDirectory() as root:
            make_module(root, "core-a", lc=0, lm=0, with_branch=False)
            self.assertEqual(coverage.collect(root), {"core-a": {"line": None, "branch": None}})

    def test_directories_without_a_build_file_or_report_are_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            make_module(root, "core-a", lc=1, lm=0)
            (Path(root) / "core-b").mkdir()
            (Path(root) / "core-b" / "build.gradle.kts").write_text("")
            self.assertEqual(list(coverage.collect(root)), ["core-a"])


class ProblemsTest(unittest.TestCase):
    BASE = {"m": {"line": 80.0, "branch": 60.0}}

    def test_drop_within_tolerance_passes(self):
        self.assertEqual(coverage.problems({"m": {"line": 79.5, "branch": 60.0}}, self.BASE), [])

    def test_drop_beyond_tolerance_fails_per_metric(self):
        found = coverage.problems({"m": {"line": 79.49, "branch": 50.0}}, self.BASE)
        self.assertEqual(len(found), 2)
        self.assertIn("line coverage 79.49 %", found[0])
        self.assertIn("branch coverage 50.00 %", found[1])

    def test_new_module_without_baseline_fails(self):
        found = coverage.problems({"m": {"line": 90.0, "branch": 90.0}, "n": {"line": 1.0, "branch": 1.0}}, self.BASE)
        self.assertEqual(len(found), 1)
        self.assertTrue(found[0].startswith("n: no baseline"))

    def test_module_missing_its_report_fails(self):
        self.assertIn("no coverage report", coverage.problems({}, self.BASE)[0])

    def test_lost_branches_fail_but_no_baseline_metric_is_skipped(self):
        self.assertEqual(len(coverage.problems({"m": {"line": 80.0, "branch": None}}, self.BASE)), 1)
        self.assertEqual(coverage.problems({"m": {"line": 80.0, "branch": 5.0}}, {"m": {"line": 80.0, "branch": None}}), [])


class UpdateTest(unittest.TestCase):
    def test_values_only_rise_by_default(self):
        new = coverage.updated({"m": {"line": 70.0, "branch": 65.0}}, {"m": {"line": 80.0, "branch": 60.0}})
        self.assertEqual(new, {"m": {"line": 80.0, "branch": 65.0}})

    def test_allow_decrease_takes_current_values(self):
        new = coverage.updated({"m": {"line": 70.0, "branch": None}}, {"m": {"line": 80.0, "branch": 60.0}}, True)
        self.assertEqual(new, {"m": {"line": 70.0, "branch": None}})

    def test_modules_without_report_are_dropped(self):
        self.assertEqual(coverage.updated({}, {"gone": {"line": 1.0, "branch": 1.0}}), {})


class CommandTest(unittest.TestCase):
    def test_update_then_check_round_trip(self):
        with tempfile.TemporaryDirectory() as root:
            make_module(root, "core-a", lc=8, lm=2, bc=1, bm=1)
            self.assertEqual(run("update", "--root", root)[0], 0)
            data = json.loads((Path(root) / coverage.BASELINE).read_text())
            self.assertEqual(data["modules"], {"core-a": {"line": 80.0, "branch": 50.0}})
            self.assertEqual(run("check", "--root", root)[0], 0)
            make_module(root, "core-b", lc=1, lm=0)
            code, _, err = run("check", "--root", root)
            self.assertEqual(code, 1)
            self.assertIn("core-b: no baseline", err)

    def test_summary_lists_every_module_and_flags_drops(self):
        with tempfile.TemporaryDirectory() as root:
            make_module(root, "core-a", lc=1, lm=1)
            baseline = Path(root) / "b.json"
            baseline.write_text(json.dumps({"modules": {"core-a": {"line": 90.0, "branch": None}}}))
            code, out, _ = run("summary", "--root", root, "--baseline", str(baseline))
            self.assertEqual(code, 0)
            self.assertIn("| core-a | 50.0 % | n/a | 90.0 % | n/a | below baseline |", out)

    def test_no_reports_is_an_error(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(run("check", "--root", root)[0], 1)


if __name__ == "__main__":
    unittest.main()
