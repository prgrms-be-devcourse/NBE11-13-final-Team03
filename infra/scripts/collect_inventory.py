"""AWS CLI 2 read-only inventory for YON. Raw output stays outside Git."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess

ACCOUNT = "174170816230"
REGION = "ap-northeast-2"
VPC = "vpc-032bd9d88d5c97147"
BUCKET = "yon-frontend-team03-174170816230-ap-northeast-2-an"
INSTANCES = ("i-05f26052a70059c73", "i-0b48442522e0c3400", "i-08a92649f4f51f8a5", "i-0f45b533faf285beb")
GROUPS = ("sg-0fbaf2496d1edd1ed", "sg-042c6f59681a7b1bd", "sg-03c341b41a4087a48", "sg-04b53d2630b9a7ea9")
IMPORT_ROLES = ("team03-main-sqs", "team03-data-sqs", "team03-frontend-deploy")
ROLES = (*IMPORT_ROLES, "team03-ec2-ecr-read")
TEMPORARY_POLICIES = {"YONNetworkInventoryReadTemporary", "YONNetworkImportReadTemporary", "YONTerraformImportReadTemporary"}


class Collector:
    def __init__(self):
        self.results = {}

    def query(self, key, *args, absent_codes=(), empty_ok=False):
        if args[1].split("-")[0] not in {"get", "list", "describe"}:
            raise ValueError("Only read-only AWS operations are permitted")
        command = ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"]
        try:
            result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                    errors="replace", timeout=120, env={**os.environ, "AWS_PAGER": ""})
            if result.returncode:
                match = re.search(r"An error occurred \(([A-Za-z0-9_.-]+)\)", result.stderr)
                code = match[1] if match else "CLI_ERROR"
                self.results[key] = {"status": "absent" if code in absent_codes else "error", "error_code": code,
                                     "exit_code": result.returncode}
                return {}
            # GetBucketVersioning on a bucket that was never versioned succeeds with an empty body, so the CLI prints nothing.
            data = {} if empty_ok and not result.stdout.strip() else json.loads(result.stdout)
            if not isinstance(data, dict):
                raise ValueError("Expected one JSON object")
        except (OSError, subprocess.TimeoutExpired, ValueError) as error:
            # Raw messages can contain credentials, URLs or policy contents.
            self.results[key] = {"status": "error", "error_code": type(error).__name__}
            return {}
        self.results[key] = {"status": "ok", "data": data}
        return data

    def collect(self, output, scope="all"):
        if scope not in {"all", "network", "import"}:
            raise ValueError("Unknown inventory scope")
        identity = self.query("identity", "sts", "get-caller-identity")
        if identity.get("Account") != ACCOUNT:
            raise ValueError("AWS account verification failed; no inventory directory was created")
        output.mkdir(mode=0o700, parents=True, exist_ok=False)
        manifest = {"schema_version": 1, "account_id": ACCOUNT, "region": REGION,
                    "started_at": datetime.now(timezone.utc).isoformat(), "scope": scope, "complete": False,
                    "results": self.results}
        try:
            if scope == "network":
                self.collect_network()
            else:
                self.collect_resources(output, import_only=scope == "import")
            manifest["complete"] = all(item["status"] in {"ok", "absent"} for item in self.results.values())
        finally:
            manifest["finished_at"] = datetime.now(timezone.utc).isoformat()
            path = output / "inventory.json"
            path.touch(mode=0o600)
            path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return manifest["complete"]

    def collect_network(self):
        q = self.query
        q("vpc", "ec2", "describe-vpcs", "--vpc-ids", VPC)
        q("subnets", "ec2", "describe-subnets", "--filters", f"Name=vpc-id,Values={VPC}")
        q("instances", "ec2", "describe-instances", "--instance-ids", *INSTANCES)
        q("volumes", "ec2", "describe-volumes", "--filters", "Name=attachment.instance-id,Values=" + ",".join(INSTANCES))
        q("security-groups", "ec2", "describe-security-groups", "--group-ids", *GROUPS)
        q("sg-rules", "ec2", "describe-security-group-rules", "--filters", "Name=group-id,Values=" + ",".join(GROUPS))
        q("route-tables", "ec2", "describe-route-tables", "--filters", f"Name=vpc-id,Values={VPC}")
        q("igws", "ec2", "describe-internet-gateways", "--filters", f"Name=attachment.vpc-id,Values={VPC}")
        q("eips", "ec2", "describe-addresses", "--filters", "Name=instance-id,Values=" + ",".join(INSTANCES))
        q("cf-prefix-list", "ec2", "describe-managed-prefix-lists", "--filters",
          "Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing")
        q("ebs-default-encryption", "ec2", "get-ebs-encryption-by-default")
        for instance in INSTANCES:
            q(f"termination/{instance}", "ec2", "describe-instance-attribute", "--instance-id", instance,
              "--attribute", "disableApiTermination")

    def collect_resources(self, output, import_only=False):
        self.collect_network()
        q = self.query
        for role in IMPORT_ROLES if import_only else ROLES:
            q(f"roles/{role}", "iam", "get-role", "--role-name", role)
            profiles = q(f"profiles/{role}", "iam", "list-instance-profiles-for-role", "--role-name", role)
            attached = q(f"attached/{role}", "iam", "list-attached-role-policies", "--role-name", role)
            for policy in attached.get("AttachedPolicies", []):
                arn = policy["PolicyArn"]
                if arn.rsplit("/", 1)[-1] in TEMPORARY_POLICIES:
                    continue
                if f"policy/{arn}" in self.results:
                    continue
                version = q(f"policy/{arn}", "iam", "get-policy", "--policy-arn", arn).get("Policy", {}).get("DefaultVersionId")
                if version:
                    q(f"policy-version/{arn}", "iam", "get-policy-version", "--policy-arn", arn, "--version-id", version)
            inline = q(f"inline/{role}", "iam", "list-role-policies", "--role-name", role)
            for name in inline.get("PolicyNames", []):
                if name in TEMPORARY_POLICIES:
                    continue
                q(f"inline/{role}/{name}", "iam", "get-role-policy", "--role-name", role, "--policy-name", name)
            if import_only:
                for profile in profiles.get("InstanceProfiles", []):
                    name = profile["InstanceProfileName"]
                    q(f"profile/{name}", "iam", "get-instance-profile", "--instance-profile-name", name)
        q("oidc", "iam", "get-open-id-connect-provider", "--open-id-connect-provider-arn",
          f"arn:aws:iam::{ACCOUNT}:oidc-provider/token.actions.githubusercontent.com")
        for operation in ("get-bucket-policy", "get-public-access-block", "get-bucket-versioning",
                          "get-bucket-encryption", "get-bucket-ownership-controls"):
            absent = {"get-bucket-policy": ("NoSuchBucketPolicy",), "get-public-access-block": ("NoSuchPublicAccessBlockConfiguration",),
                      "get-bucket-ownership-controls": ("OwnershipControlsNotFoundError",),
                      "get-bucket-encryption": ("ServerSideEncryptionConfigurationNotFoundError",)}
            q(f"s3/{operation}", "s3api", operation, "--bucket", BUCKET, absent_codes=absent.get(operation, ()),
              empty_ok=operation == "get-bucket-versioning")
        if import_only:
            q("key-pair", "ec2", "describe-key-pairs", "--key-names", "team03_key", "--include-public-key")
            q("s3/tags", "s3api", "get-bucket-tagging", "--bucket", BUCKET, absent_codes=("NoSuchTagSet",))
        distributions = q("cf-distributions", "cloudfront", "list-distributions")
        targets = [d for d in distributions.get("DistributionList", {}).get("Items", [])
                   if any(o.get("Id") == "yon-backend" for o in d.get("Origins", {}).get("Items", []))]
        if not targets and self.results["cf-distributions"]["status"] == "ok":
            self.results["cf-target"] = {"status": "error", "error_code": "TARGET_NOT_FOUND"}
        functions = set()
        for distribution in targets:
            key = "cloudfront/" + distribution["Id"]
            config = q(key, "cloudfront", "get-distribution", "--id", distribution["Id"]).get("Distribution", {}).get("DistributionConfig", {})
            if import_only:
                q(key + "/tags", "cloudfront", "list-tags-for-resource", "--resource", distribution["ARN"])
            behaviors = [config.get("DefaultCacheBehavior", {}), *config.get("CacheBehaviors", {}).get("Items", [])]
            for behavior in behaviors:
                for function in behavior.get("FunctionAssociations", {}).get("Items", []):
                    functions.add(function["FunctionARN"].rsplit("/", 1)[-1])
        q("cf-oac", "cloudfront", "get-origin-access-control", "--id", "E2JJZ8SZNVRWDC")
        for index, name in enumerate(sorted(functions)):
            q(f"cf-function/{name}/config", "cloudfront", "describe-function", "--name", name, "--stage", "LIVE")
            path = output / f"cf-function-{index}.js"
            path.touch(mode=0o600, exist_ok=False)
            path.touch(mode=0o600)
            q(f"cf-function/{name}/code", "cloudfront", "get-function", "--name", name, "--stage", "LIVE", str(path.resolve()))
            if import_only:
                (output / f"cf-function-{index}-development.js").touch(mode=0o600, exist_ok=False)
                q(f"cf-function/{name}/development", "cloudfront", "describe-function", "--name", name, "--stage", "DEVELOPMENT")
                q(f"cf-function/{name}/development-code", "cloudfront", "get-function", "--name", name, "--stage", "DEVELOPMENT", str((output / f"cf-function-{index}-development.js").resolve()))
                q(f"cf-function/{name}/tags", "cloudfront", "list-tags-for-resource", "--resource", f"arn:aws:cloudfront::{ACCOUNT}:function/{name}")
        if import_only:
            return
        for queue in ("team03-user-notifications", "team03-user-notifications-dlq", "team03-admin-collection", "team03-admin-collection-dlq"):
            q(f"sqs/{queue}", "sqs", "get-queue-attributes", "--queue-url",
              f"https://sqs.{REGION}.amazonaws.com/{ACCOUNT}/{queue}", "--attribute-names", "All")
        for stack in ("team03-sqs-user", "team03-sqs-admin"):
            q(f"cfn/{stack}", "cloudformation", "describe-stacks", "--stack-name", stack)
            q(f"cfn/{stack}/resources", "cloudformation", "list-stack-resources", "--stack-name", stack)
        schedules = q("schedules", "scheduler", "list-schedules")
        for schedule in schedules.get("Schedules", []):
            q(f"schedules/{schedule['GroupName']}/{schedule['Name']}", "scheduler", "get-schedule",
              "--name", schedule["Name"], "--group-name", schedule["GroupName"])
        buses = q("event-buses", "events", "list-event-buses")
        for bus in buses.get("EventBuses", []):
            rules = q(f"rules/{bus['Name']}", "events", "list-rules", "--event-bus-name", bus["Name"])
            for rule in rules.get("Rules", []):
                q(f"targets/{bus['Name']}/{rule['Name']}", "events", "list-targets-by-rule",
                  "--rule", rule["Name"], "--event-bus-name", bus["Name"])
        q("alarms", "cloudwatch", "describe-alarms")
        q("budgets", "budgets", "describe-budgets", "--account-id", ACCOUNT)
        q("route53", "route53", "list-hosted-zones")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New private directory; never commit raw output")
    parser.add_argument("--scope", choices=("all", "network", "import"), default="all",
                        help="import collects all adopted resources; network is EC2-only; all also audits deferred resources")
    args = parser.parse_args()
    try:
        complete = Collector().collect(args.output, args.scope)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{type(error).__name__}: inventory could not start; check account and new output directory\n")
    print(f"Inventory saved; complete={complete}. Review inventory.json errors before sharing.")
    return 0 if complete else 1


if __name__ == "__main__":
    raise SystemExit(main())
