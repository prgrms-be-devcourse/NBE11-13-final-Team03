"""Synthetic AWS responses for import checks. Never use for live execution."""
import json
from pathlib import Path


def make_inventory(directory):
    root = Path(__file__).parents[1]
    n = json.loads((root / "inventory/network-2026-10-28.json").read_text(encoding="utf-8"))
    account, region = n["account_id"], n["region"]
    results = {}

    def put(key, data):
        results[key] = {"status": "ok", "data": data}

    policy = {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "sqs:GetQueueAttributes", "Resource": "*"}]}
    role_names = ("team03-main-sqs", "team03-data-sqs", "team03-github-actions-deploy", "team03-frontend-deploy")
    put("identity", {"Account": account})
    put("vpc", {"Vpcs": [{"VpcId": n["vpc"]["id"]}]})
    put("sg-rules", {"SecurityGroupRules": [{"SecurityGroupRuleId": r["id"]} for d in ("ingress_rules", "egress_rules") for r in n[d].values()]})
    arn = f"arn:aws:iam::{account}:policy/fixture-app"
    for index, name in enumerate(role_names):
        put(f"roles/{name}", {"Role": {"RoleName": name, "Arn": f"arn:aws:iam::{account}:role/{name}", "Path": "/",
                                       "AssumeRolePolicyDocument": policy, "MaxSessionDuration": 3600}})
        profiles = [{"InstanceProfileName": name}] if index < 2 else []
        put(f"profiles/{name}", {"InstanceProfiles": profiles})
        if profiles:
            put(f"profile/{name}", {"InstanceProfile": {"InstanceProfileName": name, "Path": "/", "Roles": [{"RoleName": name}]}})
        put(f"inline/{name}", {"PolicyNames": ["fixture-app", "YONTerraformImportReadTemporary"]})
        put(f"inline/{name}/fixture-app", {"PolicyDocument": policy})
        put(f"attached/{name}", {"AttachedPolicies": [{"PolicyArn": arn}]})
    put(f"policy/{arn}", {"Policy": {"PolicyName": "fixture-app", "Path": "/"}})
    put(f"policy-version/{arn}", {"PolicyVersion": {"Document": policy}})
    put("oidc", {"Url": "token.actions.githubusercontent.com", "ClientIDList": ["sts.amazonaws.com"], "ThumbprintList": ["a" * 40]})
    baseline = json.loads((root / "inventory/baseline-2026-10-28.json").read_text(encoding="utf-8"))
    instances, volumes = [], []
    for index, original in enumerate(baseline["instances"]):
        iid = original["InstanceId"]
        volume_id = f"vol-fixture{index}"
        instances.append({"InstanceId": iid, "ImageId": "ami-0e1eeb87102d64f40", "InstanceType": "t3.small",
                          "VpcId": n["vpc"]["id"], "SubnetId": original["SubnetId"], "PrivateIpAddress": original["PrivateIpAddress"],
                          "KeyName": "team03_key", "Monitoring": {"State": "disabled"}, "EbsOptimized": True,
                          "NetworkInterfaces": [{"Association": {"PublicIp": "192.0.2.1"}}],
                          "IamInstanceProfile": {"Arn": original["instance_profile_arn"]} if original["instance_profile_arn"] else {},
                          "SecurityGroups": [{"GroupId": n["security_groups"][("main", "data", "db", "replica")[index]]["id"]}],
                          "MetadataOptions": {"HttpEndpoint": "enabled", "HttpTokens": "required", "HttpPutResponseHopLimit": 2,
                                              "HttpProtocolIpv6": "disabled", "InstanceMetadataTags": "disabled"},
                          "RootDeviceName": "/dev/sda1", "BlockDeviceMappings": [{"DeviceName": "/dev/sda1", "Ebs": {"VolumeId": volume_id, "DeleteOnTermination": True}}]})
        volumes.append({"VolumeId": volume_id, "Size": 30, "VolumeType": "gp3", "Encrypted": False, "Iops": 3000, "Throughput": 125})
        put(f"termination/{iid}", {"DisableApiTermination": {"Value": False}})
    put("instances", {"Reservations": [{"Instances": instances}]})
    put("volumes", {"Volumes": volumes})
    put("key-pair", {"KeyPairs": [{"KeyName": "team03_key", "PublicKey": "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ fixture-only", "KeyFingerprint": "fixture-fingerprint"}]})
    put("eips", {"Addresses": [{"AllocationId": "eipalloc-0357ffe1c7d9998ca", "AssociationId": "eipassoc-00f0fea3b7e580d0c",
                               "InstanceId": baseline["instances"][0]["InstanceId"], "NetworkInterfaceId": "eni-fixture", "PrivateIpAddress": "10.0.0.226", "PublicIp": "192.0.2.1"}]})
    put("s3/get-bucket-versioning", {})
    put("s3/tags", {"TagSet": []})
    put("s3/get-bucket-policy", {"Policy": json.dumps(policy)})
    put("s3/get-public-access-block", {"PublicAccessBlockConfiguration": {k: True for k in ("BlockPublicAcls", "BlockPublicPolicy", "IgnorePublicAcls", "RestrictPublicBuckets")}})
    put("s3/get-bucket-ownership-controls", {"OwnershipControls": {"Rules": [{"ObjectOwnership": "BucketOwnerEnforced"}]}})
    put("s3/get-bucket-encryption", {"ServerSideEncryptionConfiguration": {"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]}})
    behavior = {"TargetOriginId": "yon-backend", "AllowedMethods": {"Items": ["GET", "HEAD"], "CachedMethods": {"Items": ["GET", "HEAD"]}},
                "ViewerProtocolPolicy": "redirect-to-https", "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6", "Compress": True,
                "FunctionAssociations": {"Items": [{"EventType": "viewer-request", "FunctionARN": f"arn:aws:cloudfront::{account}:function/yon-spa-rewrite"}]}}
    origins = {"Items": [{"Id": "yon-backend", "DomainName": "fixture.example.com", "ConnectionAttempts": 3, "ConnectionTimeout": 10,
                          "CustomHeaders": {"Items": [{"HeaderName": "X-Forwarded-Proto", "HeaderValue": "https"}]},
                          "CustomOriginConfig": {"HTTPPort": 8080, "HTTPSPort": 443, "OriginProtocolPolicy": "http-only",
                                                 "OriginSslProtocols": {"Items": ["TLSv1.2"]}, "OriginReadTimeout": 30, "OriginKeepaliveTimeout": 5}}]}
    distro = {"Origins": origins, "OriginGroups": {"Quantity": 0}, "Enabled": True, "IsIPV6Enabled": True,
              "Aliases": {"Quantity": 0}, "Comment": "fixture only", "DefaultRootObject": "index.html", "HttpVersion": "http2", "PriceClass": "PriceClass_All",
              "DefaultCacheBehavior": behavior, "CacheBehaviors": {"Items": [{**behavior, "PathPattern": "/api/*"}]},
              "Restrictions": {"GeoRestriction": {"RestrictionType": "none"}},
              "ViewerCertificate": {"CloudFrontDefaultCertificate": True, "MinimumProtocolVersion": "TLSv1", "SSLSupportMethod": "vip"},
              "CustomErrorResponses": {"Quantity": 0}, "Logging": {"Enabled": False}}
    did = "EIMPORTFIXTURE"
    put("cf-distributions", {"DistributionList": {"Items": [{"Id": did, "ARN": f"arn:aws:cloudfront::{account}:distribution/{did}", "Origins": origins}]}})
    put("cloudfront/" + did, {"Distribution": {"Id": did, "ARN": f"arn:aws:cloudfront::{account}:distribution/{did}", "DistributionConfig": distro}})
    put("cloudfront/" + did + "/tags", {"Tags": {"Items": []}})
    put("cf-oac", {"OriginAccessControl": {"Id": "E2JJZ8SZNVRWDC", "OriginAccessControlConfig": {"Name": "fixture-oac", "Description": "fixture only",
                                                                                             "OriginAccessControlOriginType": "s3", "SigningBehavior": "always", "SigningProtocol": "sigv4"}}})
    function = {"FunctionSummary": {"FunctionConfig": {"Runtime": "cloudfront-js-2.0", "Comment": "fixture only"}}}
    put("cf-function/yon-spa-rewrite/config", function)
    put("cf-function/yon-spa-rewrite/development", function)
    put("cf-function/yon-spa-rewrite/tags", {"Tags": {"Items": []}})
    for suffix in ("", "-development"):
        (directory / f"cf-function-0{suffix}.js").write_text("function handler(event) { return event.request; }\n", encoding="utf-8")
    raw = {"account_id": account, "region": region, "scope": "import", "complete": True, "results": results}
    path = directory / "inventory.json"
    path.write_text(json.dumps(raw), encoding="utf-8")
    return path, raw
