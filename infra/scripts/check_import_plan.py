"""Allow exact imports or declared address moves, with no AWS resource changes."""
import argparse
import json
from pathlib import Path

SNAPSHOT = Path(__file__).parents[1] / "inventory/network-2026-10-28.json"
ADDRESS_MOVES = Path(__file__).parents[1] / "inventory/address-moves.json"


def remap_targets(targets):
    moves = json.loads(ADDRESS_MOVES.read_text(encoding="utf-8"))
    return {moves.get(address, address): dict(target) for address, target in targets.items()}


def resources_in(module):
    """Terraform JSON nests child-module resources at every module boundary."""
    yield from module.get("resources", [])
    for child in module.get("child_modules", []):
        yield from resources_in(child)


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
                cidr_ipv4=rule.get("cidr_ipv4"), cidr_ipv6=None, prefix_list_id=rule.get("prefix_list_id"),
                referenced_security_group_id=n["security_groups"][peer]["id"] if peer else None,
                description=rule.get("description"), tags=rule["tags"])
    return remap_targets(targets)


MAIN_HTTP_SOURCE = 'aws_vpc_security_group_ingress_rule.existing["main_http"]'
MAIN_HTTP_TARGET = 'module.main.aws_vpc_security_group_ingress_rule.this["main_http"]'
MAIN_HTTP_OLD_ID = "sgr-05f5a7aabab29ed7d"
MAIN_HTTP_NEW_ID = "sgr-01e8cd7a85445657c"
MAIN_HTTP_PREFIX = "pl-22a6434b"


def require_cloudfront_target(targets):
    target = targets.get(MAIN_HTTP_TARGET, {})
    values = target.get("values", {})
    if (target.get("import_id") != MAIN_HTTP_NEW_ID or values.get("id") != MAIN_HTTP_NEW_ID
            or values.get("prefix_list_id") != MAIN_HTTP_PREFIX or values.get("cidr_ipv4") is not None
            or values.get("referenced_security_group_id") is not None
            or values.get("ip_protocol") != "tcp" or values.get("from_port") != 8080 or values.get("to_port") != 8080):
        raise ValueError("Rebinding requires the reviewed CloudFront-only main HTTP snapshot")


# Same names as collect_inventory.TEMPORARY_POLICIES (kept here so this checker stays standalone).
TEMPORARY_POLICIES = frozenset({"YONNetworkInventoryReadTemporary", "YONNetworkImportReadTemporary",
                                "YONTerraformImportReadTemporary"})
# Read-only attributes that change without any configuration change:
# - EC2 without an EIP gets a new public IP/DNS on every stop/start (servers stop 18:00, start 09:00).
# - aws_security_group.ingress/egress mirror the separately managed rule resources, which are checked exactly.
BENIGN_DRIFT = {"aws_instance": frozenset({"public_ip", "public_dns"}),
                "aws_security_group": frozenset({"ingress", "egress"})}


def check_drift(drift):
    """Accept only known read-only drift; anything else (including deletion) stops for review."""
    accepted = 0
    for resource in drift:
        address = resource.get("address")
        change = resource.get("change") or {}
        before, after = change.get("before"), change.get("after")
        if change.get("actions") != ["update"] or not isinstance(before, dict) or not isinstance(after, dict):
            raise ValueError(f"Resource drift requires review: {address}")
        changed = {key for key in set(before) | set(after) if before.get(key) != after.get(key)}
        kind = resource.get("type")
        if changed and changed <= BENIGN_DRIFT.get(kind, frozenset()):
            accepted += 1
            continue
        if kind == "aws_iam_role" and changed == {"inline_policy"}:
            names = lambda value: {item.get("name") for item in value or []}
            if names(before["inline_policy"]) ^ names(after["inline_policy"]) <= TEMPORARY_POLICIES:
                accepted += 1
                continue
        raise ValueError(f"Resource drift requires review: {address} {sorted(changed)}")
    return accepted


