import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("inventory", Path(__file__).parents[1] / "scripts/collect_inventory.py")
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class InventoryTests(unittest.TestCase):
    def response(self, args, **kwargs):
        self.assertNotIn("--no-paginate", args)
        self.assertEqual(kwargs["env"]["AWS_PAGER"], "")
        self.assertIn(args[2].split("-")[0], {"get", "list", "describe"})
        service, operation = args[1:3]
        data = {}
        if service == "sts":
            data = {"Account": inventory.ACCOUNT}
        elif operation == "list-attached-role-policies":
            data = {"AttachedPolicies": [{"PolicyArn": "arn:aws:iam::aws:policy/ReadOnlyAccess"}]}
        elif operation == "get-policy":
            data = {"Policy": {"DefaultVersionId": "v4"}}
        elif operation == "get-policy-version":
            data = {"PolicyVersion": {"Document": {"Statement": []}}}
        elif operation == "list-role-policies":
            data = {"PolicyNames": ["inline"]}
        elif operation == "list-distributions":
            data = {"DistributionList": {"Items": [{"Id": "YON", "Origins": {"Items": [{"Id": "yon-backend"}]}}]}}
        elif operation == "get-distribution":
            data = {"Distribution": {"DistributionConfig": {"DefaultCacheBehavior": {"FunctionAssociations": {
                "Items": [{"FunctionARN": "arn:aws:cloudfront::174170816230:function/yon-spa-rewrite"}]}}}}}
        elif operation == "get-function":
            Path(args[args.index("LIVE") + 1]).write_text("function handler(event) {return event.request;}")
            data = {"ContentType": "application/javascript"}
        elif operation == "list-schedules":
            data = {"Schedules": [{"Name": "start-ec2", "GroupName": "school"}]}
        elif operation == "get-schedule":
            data = {"ScheduleExpression": "cron(0 9 * * ? *)", "ScheduleExpressionTimezone": "Asia/Seoul"}
        elif operation == "list-event-buses":
            data = {"EventBuses": [{"Name": "default"}, {"Name": "custom"}]}
        elif operation == "list-rules":
            data = {"Rules": [{"Name": "stop-ec2"}]}
        return subprocess.CompletedProcess(args, 0, json.dumps(data), "")

    def test_collects_policy_versions_functions_and_schedule_details_in_one_json(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", side_effect=self.response) as run:
            output = Path(directory) / "new"
            self.assertTrue(inventory.Collector().collect(output))
            manifest = json.loads((output / "inventory.json").read_text(encoding="utf-8"))
            self.assertTrue(manifest["complete"])
            results = manifest["results"]
            self.assertIn("policy-version/arn:aws:iam::aws:policy/ReadOnlyAccess", results)
            self.assertIn("inline/team03-data-sqs/inline", results)
            self.assertIn("cloudfront/YON", results)
            self.assertIn("targets/custom/stop-ec2", results)
            self.assertEqual(results["schedules/school/start-ec2"]["data"]["ScheduleExpressionTimezone"], "Asia/Seoul")
            self.assertTrue((output / "cf-function-0.js").is_file())
            policy_calls = [c for c in run.call_args_list if c.args[0][2] == "get-policy"]
            self.assertEqual(len(policy_calls), 1)

    def test_wrong_account_stops_before_resource_queries_or_output_creation(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", return_value=
                subprocess.CompletedProcess([], 0, '{"Account":"000000000000"}', "")) as run:
            output = Path(directory) / "new"
            with self.assertRaisesRegex(ValueError, "account verification failed"):
                inventory.Collector().collect(output)
            self.assertFalse(output.exists())
            self.assertEqual(run.call_count, 1)

    def test_refuses_to_overwrite_an_existing_inventory(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", side_effect=self.response) as run:
            output = Path(directory)
            (output / "inventory.json").write_text("previous")
            with self.assertRaises(FileExistsError):
                inventory.Collector().collect(output)
            self.assertEqual((output / "inventory.json").read_text(), "previous")
            self.assertEqual(run.call_count, 1)

    def test_denied_lookup_is_not_treated_as_absence_and_later_queries_continue(self):
        def response(args, **kwargs):
            if args[2] == "list-hosted-zones":
                return subprocess.CompletedProcess(args, 254, "", "An error occurred (AccessDenied) private-secret")
            return self.response(args, **kwargs)
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", side_effect=response):
            output = Path(directory) / "new"
            self.assertFalse(inventory.Collector().collect(output))
            text = (output / "inventory.json").read_text(encoding="utf-8")
            result = json.loads(text)
            self.assertEqual(result["results"]["route53"]["error_code"], "AccessDenied")
            self.assertEqual(result["results"]["sqs/team03-admin-collection"]["status"], "ok")
            self.assertNotIn("private-secret", text)

    def test_failed_early_lookup_does_not_stop_remaining_services(self):
        def response(args, **kwargs):
            if args[2] == "describe-route-tables":
                raise subprocess.TimeoutExpired(args, 120, output="private-secret")
            return self.response(args, **kwargs)
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", side_effect=response):
            output = Path(directory) / "new"
            self.assertFalse(inventory.Collector().collect(output))
            results = json.loads((output / "inventory.json").read_text(encoding="utf-8"))["results"]
            self.assertEqual(results["route-tables"]["error_code"], "TimeoutExpired")
            self.assertEqual(results["budgets"]["status"], "ok")

    def test_malformed_json_is_recorded_as_failure(self):
        collector = inventory.Collector()
        with patch.object(inventory.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "{}{}", "")):
            self.assertEqual(collector.query("broken", "ec2", "describe-instances"), {})
        self.assertEqual(collector.results["broken"]["status"], "error")

    def test_missing_target_distribution_is_not_a_complete_inventory(self):
        def response(args, **kwargs):
            if args[2] == "list-distributions":
                return subprocess.CompletedProcess(args, 0, '{"DistributionList": {"Quantity": 0}}', "")
            return self.response(args, **kwargs)
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", side_effect=response):
            output = Path(directory) / "new"
            self.assertFalse(inventory.Collector().collect(output))
            data = json.loads((output / "inventory.json").read_text(encoding="utf-8"))
            self.assertEqual(data["results"]["cf-target"]["error_code"], "TARGET_NOT_FOUND")

    def test_write_operation_is_rejected_without_running_aws(self):
        with patch.object(inventory.subprocess, "run") as run:
            with self.assertRaises(ValueError):
                inventory.Collector().query("unsafe", "ec2", "terminate-instances")
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
