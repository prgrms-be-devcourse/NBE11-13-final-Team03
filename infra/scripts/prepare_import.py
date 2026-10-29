"""Build private Terraform inputs and exact import targets from a complete inventory."""
import argparse
import hashlib
import json
from pathlib import Path

from check_import_plan import expected_targets, remap_targets
from collect_inventory import ACCOUNT, REGION, VPC, BUCKET, INSTANCES, IMPORT_ROLES, TEMPORARY_POLICIES


def tags(items):
    return {x["Key"]: x["Value"] for x in items if not x["Key"].startswith("aws:")}


def document(value):
    if isinstance(value, str):
        value = json.loads(value)
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON policy object")
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def behavior(value):
    if not value.get("CachePolicyId") or value.get("ForwardedValues"):
        raise ValueError("Legacy CloudFront forwarded_values requires review")
    result = {
        "target_origin_id": value["TargetOriginId"], "path_pattern": value.get("PathPattern"),
        "allowed_methods": value["AllowedMethods"]["Items"],
        "cached_methods": value["AllowedMethods"]["CachedMethods"]["Items"],
        "viewer_protocol_policy": value["ViewerProtocolPolicy"],
        "cache_policy_id": value["CachePolicyId"], "origin_request_policy_id": value.get("OriginRequestPolicyId"),
        "response_headers_policy_id": value.get("ResponseHeadersPolicyId"),
        "realtime_log_config_arn": value.get("RealtimeLogConfigArn"),
        "field_level_encryption_id": value.get("FieldLevelEncryptionId", ""),
        "compress": value["Compress"], "smooth_streaming": value.get("SmoothStreaming", False),
        "trusted_key_groups": value.get("TrustedKeyGroups", {}).get("Items", []),
        "trusted_signers": value.get("TrustedSigners", {}).get("Items", []),
        "min_ttl": value.get("MinTTL", 0), "default_ttl": value.get("DefaultTTL", 0), "max_ttl": value.get("MaxTTL", 0),
        "grpc_enabled": value.get("GrpcConfig", {}).get("Enabled", False),
        "functions": [{"event_type": f["EventType"], "function_arn": f["FunctionARN"]}
                      for f in value.get("FunctionAssociations", {}).get("Items", [])],
        "lambdas": [{"event_type": f["EventType"], "lambda_arn": f["LambdaFunctionARN"],
                     "include_body": f.get("IncludeBody", False)}
                    for f in value.get("LambdaFunctionAssociations", {}).get("Items", [])],
    }
    return result