def check(plan, phase, targets=None, *, rebind_main_http=False):
    if phase not in {"import", "after"}:
        raise ValueError("Unknown phase")
    if plan.get("errored") or plan.get("complete") is not True or plan.get("deferred_changes"):
        raise ValueError("Plan is errored, incomplete or deferred")
    check_drift(plan.get("resource_drift") or [])
    expected = expected_targets() if targets is None else targets
    if not expected or not isinstance(expected, dict):
        raise ValueError("Exact target manifest is required")
    changes = plan.get("resource_changes", [])
    addresses = [c.get("address") for c in changes]
    if len(addresses) != len(set(addresses)) or set(addresses) != set(expected):
        raise ValueError(f"Plan must contain exactly the {len(expected)} approved addresses")
    planned = list(resources_in(plan.get("planned_values", {}).get("root_module", {})))
    values = {r["address"]: r.get("values", {}) for r in planned}
    if len(planned) != len(values) or set(values) != set(expected):
        raise ValueError("Planned resources differ from the approved targets")
    prior_resources = list(resources_in(plan.get("prior_state", {}).get("values", {}).get("root_module", {})))
    prior = {r["address"]: r for r in prior_resources}
    if len(prior_resources) != len(prior):
        raise ValueError("Duplicate prior-state addresses")
    moves = json.loads(ADDRESS_MOVES.read_text(encoding="utf-8"))
    imports = [r for r in changes if r["change"].get("importing")]
    if rebind_main_http:
        require_cloudfront_target(expected)
        if MAIN_HTTP_SOURCE in prior:
            raise ValueError("The stale main HTTP binding must be forgotten before re-import")
        if (phase != "import" or len(imports) != 1 or imports[0]["address"] != MAIN_HTTP_TARGET
                or imports[0].get("previous_address")
                or imports[0]["change"]["importing"].get("id") != MAIN_HTTP_NEW_ID):
            raise ValueError("Only the approved main HTTP re-import may accompany address moves")
    elif any(r.get("previous_address") for r in changes) and imports:
        raise ValueError("Do not mix state address moves and new imports")
    for resource in changes:
        address = resource["address"]
        change = resource["change"]
        previous = resource.get("previous_address")
        if previous and (phase == "after" or moves.get(previous) != address):
            raise ValueError(f"State address moves require review: {address}")
        if resource.get("mode") != "managed" or resource.get("provider_name") != "registry.terraform.io/hashicorp/aws":
            raise ValueError(f"Unexpected mode/provider: {address}")
        if change.get("actions") != ["no-op"]:
            raise ValueError(f"AWS create/update/delete/read is forbidden: {address}")
        importing = change.get("importing")
        if phase == "import":
            if importing and importing.get("id") != expected[address]["import_id"]:
                raise ValueError(f"Missing or incorrect import ID: {address}")
            # Terraform versions may expose the prior object before or after moving.
            previous_state = prior.get(address)
            if previous and not previous_state:
                previous_state = prior.get(previous)
            if previous and values[address].get("id") is None:
                raise ValueError(f"Missing prior object ID for state move: {address}")
            if not importing and (not previous_state or previous_state.get("values", {}).get("id") != values[address].get("id")):
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
    parser.add_argument("--rebind-main-http", action="store_true", help="Allow only the approved main HTTP re-import with moves")
    parser.add_argument("--targets", type=Path, help="Private targets.json produced by prepare_import.py")
    args = parser.parse_args()
    try:
        targets = json.loads(args.targets.read_text(encoding="utf-8")) if args.targets else None
        count = check(json.loads(args.plan_json.read_text(encoding="utf-8-sig")), args.phase, targets, rebind_main_http=args.rebind_main_http)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.exit(1, f"STOP: {error}\n")
    print(f"PASS: {count} exact targets, phase={args.phase}, no AWS resource changes")


if __name__ == "__main__":
    main()
