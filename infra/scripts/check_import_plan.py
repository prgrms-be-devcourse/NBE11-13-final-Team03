"""Reject any plan other than exact approved imports or a complete no-change plan."""
import argparse
import json
from pathlib import Path

SNAPSHOT = Path(__file__).parents[1] / "inventory/network-2026-10-28.json"


def expected_targets():
    n = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    vpc = n["vpc"]["id"]
    public = n["public_route_table"]["id"]
    igw = n["internet_gateway"]["id"]
    targets = {}

    def add(address, import_id, **values):
        targets[address] = {"import_id": import_id, "values": {"region": n["region"], **values}}

    add("aws_vpc.main", vpc, id=vpc, cidr_block=n["vpc"]["cidr_block"],
        instance_tenancy=n["vpc"]["instance_tenancy"], tags=n["vpc"]["tags"], default_route_table_id=n["main_route_table"]["id"])
    add("aws_internet_gateway.main", igw, id=igw, vpc_id=vpc, tags=n["internet_gateway"]["tags"])
    add("aws_route_table.main", n["main_route_table"]["id"], id=n["main_route_table"]["id"],
        vpc_id=vpc, tags=n["main_route_table"]["tags"])
    add("aws_route_table.public", public, id=public, vpc_id=vpc, tags=n["public_route_table"]["tags"])
    add("aws_route.internet", public + "_0.0.0.0/0", route_table_id=public,
        destination_cidr_block="0.0.0.0/0", gateway_id=igw)
    for key, subnet in n["subnets"].items():
        add(f'aws_subnet.public["{key}"]', subnet["id"], id=subnet["id"], vpc_id=vpc,
            **{k: subnet[k] for k in ("cidr_block", "availability_zone", "map_public_ip_on_launch",
                                     "private_dns_hostname_type_on_launch", "tags")},
            assign_ipv6_address_on_creation=False, enable_dns64=False,
            enable_resource_name_dns_a_record_on_launch=False, enable_resource_name_dns_aaaa_record_on_launch=False)
        add(f'aws_route_table_association.public["{key}"]', subnet["id"] + "/" + public,
            id=subnet["association_id"], subnet_id=subnet["id"], route_table_id=public)
    for key, group in n["security_groups"].items():
        add(f'aws_security_group.service["{key}"]', group["id"], id=group["id"], vpc_id=vpc,
            name=group["name"], description=group["description"], tags=group["tags"])
    for direction in ("ingress", "egress"):
        for key, rule in n[direction + "_rules"].items():
            peer = rule.get("referenced_group")
            add(f'aws_vpc_security_group_{direction}_rule.existing["{key}"]', rule["id"],
                id=rule["id"], security_group_id=n["security_groups"][rule["group"]]["id"],
                ip_protocol=rule["ip_protocol"], from_port=rule.get("from_port"), to_port=rule.get("to_port"),
                cidr_ipv4=rule.get("cidr_ipv4"), cidr_ipv6=None, prefix_list_id=None,
                referenced_security_group_id=n["security_groups"][peer]["id"] if peer else None,
                description=rule.get("description"), tags=rule["tags"])
    return targets


def check(plan, phase, targets=None):
    if phase not in {"import", "after"}:
        raise ValueError("Unknown phase")
    if plan.get("errored") or plan.get("complete") is not True or plan.get("deferred_changes"):
        raise ValueError("Plan is errored, incomplete or deferred")
    if plan.get("resource_drift"):
        raise ValueError("Resource drift requires review")
    expected = expected_targets() if targets is None else targets
    if not expected or not isinstance(expected, dict):
        raise ValueError("Exact target manifest is required")
    changes = plan.get("resource_changes", [])
    addresses = [c.get("address") for c in changes]
    if len(addresses) != len(set(addresses)) or set(addresses) != set(expected):
        raise ValueError(f"Plan must contain exactly the {len(expected)} approved addresses")
    planned = plan.get("planned_values", {}).get("root_module", {}).get("resources", [])
    values = {r["address"]: r.get("values", {}) for r in planned}
    if len(planned) != len(values) or set(values) != set(expected):
        raise ValueError("Planned resources differ from the approved targets")
    prior = {r["address"]: r for r in plan.get("prior_state", {}).get("values", {}).get("root_module", {}).get("resources", [])}
    for resource in changes:
        address = resource["address"]
        change = resource["change"]
        if resource.get("previous_address"):
            raise ValueError(f"State address moves require review: {address}")
        if resource.get("mode") != "managed" or resource.get("provider_name") != "registry.terraform.io/hashicorp/aws":
            raise ValueError(f"Unexpected mode/provider: {address}")
        if change.get("actions") != ["no-op"]:
            raise ValueError(f"AWS create/update/delete/read is forbidden: {address}")
        importing = change.get("importing")
        if phase == "import":
            if importing and importing.get("id") != expected[address]["import_id"]:
                raise ValueError(f"Missing or incorrect import ID: {address}")
            if not importing and (address not in prior or prior[address].get("values", {}).get("id") != values[address].get("id")):
                raise ValueError(f"Missing or incorrect import ID: {address}")
        elif importing:
            raise ValueError(f"Import still pending: {address}")
        for field, value in expected[address]["values"].items():
            actual = values[address].get(field)
            if field == "tags":
                actual = actual or {}
            if actual != value:
                raise ValueError(f"Snapshot mismatch: {address}.{field}")
    return len(expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan_json", type=Path, help="Private terraform show -json output")
    parser.add_argument("--phase", choices=("import", "after"), required=True)
    parser.add_argument("--targets", type=Path, help="Private targets.json produced by prepare_import.py")
    args = parser.parse_args()
    try:
        targets = json.loads(args.targets.read_text(encoding="utf-8")) if args.targets else None
        count = check(json.loads(args.plan_json.read_text(encoding="utf-8-sig")), args.phase, targets)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.exit(1, f"STOP: {error}\n")
    print(f"PASS: {count} exact targets, phase={args.phase}, no AWS resource changes")


if __name__ == "__main__":
    main()
