"""Fault checks for the CI gate, without Docker or application test duplication."""
from pathlib import Path
import tempfile
import unittest

from test_report import junit_report


class ExecutionGateTest(unittest.TestCase):
    def check_report(self, xml, **overrides):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temporary:
            path = Path(temporary) / "results.xml"
            path.write_text(xml, encoding="utf-8")
            return junit_report([path], overrides.get("inventory", {"Critical": "postgres"}),
                                overrides.get("manual", {}), overrides.get("required", {}), overrides.get("browser", ()))

    def test_missing_report_is_a_failure(self):
        self.assertTrue(junit_report([], {"Critical": "postgres"}, {}, {})[2])

    def test_malformed_report_cannot_pass(self):
        self.assertIn("Unreadable", self.check_report("<testsuite>")[2][0])

    def test_missing_required_class_and_method_are_detected(self):
        _, _, problems = self.check_report('<testsuite><testcase classname="Other" name="ok()"/></testsuite>', required={"Critical": ["rollback"]})
        self.assertEqual(2, len(problems))

    def test_unexpected_skip_is_not_a_pass(self):
        groups, skipped, problems = self.check_report('<testsuite><testcase classname="Critical" name="docker()"><skipped message="no engine"/></testcase></testsuite>')
        self.assertEqual((0, 1, "no engine"), (groups["postgres"]["passed"], groups["postgres"]["skipped"], skipped[0]["reason"]))
        self.assertTrue(problems)

    def test_explicit_manual_opt_in_is_reported_separately(self):
        groups, _, problems = self.check_report('<testsuite><testcase classname="Bench" name="load()"><skipped/></testcase></testsuite>', inventory={"Bench": "manual"}, manual={"Bench": "DB opt-in missing"})
        self.assertEqual((1, 0, []), (groups["manual"]["skipped"], groups["manual"]["passed"], problems))

    def test_nested_node_suites_are_counted_once_and_browser_is_required(self):
        groups, _, problems = self.check_report('<testsuites><testsuite><testsuite><testcase name="Chrome flow"/></testsuite><testcase name="format"/></testsuite></testsuites>', inventory={}, browser=["Chrome flow"])
        self.assertEqual((1, 1, []), (groups["browser"]["passed"], groups["fast"]["passed"], problems))
        self.assertTrue(self.check_report('<testsuite><testcase name="format"/></testsuite>', inventory={}, browser=["Chrome flow"])[2])

    def test_failed_or_skipped_required_method_is_rejected(self):
        for outcome in ('<failure message="wrong rows"/>', '<error/>', '<skipped/>'):
            with self.subTest(outcome=outcome):
                self.assertTrue(self.check_report(f'<testsuite><testcase classname="Critical" name="rollback()">{outcome}</testcase></testsuite>', required={"Critical": ["rollback"]})[2])


if __name__ == "__main__":
    unittest.main()