def distribution(value, resource_tags):
    for feature in ("OriginGroups", "ViewerMtlsConfig", "ConnectionFunctionAssociation", "CacheTagConfig"):
        present = value.get(feature)
        if present and (feature != "OriginGroups" or present.get("Quantity", 0) or present.get("Items")):
            raise ValueError(f"Unsupported CloudFront feature requires review: {feature}")
    origins = []
    for source in value["Origins"]["Items"]:
        if source.get("VpcOriginConfig") or source.get("OriginAccessControlId", "") not in ("", "E2JJZ8SZNVRWDC"):
            raise ValueError("CloudFront origin topology requires review")
        custom = source.get("CustomOriginConfig")
        s3 = source.get("S3OriginConfig")
        if s3 and s3.get("OriginReadTimeout", 30) != 30:
            raise ValueError("Nondefault S3 origin timeout requires provider support review")
        shield = source.get("OriginShield", {})
        origins.append({
            "origin_id": source["Id"], "domain_name": source["DomainName"], "origin_path": source.get("OriginPath", ""),
            "origin_access_control_id": source.get("OriginAccessControlId") or None,
            "connection_attempts": source["ConnectionAttempts"], "connection_timeout": source["ConnectionTimeout"],
            "response_completion_timeout": source.get("ResponseCompletionTimeout"),
            "custom_headers": [{"name": h["HeaderName"], "value": h["HeaderValue"]}
                               for h in source.get("CustomHeaders", {}).get("Items", [])],
            "custom_origin_config": [{"http_port": custom["HTTPPort"], "https_port": custom["HTTPSPort"],
                                      "ip_address_type": custom.get("IpAddressType", "ipv4"),
                                      "origin_protocol_policy": custom["OriginProtocolPolicy"],
                                      "origin_ssl_protocols": custom["OriginSslProtocols"]["Items"],
                                      "origin_read_timeout": custom["OriginReadTimeout"],
                                      "origin_keepalive_timeout": custom["OriginKeepaliveTimeout"]}] if custom else [],
            # An OAC S3 origin has an empty OAI, which the provider reads back as no s3_origin_config block.
            "s3_origin_config": [{"origin_access_identity": s3["OriginAccessIdentity"]}] if s3 and s3.get("OriginAccessIdentity") else [],
            "origin_shield": [{"enabled": True, "origin_shield_region": shield["OriginShieldRegion"]}]
                             if shield.get("Enabled") else [],
        })
    cert = value["ViewerCertificate"]
    geo = value["Restrictions"]["GeoRestriction"]
    logging = value["Logging"]
    return {
        "enabled": value["Enabled"], "is_ipv6_enabled": value["IsIPV6Enabled"],
        "aliases": value["Aliases"].get("Items", []), "comment": value["Comment"] or None,
        "default_root_object": value["DefaultRootObject"], "http_version": value["HttpVersion"],
        "price_class": value["PriceClass"], "web_acl_id": value.get("WebACLId", ""),
        "continuous_deployment_policy_id": value.get("ContinuousDeploymentPolicyId", ""),
        "staging": value.get("Staging", False), "tags": resource_tags, "origins": origins,
        "default_cache_behavior": behavior(value["DefaultCacheBehavior"]),
        "ordered_cache_behaviors": [behavior(b) for b in value["CacheBehaviors"].get("Items", [])],
        "geo_restriction": {"restriction_type": geo["RestrictionType"], "locations": geo.get("Items", [])},
        "viewer_certificate": {"cloudfront_default_certificate": cert.get("CloudFrontDefaultCertificate", False),
                               "acm_certificate_arn": cert.get("ACMCertificateArn"), "iam_certificate_id": cert.get("IAMCertificateId"),
                               "minimum_protocol_version": cert["MinimumProtocolVersion"],
                               "ssl_support_method": None if cert.get("CloudFrontDefaultCertificate") else cert.get("SSLSupportMethod")},
        "custom_error_responses": [{"error_code": e["ErrorCode"], "response_code": e.get("ResponseCode"),
                                    "response_page_path": e.get("ResponsePagePath"),
                                    "error_caching_min_ttl": e.get("ErrorCachingMinTTL", 300)}
                                   for e in value["CustomErrorResponses"].get("Items", [])],
        "logging": [{"bucket": logging["Bucket"], "prefix": logging["Prefix"], "include_cookies": logging["IncludeCookies"]}]
                   if logging["Enabled"] else [],
    }


