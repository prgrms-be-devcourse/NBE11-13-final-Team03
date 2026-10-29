import copy
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("guard", Path(__file__).parents[1] / "scripts/check_import_plan.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def valid_plan(phase="import"):
    changes, resources = [], []
    for address, target in guard.expected_targets().items():
        change = {"actions": ["no-op"]}
        if phase == "import":
            change["importing"] = {"id": target["import_id"]}
        changes.append({"address": address, "mode": "managed",
                        "provider_name": "registry.terraform.io/hashicorp/aws", "change": change})
        resources.append({"address": address, "values": copy.deepcopy(target["values"])})
    return {"complete": True, "errored": False, "resource_changes": changes,
            "planned_values": {"root_module": {"resources": resources}}}


class ImportPlanTests(unittest.TestCase):
    def test_accepts_exact_import_and_after_import_no_change(self):
        for phase in ("import", "after"):
            self.assertEqual(guard.check(valid_plan(phase), phase), 27)

    def test_rejects_all_aws_actions_including_replacement_and_read(self):
        for actions in (["create"], ["update"], ["delete"], ["delete", "create"], ["read"]):
            with self.subTest(actions=actions):
                plan = valid_plan()
                plan["resource_changes"][0]["change"]["actions"] = actions
                with self.assertRaisesRegex(ValueError, "forbidden"):
                    guard.check(plan, "import")

    def test_rejects_errored_partial_deferred_and_drifted_plan(self):
        for field, value in (("errored", True), ("complete", False), ("complete", None),
                             ("deferred_changes", [{"reason": "unknown"}]),
                             ("resource_drift", [{"address": "aws_vpc.main"}])):
            with self.subTest(field=field, value=value):
                plan = valid_plan()
                plan[field] = value
                with self.assertRaises(ValueError):
                    guard.check(plan, "import")

    def test_rejects_missing_extra_and_duplicate_targets(self):
        for field in ("changes", "values"):
            for mutation in ("missing", "extra", "duplicate"):
                with self.subTest(field=field, mutation=mutation):
                    plan = valid_plan()
                    entries = (plan["resource_changes"] if field == "changes" else
                               plan["planned_values"]["root_module"]["resources"])
                    if mutation == "missing":
                        entries.pop()
                    else:
                        extra = copy.deepcopy(entries[0])
                        if mutation == "extra":
                            extra["address"] = "aws_instance.unexpected"
                        entries.append(extra)
                    with self.assertRaises(ValueError):
                        guard.check(plan, "import")

    def test_rejects_wrong_or_missing_import_id_and_remaining_import(self):
        for importing in (None, {"id": "vpc-wrong"}):
            plan = valid_plan()
            plan["resource_changes"][0]["change"]["importing"] = importing
            with self.assertRaisesRegex(ValueError, "import ID"):
                guard.check(plan, "import")
        with self.assertRaisesRegex(ValueError, "Import still pending"):
            guard.check(valid_plan(), "after")

    def test_rejects_changed_snapshot_values_even_with_no_op_actions(self):
        for field, value in (("id", "vpc-wrong"), ("region", "us-east-1"),
                             ("cidr_block", "10.1.0.0/16"), ("tags", {"Name": "changed"})):
            with self.subTest(field=field):
                plan = valid_plan()
                plan["planned_values"]["root_module"]["resources"][0]["values"][field] = value
                with self.assertRaisesRegex(ValueError, "Snapshot mismatch"):
                    guard.check(plan, "import")

    def test_rejects_data_sources_other_providers_and_address_moves(self):
        for field, value in (("mode", "data"), ("provider_name", "example/other"),
                             ("previous_address", "aws_vpc.old")):
            plan = valid_plan()
            plan["resource_changes"][0][field] = value
            with self.assertRaises(ValueError):
                guard.check(plan, "import")

    def test_special_import_ids_and_unrestricted_egress(self):
        targets = guard.expected_targets()
        self.assertEqual(targets["aws_route_table.main"]["import_id"], "rtb-0b7d3d03690c74791")
        self.assertEqual(targets["aws_route.internet"]["import_id"], "rtb-0e5380dd325267c16_0.0.0.0/0")
        self.assertEqual(targets['aws_route_table_association.public["public_a"]']["import_id"],
                         "subnet-084d01b4bc4420dc4/rtb-0e5380dd325267c16")
        egress = [t for a, t in targets.items() if a.startswith("aws_vpc_security_group_egress_rule.")]
        self.assertEqual(len(egress), 4)
        self.assertTrue(all(t["values"]["from_port"] is None and t["values"]["to_port"] is None for t in egress))

    def test_supplemental_policy_has_only_three_regional_read_actions(self):
        path = Path(__file__).parents[1] / "iam/network-import-read.json"
        policy = json.loads(path.read_text(encoding="utf-8"))
        actions = set()
        for statement in policy["Statement"]:
            self.assertEqual(statement["Effect"], "Allow")
            self.assertEqual(statement["Condition"]["StringEquals"]["aws:RequestedRegion"], "ap-northeast-2")
            actions.update(statement["Action"])
        self.assertEqual(actions, {"ec2:DescribeNetworkAcls", "ec2:DescribeTags", "ec2:DescribeVpcAttribute"})
        self.assertEqual(policy["Statement"][1]["Resource"],
                         "arn:aws:ec2:ap-northeast-2:174170816230:vpc/vpc-032bd9d88d5c97147")


if __name__ == "__main__":
    unittest.main()
