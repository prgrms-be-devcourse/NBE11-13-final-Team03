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
    def test_only_explicit_absence_codes_are_accepted(self):
        for error_code, expected in (("NoSuchBucketPolicy", "absent"), ("AccessDenied", "error")):
            result = subprocess.CompletedProcess([], 254, "", f"An error occurred ({error_code}) when calling the GetBucketPolicy operation")
            collector = inventory.Collector()
            with patch.object(inventory.subprocess, "run", return_value=result):
                collector.query("policy", "s3api", "get-bucket-policy", absent_codes=("NoSuchBucketPolicy",))
            self.assertEqual(collector.results["policy"]["status"], expected)

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

    def test_network_scope_uses_only_actions_in_the_regional_read_policy(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", side_effect=self.response) as run:
            output = Path(directory) / "new"
            self.assertTrue(inventory.Collector().collect(output, "network"))
            manifest = json.loads((output / "inventory.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["scope"], "network")
            self.assertTrue(manifest["complete"])
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual({args[1] for args in commands}, {"sts", "ec2"})
            actions = {"ec2:" + "".join(part.title() for part in args[2].split("-"))
                       for args in commands if args[1] == "ec2"}
            policy = json.loads((Path(__file__).parents[1] / "iam/network-inventory-read.json").read_text())
            self.assertEqual({a for s in policy["Statement"] for a in s["Action"]}, actions)
            for statement in policy["Statement"]:
                self.assertEqual(statement["Effect"], "Allow")
                self.assertEqual(statement["Condition"], {"StringEquals": {"aws:RequestedRegion": inventory.REGION}})
            self.assertEqual(policy["Statement"][0]["Resource"], "*")
            attributes = policy["Statement"][1]
            self.assertEqual(attributes["Action"], ["ec2:DescribeInstanceAttribute"])
            self.assertEqual(set(attributes["Resource"]), {
                f"arn:aws:ec2:{inventory.REGION}:{inventory.ACCOUNT}:instance/{i}" for i in inventory.INSTANCES})
            self.assertFalse(list(output.glob("*.js")))

    def test_network_denial_is_recorded_without_querying_other_services(self):
        def response(args, **kwargs):
            if args[2] == "describe-vpcs":
                return subprocess.CompletedProcess(args, 254, "", "An error occurred (UnauthorizedOperation) not authorized")
            return self.response(args, **kwargs)
        with tempfile.TemporaryDirectory() as directory, patch.object(inventory.subprocess, "run", side_effect=response) as run:
            output = Path(directory) / "new"
            self.assertFalse(inventory.Collector().collect(output, "network"))
            manifest = json.loads((output / "inventory.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["results"]["vpc"]["error_code"], "UnauthorizedOperation")
            self.assertIn("termination/" + inventory.INSTANCES[-1], manifest["results"])
            self.assertEqual({call.args[0][1] for call in run.call_args_list}, {"sts", "ec2"})

    def test_invalid_scope_stops_before_any_aws_call(self):
        with patch.object(inventory.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "scope"):
                inventory.Collector().collect(Path("unused"), "unknown")
            run.assert_not_called()

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