def prepare(inventory_path):
    raw = json.loads(inventory_path.read_text(encoding="utf-8-sig"))
    if (raw.get("account_id"), raw.get("region"), raw.get("scope"), raw.get("complete")) != (ACCOUNT, REGION, "import", True):
        raise ValueError("A complete --scope import inventory for the YON account is required")
    results = raw["results"]

    def get(key, optional=False):
        result = results[key]
        if optional and result["status"] == "absent":
            return None
        if result["status"] != "ok":
            raise ValueError(f"Inventory query is not successful: {key}")
        return result["data"]

    if get("identity")["Account"] != ACCOUNT:
        raise ValueError("Inventory identity mismatch")
    if {v["VpcId"] for v in get("vpc")["Vpcs"]} != {VPC}:
        raise ValueError("VPC identity mismatch")
    n = json.loads((Path(__file__).parents[1] / "inventory/network-2026-10-28.json").read_text(encoding="utf-8"))
    if {r["SecurityGroupRuleId"] for r in get("sg-rules")["SecurityGroupRules"]} != {
            r["id"] for direction in ("ingress_rules", "egress_rules") for r in n[direction].values()}:
        raise ValueError("Network rules changed since the reviewed snapshot")
    config = {"account_id": ACCOUNT, "region": REGION, "roles": {}, "profiles": {}, "policies": {},
              "inline_policies": {}, "attachments": {}, "instances": {}, "functions": {}}
    targets = expected_targets()

    def add(address, import_id, regional=False, **values):
        targets[address] = {"import_id": import_id, "values": {**({"region": REGION} if regional else {}), **values}}

    for name in IMPORT_ROLES:
        role = get(f"roles/{name}")["Role"]
        if role["RoleName"] != name or role["Arn"] != f"arn:aws:iam::{ACCOUNT}:role/{name}":
            raise ValueError("Unexpected IAM role identity/path")
        config["roles"][name] = {"path": role["Path"], "description": role.get("Description", ""),
                                 "max_session_duration": role["MaxSessionDuration"],
                                 "assume_role_policy": document(role["AssumeRolePolicyDocument"]),
                                 "permissions_boundary": role.get("PermissionsBoundary", {}).get("PermissionsBoundaryArn"),
                                 "tags": tags(role.get("Tags", []))}
        add(f'aws_iam_role.existing["{name}"]', name, id=name, name=name, arn=role["Arn"])
        for item in get(f"profiles/{name}")["InstanceProfiles"]:
            pname = item["InstanceProfileName"]
            profile = get(f"profile/{pname}")["InstanceProfile"]
            if pname not in IMPORT_ROLES[:2] or [r["RoleName"] for r in profile["Roles"]] != [name]:
                raise ValueError("Unexpected instance profile binding")
            config["profiles"][pname] = {"path": profile["Path"], "role": name, "tags": tags(profile.get("Tags", []))}
            add(f'aws_iam_instance_profile.existing["{pname}"]', pname, id=pname, name=pname, role=name)
        for policy_name in get(f"inline/{name}")["PolicyNames"]:
            if policy_name in TEMPORARY_POLICIES:
                continue
            key = name + "/" + policy_name
            config["inline_policies"][key] = {"role": name, "name": policy_name,
                                               "policy": document(get(f"inline/{name}/{policy_name}")["PolicyDocument"])}
            add(f'aws_iam_role_policy.existing["{key}"]', name + ":" + policy_name, role=name, name=policy_name)
        for attached in get(f"attached/{name}")["AttachedPolicies"]:
            arn = attached["PolicyArn"]
            if arn.rsplit("/", 1)[-1] in TEMPORARY_POLICIES:
                continue
            key = name + "/" + arn
            config["attachments"][key] = {"role": name, "policy_arn": arn}
            add(f'aws_iam_role_policy_attachment.existing["{key}"]', key, role=name, policy_arn=arn)
            if arn.startswith(f"arn:aws:iam::{ACCOUNT}:policy/"):
                policy = get(f"policy/{arn}")["Policy"]
                config["policies"][arn] = {"name": policy["PolicyName"], "path": policy["Path"],
                                           "description": policy.get("Description", ""), "tags": tags(policy.get("Tags", [])),
                                           "policy": document(get(f"policy-version/{arn}")["PolicyVersion"]["Document"])}
                add(f'aws_iam_policy.existing["{arn}"]', arn, id=arn, arn=arn, name=policy["PolicyName"])
            elif not arn.startswith("arn:aws:iam::aws:policy/"):
                raise ValueError("Unexpected policy account")
    if set(config["profiles"]) != set(IMPORT_ROLES[:2]):
        raise ValueError("Both application instance profiles must be present")
    oidc = get("oidc")
    config["oidc"] = {"id": f"arn:aws:iam::{ACCOUNT}:oidc-provider/token.actions.githubusercontent.com",
                       "url": "https://" + oidc["Url"].removeprefix("https://"),
                       "client_id_list": oidc["ClientIDList"], "thumbprint_list": oidc["ThumbprintList"],
                       "tags": tags(oidc.get("Tags", []))}
    if config["oidc"]["url"] != "https://token.actions.githubusercontent.com":
        raise ValueError("Unexpected OIDC provider")
    # The provider stores the URL as IAM returns it (no scheme) and suppresses the https:// difference in config.
    add("aws_iam_openid_connect_provider.github", config["oidc"]["id"], id=config["oidc"]["id"],
        url=config["oidc"]["url"].removeprefix("https://"))
    instances = [i for r in get("instances")["Reservations"] for i in r["Instances"]]
    if {i["InstanceId"] for i in instances} != set(INSTANCES):
        raise ValueError("Exactly the four approved EC2 instances are required")
    volumes = {v["VolumeId"]: v for v in get("volumes")["Volumes"]}
    for source in instances:
        iid = source["InstanceId"]
        key = ("main", "data", "db", "replica")[INSTANCES.index(iid)]
        devices = source["BlockDeviceMappings"]
        if len(devices) != 1 or "Ebs" not in devices[0] or devices[0]["DeviceName"] != source["RootDeviceName"]:
            raise ValueError("Additional EC2 block devices require review")
        volume = volumes[devices[0]["Ebs"]["VolumeId"]]
        meta = source["MetadataOptions"]
        profile = source.get("IamInstanceProfile", {}).get("Arn", "").rsplit("/", 1)[-1] or None
        if source["VpcId"] != VPC or source["SubnetId"] not in {s["id"] for s in n["subnets"].values()}:
            raise ValueError("EC2 network mismatch")
        config["instances"][key] = {
            "id": iid, "ami": source["ImageId"], "instance_type": source["InstanceType"],
            "subnet_id": source["SubnetId"], "private_ip": source["PrivateIpAddress"], "key_name": source["KeyName"],
            "vpc_security_group_ids": sorted(g["GroupId"] for g in source["SecurityGroups"]),
            "iam_instance_profile": profile, "monitoring": source["Monitoring"]["State"] == "enabled",
            "disable_api_termination": get(f"termination/{iid}")["DisableApiTermination"]["Value"],
            "associate_public_ip_address": bool(source["NetworkInterfaces"][0].get("Association")),
            "ebs_optimized": source["EbsOptimized"], "tags": tags(source.get("Tags", [])),
            "metadata_options": {"http_endpoint": meta["HttpEndpoint"], "http_tokens": meta["HttpTokens"],
                                 "http_put_response_hop_limit": meta["HttpPutResponseHopLimit"],
                                 "http_protocol_ipv6": meta["HttpProtocolIpv6"], "instance_metadata_tags": meta["InstanceMetadataTags"]},
            "root_block_device": {"delete_on_termination": devices[0]["Ebs"]["DeleteOnTermination"],
                                  "encrypted": volume["Encrypted"], "volume_size": volume["Size"],
                                  "volume_type": volume["VolumeType"], "iops": volume["Iops"],
                                  "throughput": volume.get("Throughput"), "tags": tags(volume.get("Tags", []))},
        }
        add(f'aws_instance.existing["{key}"]', iid, regional=True, id=iid, subnet_id=source["SubnetId"],
            private_ip=source["PrivateIpAddress"], instance_type=source["InstanceType"], key_name=source["KeyName"])
    pairs = get("key-pair")["KeyPairs"]
    if len(pairs) != 1 or pairs[0]["KeyName"] != "team03_key" or not pairs[0].get("PublicKey"):
        raise ValueError("The existing team03_key public key is required")
    pair = pairs[0]
    config["key_pair"] = {"key_name": pair["KeyName"], "public_key": pair["PublicKey"], "tags": tags(pair.get("Tags", []))}
    add("aws_key_pair.team", pair["KeyName"], regional=True, id=pair["KeyName"], key_name=pair["KeyName"], fingerprint=pair["KeyFingerprint"])
    addresses = get("eips")["Addresses"]
    if len(addresses) != 1 or addresses[0]["AllocationId"] != "eipalloc-0357ffe1c7d9998ca" or addresses[0]["InstanceId"] != INSTANCES[0]:
        raise ValueError("Unexpected EIP identity/binding")
    eip = addresses[0]
    config["eip"] = {"id": eip["AllocationId"], "association_id": eip["AssociationId"], "tags": tags(eip.get("Tags", [])),
                      "network_interface_id": eip["NetworkInterfaceId"], "private_ip_address": eip["PrivateIpAddress"]}
    add("aws_eip.main", eip["AllocationId"], regional=True, id=eip["AllocationId"], domain="vpc", public_ip=eip["PublicIp"])
    add("aws_eip_association.main", eip["AssociationId"], regional=True, id=eip["AssociationId"],
        allocation_id=eip["AllocationId"], network_interface_id=eip["NetworkInterfaceId"], private_ip_address=eip["PrivateIpAddress"])
    version = get("s3/get-bucket-versioning")
    bucket_tags = get("s3/tags", optional=True)
    config["bucket"] = {"name": BUCKET, "tags": tags(bucket_tags["TagSet"]) if bucket_tags else {},
                         "versioning_status": version.get("Status", "Disabled"), "mfa_delete": version.get("MFADelete")}
    controls = {k: {} for k in ("policy", "public_access", "ownership", "encryption")}
    policy = get("s3/get-bucket-policy", optional=True)
    if policy is not None:
        controls["policy"]["frontend"] = {"policy": document(policy["Policy"])}
    public = get("s3/get-public-access-block", optional=True)
    if public is not None:
        controls["public_access"]["frontend"] = public["PublicAccessBlockConfiguration"]
    ownership = get("s3/get-bucket-ownership-controls", optional=True)
    if ownership is not None:
        rules = ownership["OwnershipControls"]["Rules"]
        if len(rules) != 1:
            raise ValueError("Unexpected S3 ownership rules")
        controls["ownership"]["frontend"] = {"object_ownership": rules[0]["ObjectOwnership"]}
    encryption = get("s3/get-bucket-encryption", optional=True)
    if encryption is not None:
        controls["encryption"]["frontend"] = {"rules": [
            {"bucket_key_enabled": r.get("BucketKeyEnabled", False),
             "blocked_encryption_types": r.get("BlockedEncryptionTypes", {}).get("EncryptionType", []),
             "sse_algorithm": r["ApplyServerSideEncryptionByDefault"]["SSEAlgorithm"],
             "kms_master_key_id": r["ApplyServerSideEncryptionByDefault"].get("KMSMasterKeyID")}
            for r in encryption["ServerSideEncryptionConfiguration"]["Rules"]]}
    config["bucket_controls"] = controls
    add("aws_s3_bucket.frontend", BUCKET, regional=True, id=BUCKET, bucket=BUCKET)
    add("aws_s3_bucket_versioning.frontend", BUCKET, regional=True, id=BUCKET, bucket=BUCKET)
    types = {"policy": "policy", "public_access": "public_access_block", "ownership": "ownership_controls", "encryption": "server_side_encryption_configuration"}
    for key, suffix in types.items():
        if controls[key]:
            add(f'aws_s3_bucket_{suffix}.frontend["frontend"]', BUCKET, regional=True, id=BUCKET, bucket=BUCKET)
    summaries = get("cf-distributions")["DistributionList"].get("Items", [])
    selected = [d for d in summaries if any(o["Id"] == "yon-backend" for o in d["Origins"]["Items"])]
    if len(selected) != 1:
        raise ValueError("Exactly one YON CloudFront distribution is required")
    did = selected[0]["Id"]
    full = get("cloudfront/" + did)["Distribution"]
    if full["Id"] != did or full["ARN"] != f"arn:aws:cloudfront::{ACCOUNT}:distribution/{did}":
        raise ValueError("CloudFront identity mismatch")
    config["distribution"] = {"id": did, **distribution(full["DistributionConfig"], tags(get("cloudfront/" + did + "/tags")["Tags"].get("Items", [])))}
    add("aws_cloudfront_distribution.frontend", did, id=did, arn=full["ARN"])
    oac = get("cf-oac")["OriginAccessControl"]
    if oac["Id"] != "E2JJZ8SZNVRWDC":
        raise ValueError("Unexpected OAC identity")
    oc = oac["OriginAccessControlConfig"]
    config["oac"] = {"id": oac["Id"], "name": oc["Name"], "description": oc["Description"],
                      "origin_type": oc["OriginAccessControlOriginType"], "signing_behavior": oc["SigningBehavior"], "signing_protocol": oc["SigningProtocol"]}
    add("aws_cloudfront_origin_access_control.frontend", oac["Id"], id=oac["Id"], name=oc["Name"])
    functions = sorted({f["function_arn"].rsplit("/", 1)[-1]
                        for b in [config["distribution"]["default_cache_behavior"], *config["distribution"]["ordered_cache_behaviors"]]
                        for f in b["functions"]})
    if functions != ["yon-spa-rewrite"]:
        raise ValueError("Unexpected CloudFront function associations")
    for index, name in enumerate(functions):
        live = get(f"cf-function/{name}/config")["FunctionSummary"]["FunctionConfig"]
        dev = get(f"cf-function/{name}/development")["FunctionSummary"]["FunctionConfig"]
        code = (inventory_path.parent / f"cf-function-{index}.js").read_text(encoding="utf-8")
        dev_code = (inventory_path.parent / f"cf-function-{index}-development.js").read_text(encoding="utf-8")
        if live != dev or code != dev_code:
            raise ValueError("CloudFront LIVE/DEVELOPMENT differ; review before adopting the function")
        config["functions"][name] = {"runtime": live["Runtime"], "comment": live["Comment"], "code": code,
                                      "key_value_store_associations": [x["KeyValueStoreARN"] for x in live.get("KeyValueStoreAssociations", {}).get("Items", [])],
                                      "tags": tags(get(f"cf-function/{name}/tags")["Tags"].get("Items", []))}
        add(f'aws_cloudfront_function.existing["{name}"]', name, id=name, name=name)
    return config, remap_targets(targets)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="New private directory outside Git")
    args = parser.parse_args()
    try:
        if Path(__file__).resolve().parents[2] in args.output.resolve().parents:
            raise ValueError("Keep private inputs outside the checkout")
        config, targets = prepare(args.inventory)
        args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
        files = {"adoption.tfvars.json": {"adoption": config}, "targets.json": targets,
                 "source.json": {"inventory_sha256": hashlib.sha256(args.inventory.read_bytes()).hexdigest(),
                                 "account_id": ACCOUNT, "region": REGION, "target_count": len(targets)}}
        for name, value in files.items():
            path = args.output / name
            path.touch(mode=0o600, exist_ok=False)
            path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.exit(1, f"STOP: {type(error).__name__}: inputs could not be prepared; review inventory completeness/topology\n")
    print(f"Prepared {len(targets)} exact targets. Keep inputs, inventory and plans private.")


if __name__ == "__main__":
    main()
