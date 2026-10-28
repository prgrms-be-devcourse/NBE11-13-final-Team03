# Observed attributes are mocked. This does not contact AWS or verify live drift.
mock_provider "aws" {}

variables {
  adoption = jsondecode(file("tests/adoption.fixture.json")).adoption
}

override_resource {
  target = aws_vpc.main
  values = { "region" : "ap-northeast-2", "id" : "vpc-032bd9d88d5c97147", "cidr_block" : "10.0.0.0/16", "instance_tenancy" : "default", "tags" : { "Name" : "yon-vpc" }, "default_route_table_id" : "rtb-0b7d3d03690c74791", "enable_dns_support" : true }
}

override_resource {
  target = aws_internet_gateway.main
  values = { "region" : "ap-northeast-2", "id" : "igw-08837edad9a26957c", "vpc_id" : "vpc-032bd9d88d5c97147", "tags" : { "Name" : "yon-igw" } }
}

override_resource {
  target = aws_route_table.main
  values = { "region" : "ap-northeast-2", "id" : "rtb-0b7d3d03690c74791", "vpc_id" : "vpc-032bd9d88d5c97147", "tags" : {} }
}

override_resource {
  target = aws_route_table.public
  values = { "region" : "ap-northeast-2", "id" : "rtb-0e5380dd325267c16", "vpc_id" : "vpc-032bd9d88d5c97147", "tags" : { "Name" : "yon-rtb-public" } }
}

override_resource {
  target = aws_route.internet
  values = { "region" : "ap-northeast-2", "route_table_id" : "rtb-0e5380dd325267c16", "destination_cidr_block" : "0.0.0.0/0", "gateway_id" : "igw-08837edad9a26957c" }
}

override_resource {
  target = aws_subnet.public["public_a"]
  values = { "region" : "ap-northeast-2", "id" : "subnet-084d01b4bc4420dc4", "vpc_id" : "vpc-032bd9d88d5c97147", "cidr_block" : "10.0.0.0/20", "availability_zone" : "ap-northeast-2a", "map_public_ip_on_launch" : true, "private_dns_hostname_type_on_launch" : "ip-name", "tags" : { "Name" : "yon-subnet-public1-ap-northeast-2a" }, "assign_ipv6_address_on_creation" : false, "enable_dns64" : false, "enable_resource_name_dns_a_record_on_launch" : false, "enable_resource_name_dns_aaaa_record_on_launch" : false }
}

override_resource {
  target = aws_route_table_association.public["public_a"]
  values = { "region" : "ap-northeast-2", "id" : "rtbassoc-01f78681b3a7bce31", "subnet_id" : "subnet-084d01b4bc4420dc4", "route_table_id" : "rtb-0e5380dd325267c16" }
}

override_resource {
  target = aws_subnet.public["public_b"]
  values = { "region" : "ap-northeast-2", "id" : "subnet-0f2f8345977f304f9", "vpc_id" : "vpc-032bd9d88d5c97147", "cidr_block" : "10.0.16.0/20", "availability_zone" : "ap-northeast-2b", "map_public_ip_on_launch" : true, "private_dns_hostname_type_on_launch" : "ip-name", "tags" : { "Name" : "yon-subnet-public2-ap-northeast-2b" }, "assign_ipv6_address_on_creation" : false, "enable_dns64" : false, "enable_resource_name_dns_a_record_on_launch" : false, "enable_resource_name_dns_aaaa_record_on_launch" : false }
}

override_resource {
  target = aws_route_table_association.public["public_b"]
  values = { "region" : "ap-northeast-2", "id" : "rtbassoc-07f70dec8c3b309eb", "subnet_id" : "subnet-0f2f8345977f304f9", "route_table_id" : "rtb-0e5380dd325267c16" }
}

override_resource {
  target = aws_security_group.service["data"]
  values = { "region" : "ap-northeast-2", "id" : "sg-042c6f59681a7b1bd", "vpc_id" : "vpc-032bd9d88d5c97147", "name" : "yon-data", "description" : "data", "tags" : {} }
}

override_resource {
  target = aws_security_group.service["db"]
  values = { "region" : "ap-northeast-2", "id" : "sg-03c341b41a4087a48", "vpc_id" : "vpc-032bd9d88d5c97147", "name" : "yon-db", "description" : "DB", "tags" : {} }
}

override_resource {
  target = aws_security_group.service["main"]
  values = { "region" : "ap-northeast-2", "id" : "sg-0fbaf2496d1edd1ed", "vpc_id" : "vpc-032bd9d88d5c97147", "name" : "yon-main", "description" : "main", "tags" : {} }
}

override_resource {
  target = aws_security_group.service["replica"]
  values = { "region" : "ap-northeast-2", "id" : "sg-04b53d2630b9a7ea9", "vpc_id" : "vpc-032bd9d88d5c97147", "name" : "yon-db-replica", "description" : "replica", "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["data_grpc"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-0822ff4c706e1dcab", "security_group_id" : "sg-042c6f59681a7b1bd", "ip_protocol" : "tcp", "from_port" : 9092, "to_port" : 9092, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-0fbaf2496d1edd1ed", "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["data_ssh"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-08507733aeb83b722", "security_group_id" : "sg-042c6f59681a7b1bd", "ip_protocol" : "tcp", "from_port" : 22, "to_port" : 22, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-0fbaf2496d1edd1ed", "description" : "ssh via yon-main", "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["db_data"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-04e10529cd6758dcd", "security_group_id" : "sg-03c341b41a4087a48", "ip_protocol" : "tcp", "from_port" : 5432, "to_port" : 5432, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-042c6f59681a7b1bd", "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["db_main"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-012f9acbdd2a43d46", "security_group_id" : "sg-03c341b41a4087a48", "ip_protocol" : "tcp", "from_port" : 5432, "to_port" : 5432, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-0fbaf2496d1edd1ed", "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["db_replica"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-082e5a36242f1daaf", "security_group_id" : "sg-03c341b41a4087a48", "ip_protocol" : "tcp", "from_port" : 5432, "to_port" : 5432, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-04b53d2630b9a7ea9", "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["db_ssh"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-06d13f5dd51c26904", "security_group_id" : "sg-03c341b41a4087a48", "ip_protocol" : "tcp", "from_port" : 22, "to_port" : 22, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-0fbaf2496d1edd1ed", "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["main_http"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-05f5a7aabab29ed7d", "security_group_id" : "sg-0fbaf2496d1edd1ed", "ip_protocol" : "tcp", "from_port" : 8080, "to_port" : 8080, "cidr_ipv4" : "0.0.0.0/0", "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : null, "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["main_ssh"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-0d3d7d9d481776c61", "security_group_id" : "sg-0fbaf2496d1edd1ed", "ip_protocol" : "tcp", "from_port" : 22, "to_port" : 22, "cidr_ipv4" : "0.0.0.0/0", "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : null, "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["replica_main"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-09085b1124dc31468", "security_group_id" : "sg-04b53d2630b9a7ea9", "ip_protocol" : "tcp", "from_port" : 5432, "to_port" : 5432, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-0fbaf2496d1edd1ed", "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.existing["replica_ssh"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-032f88857867e0892", "security_group_id" : "sg-04b53d2630b9a7ea9", "ip_protocol" : "tcp", "from_port" : 22, "to_port" : 22, "cidr_ipv4" : null, "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : "sg-0fbaf2496d1edd1ed", "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_egress_rule.existing["data"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-06162a33efd262518", "security_group_id" : "sg-042c6f59681a7b1bd", "ip_protocol" : "-1", "from_port" : null, "to_port" : null, "cidr_ipv4" : "0.0.0.0/0", "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : null, "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_egress_rule.existing["db"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-0e4bf2af964175717", "security_group_id" : "sg-03c341b41a4087a48", "ip_protocol" : "-1", "from_port" : null, "to_port" : null, "cidr_ipv4" : "0.0.0.0/0", "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : null, "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_egress_rule.existing["main"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-05c778c6d5e5cdffc", "security_group_id" : "sg-0fbaf2496d1edd1ed", "ip_protocol" : "-1", "from_port" : null, "to_port" : null, "cidr_ipv4" : "0.0.0.0/0", "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : null, "description" : null, "tags" : {} }
}

override_resource {
  target = aws_vpc_security_group_egress_rule.existing["replica"]
  values = { "region" : "ap-northeast-2", "id" : "sgr-0855df5ec2c055f03", "security_group_id" : "sg-04b53d2630b9a7ea9", "ip_protocol" : "-1", "from_port" : null, "to_port" : null, "cidr_ipv4" : "0.0.0.0/0", "cidr_ipv6" : null, "prefix_list_id" : null, "referenced_security_group_id" : null, "description" : null, "tags" : {} }
}

override_resource {
  target = aws_iam_role.existing["team03-main-sqs"]
  values = { "path" : "/", "description" : "", "max_session_duration" : 3600, "assume_role_policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}", "permissions_boundary" : null, "tags" : {}, "name" : "team03-main-sqs", "id" : "team03-main-sqs", "arn" : "arn:aws:iam::174170816230:role/team03-main-sqs" }
}

override_resource {
  target = aws_iam_role.existing["team03-data-sqs"]
  values = { "path" : "/", "description" : "", "max_session_duration" : 3600, "assume_role_policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}", "permissions_boundary" : null, "tags" : {}, "name" : "team03-data-sqs", "id" : "team03-data-sqs", "arn" : "arn:aws:iam::174170816230:role/team03-data-sqs" }
}

override_resource {
  target = aws_iam_role.existing["team03-github-actions-deploy"]
  values = { "path" : "/", "description" : "", "max_session_duration" : 3600, "assume_role_policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}", "permissions_boundary" : null, "tags" : {}, "name" : "team03-github-actions-deploy", "id" : "team03-github-actions-deploy", "arn" : "arn:aws:iam::174170816230:role/team03-github-actions-deploy" }
}

override_resource {
  target = aws_iam_role.existing["team03-frontend-deploy"]
  values = { "path" : "/", "description" : "", "max_session_duration" : 3600, "assume_role_policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}", "permissions_boundary" : null, "tags" : {}, "name" : "team03-frontend-deploy", "id" : "team03-frontend-deploy", "arn" : "arn:aws:iam::174170816230:role/team03-frontend-deploy" }
}

override_resource {
  target = aws_iam_instance_profile.existing["team03-main-sqs"]
  values = { "path" : "/", "role" : "team03-main-sqs", "tags" : {}, "name" : "team03-main-sqs", "id" : "team03-main-sqs" }
}

override_resource {
  target = aws_iam_instance_profile.existing["team03-data-sqs"]
  values = { "path" : "/", "role" : "team03-data-sqs", "tags" : {}, "name" : "team03-data-sqs", "id" : "team03-data-sqs" }
}

override_resource {
  target = aws_iam_policy.existing["arn:aws:iam::174170816230:policy/fixture-app"]
  values = { "name" : "fixture-app", "path" : "/", "description" : "", "tags" : {}, "policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}", "id" : "arn:aws:iam::174170816230:policy/fixture-app", "arn" : "arn:aws:iam::174170816230:policy/fixture-app" }
}

override_resource {
  target = aws_iam_role_policy.existing["team03-main-sqs/fixture-app"]
  values = { "role" : "team03-main-sqs", "name" : "fixture-app", "policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}" }
}

override_resource {
  target = aws_iam_role_policy.existing["team03-data-sqs/fixture-app"]
  values = { "role" : "team03-data-sqs", "name" : "fixture-app", "policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}" }
}

override_resource {
  target = aws_iam_role_policy.existing["team03-github-actions-deploy/fixture-app"]
  values = { "role" : "team03-github-actions-deploy", "name" : "fixture-app", "policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}" }
}

override_resource {
  target = aws_iam_role_policy.existing["team03-frontend-deploy/fixture-app"]
  values = { "role" : "team03-frontend-deploy", "name" : "fixture-app", "policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}" }
}

override_resource {
  target = aws_iam_role_policy_attachment.existing["team03-main-sqs/arn:aws:iam::174170816230:policy/fixture-app"]
  values = { "role" : "team03-main-sqs", "policy_arn" : "arn:aws:iam::174170816230:policy/fixture-app" }
}

override_resource {
  target = aws_iam_role_policy_attachment.existing["team03-data-sqs/arn:aws:iam::174170816230:policy/fixture-app"]
  values = { "role" : "team03-data-sqs", "policy_arn" : "arn:aws:iam::174170816230:policy/fixture-app" }
}

override_resource {
  target = aws_iam_role_policy_attachment.existing["team03-github-actions-deploy/arn:aws:iam::174170816230:policy/fixture-app"]
  values = { "role" : "team03-github-actions-deploy", "policy_arn" : "arn:aws:iam::174170816230:policy/fixture-app" }
}

override_resource {
  target = aws_iam_role_policy_attachment.existing["team03-frontend-deploy/arn:aws:iam::174170816230:policy/fixture-app"]
  values = { "role" : "team03-frontend-deploy", "policy_arn" : "arn:aws:iam::174170816230:policy/fixture-app" }
}

override_resource {
  target = aws_iam_openid_connect_provider.github
  values = { "id" : "arn:aws:iam::174170816230:oidc-provider/token.actions.githubusercontent.com", "url" : "https://token.actions.githubusercontent.com", "client_id_list" : ["sts.amazonaws.com"], "thumbprint_list" : ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"], "tags" : {} }
}

override_resource {
  target = aws_instance.existing["main"]
  values = { "id" : "i-05f26052a70059c73", "ami" : "ami-0e1eeb87102d64f40", "instance_type" : "t3.small", "subnet_id" : "subnet-084d01b4bc4420dc4", "private_ip" : "10.0.0.226", "key_name" : "team03_key", "vpc_security_group_ids" : ["sg-0fbaf2496d1edd1ed"], "iam_instance_profile" : "team03-main-sqs", "monitoring" : false, "disable_api_termination" : false, "associate_public_ip_address" : true, "ebs_optimized" : true, "tags" : {}, "region" : "ap-northeast-2", "metadata_options" : { "http_endpoint" : "enabled", "http_tokens" : "required", "http_put_response_hop_limit" : 2, "http_protocol_ipv6" : "disabled", "instance_metadata_tags" : "disabled" }, "root_block_device" : { "delete_on_termination" : true, "encrypted" : false, "volume_size" : 30, "volume_type" : "gp3", "iops" : 3000, "throughput" : 125, "tags" : {} } }
}

override_resource {
  target = aws_instance.existing["data"]
  values = { "id" : "i-0b48442522e0c3400", "ami" : "ami-0e1eeb87102d64f40", "instance_type" : "t3.small", "subnet_id" : "subnet-084d01b4bc4420dc4", "private_ip" : "10.0.5.57", "key_name" : "team03_key", "vpc_security_group_ids" : ["sg-042c6f59681a7b1bd"], "iam_instance_profile" : "team03-data-sqs", "monitoring" : false, "disable_api_termination" : false, "associate_public_ip_address" : true, "ebs_optimized" : true, "tags" : {}, "region" : "ap-northeast-2", "metadata_options" : { "http_endpoint" : "enabled", "http_tokens" : "required", "http_put_response_hop_limit" : 2, "http_protocol_ipv6" : "disabled", "instance_metadata_tags" : "disabled" }, "root_block_device" : { "delete_on_termination" : true, "encrypted" : false, "volume_size" : 30, "volume_type" : "gp3", "iops" : 3000, "throughput" : 125, "tags" : {} } }
}

override_resource {
  target = aws_instance.existing["db"]
  values = { "id" : "i-08a92649f4f51f8a5", "ami" : "ami-0e1eeb87102d64f40", "instance_type" : "t3.small", "subnet_id" : "subnet-084d01b4bc4420dc4", "private_ip" : "10.0.0.37", "key_name" : "team03_key", "vpc_security_group_ids" : ["sg-03c341b41a4087a48"], "iam_instance_profile" : null, "monitoring" : false, "disable_api_termination" : false, "associate_public_ip_address" : true, "ebs_optimized" : true, "tags" : {}, "region" : "ap-northeast-2", "metadata_options" : { "http_endpoint" : "enabled", "http_tokens" : "required", "http_put_response_hop_limit" : 2, "http_protocol_ipv6" : "disabled", "instance_metadata_tags" : "disabled" }, "root_block_device" : { "delete_on_termination" : true, "encrypted" : false, "volume_size" : 30, "volume_type" : "gp3", "iops" : 3000, "throughput" : 125, "tags" : {} } }
}

override_resource {
  target = aws_instance.existing["replica"]
  values = { "id" : "i-0f45b533faf285beb", "ami" : "ami-0e1eeb87102d64f40", "instance_type" : "t3.small", "subnet_id" : "subnet-084d01b4bc4420dc4", "private_ip" : "10.0.2.203", "key_name" : "team03_key", "vpc_security_group_ids" : ["sg-04b53d2630b9a7ea9"], "iam_instance_profile" : null, "monitoring" : false, "disable_api_termination" : false, "associate_public_ip_address" : true, "ebs_optimized" : true, "tags" : {}, "region" : "ap-northeast-2", "metadata_options" : { "http_endpoint" : "enabled", "http_tokens" : "required", "http_put_response_hop_limit" : 2, "http_protocol_ipv6" : "disabled", "instance_metadata_tags" : "disabled" }, "root_block_device" : { "delete_on_termination" : true, "encrypted" : false, "volume_size" : 30, "volume_type" : "gp3", "iops" : 3000, "throughput" : 125, "tags" : {} } }
}

override_resource {
  target = aws_key_pair.team
  values = { "key_name" : "team03_key", "public_key" : "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ fixture-only", "tags" : {}, "region" : "ap-northeast-2", "id" : "team03_key", "fingerprint" : "fixture-fingerprint" }
}

override_resource {
  target = aws_eip.main
  values = { "tags" : {}, "region" : "ap-northeast-2", "id" : "eipalloc-0357ffe1c7d9998ca", "domain" : "vpc", "public_ip" : "192.0.2.1" }
}

override_resource {
  target = aws_eip_association.main
  values = { "allocation_id" : "eipalloc-0357ffe1c7d9998ca", "network_interface_id" : "eni-fixture", "private_ip_address" : "10.0.0.226", "region" : "ap-northeast-2", "id" : "eipassoc-00f0fea3b7e580d0c" }
}

override_resource {
  target = aws_s3_bucket.frontend
  values = { "bucket" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "tags" : {}, "force_destroy" : false, "region" : "ap-northeast-2", "id" : "yon-frontend-team03-174170816230-ap-northeast-2-an" }
}

override_resource {
  target = aws_s3_bucket_versioning.frontend
  values = { "bucket" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "region" : "ap-northeast-2", "id" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "versioning_configuration" : { "status" : "Disabled", "mfa_delete" : null } }
}

override_resource {
  target = aws_s3_bucket_policy.frontend["frontend"]
  values = { "bucket" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "policy" : "{\"Statement\":[{\"Action\":\"sqs:GetQueueAttributes\",\"Effect\":\"Allow\",\"Resource\":\"*\"}],\"Version\":\"2012-10-17\"}", "region" : "ap-northeast-2", "id" : "yon-frontend-team03-174170816230-ap-northeast-2-an" }
}

override_resource {
  target = aws_s3_bucket_public_access_block.frontend["frontend"]
  values = { "bucket" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "block_public_acls" : true, "block_public_policy" : true, "ignore_public_acls" : true, "restrict_public_buckets" : true, "region" : "ap-northeast-2", "id" : "yon-frontend-team03-174170816230-ap-northeast-2-an" }
}

override_resource {
  target = aws_s3_bucket_ownership_controls.frontend["frontend"]
  values = { "bucket" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "region" : "ap-northeast-2", "id" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "rule" : { "object_ownership" : "BucketOwnerEnforced" } }
}

override_resource {
  target = aws_s3_bucket_server_side_encryption_configuration.frontend["frontend"]
  values = { "bucket" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "region" : "ap-northeast-2", "id" : "yon-frontend-team03-174170816230-ap-northeast-2-an", "rule" : { "bucket_key_enabled" : false, "blocked_encryption_types" : [], "apply_server_side_encryption_by_default" : { "sse_algorithm" : "AES256", "kms_master_key_id" : null } } }
}

override_resource {
  target = aws_cloudfront_origin_access_control.frontend
  values = { "name" : "fixture-oac", "description" : "fixture only", "origin_access_control_origin_type" : "s3", "signing_behavior" : "always", "signing_protocol" : "sigv4", "id" : "E2JJZ8SZNVRWDC" }
}

override_resource {
  target = aws_cloudfront_function.existing["yon-spa-rewrite"]
  values = { "runtime" : "cloudfront-js-2.0", "comment" : "fixture only", "code" : "function handler(event) { return event.request; }\n", "key_value_store_associations" : [], "tags" : {}, "name" : "yon-spa-rewrite", "publish" : true, "id" : "yon-spa-rewrite" }
}

override_resource {
  target = aws_cloudfront_distribution.frontend
  values = { "id" : "EIMPORTFIXTURE", "enabled" : true, "is_ipv6_enabled" : true, "aliases" : [], "comment" : "fixture only", "default_root_object" : "index.html", "http_version" : "http2", "price_class" : "PriceClass_All", "web_acl_id" : "", "continuous_deployment_policy_id" : "", "staging" : false, "tags" : {}, "retain_on_delete" : true, "wait_for_deployment" : false, "arn" : "arn:aws:cloudfront::174170816230:distribution/EIMPORTFIXTURE", "default_cache_behavior" : { "target_origin_id" : "yon-backend", "allowed_methods" : ["GET", "HEAD"], "cached_methods" : ["GET", "HEAD"], "viewer_protocol_policy" : "redirect-to-https", "cache_policy_id" : "658327ea-f89d-4fab-a63d-7e88639e58f6", "origin_request_policy_id" : null, "response_headers_policy_id" : null, "realtime_log_config_arn" : null, "field_level_encryption_id" : "", "compress" : true, "smooth_streaming" : false, "trusted_key_groups" : [], "trusted_signers" : [], "min_ttl" : 0, "default_ttl" : 0, "max_ttl" : 0, "function_association" : { "event_type" : "viewer-request", "function_arn" : "arn:aws:cloudfront::174170816230:function/yon-spa-rewrite" }, "grpc_config" : { "enabled" : false } }, "ordered_cache_behavior" : { "target_origin_id" : "yon-backend", "path_pattern" : "/api/*", "allowed_methods" : ["GET", "HEAD"], "cached_methods" : ["GET", "HEAD"], "viewer_protocol_policy" : "redirect-to-https", "cache_policy_id" : "658327ea-f89d-4fab-a63d-7e88639e58f6", "origin_request_policy_id" : null, "response_headers_policy_id" : null, "realtime_log_config_arn" : null, "field_level_encryption_id" : "", "compress" : true, "smooth_streaming" : false, "trusted_key_groups" : [], "trusted_signers" : [], "min_ttl" : 0, "default_ttl" : 0, "max_ttl" : 0, "function_association" : { "event_type" : "viewer-request", "function_arn" : "arn:aws:cloudfront::174170816230:function/yon-spa-rewrite" }, "grpc_config" : { "enabled" : false } }, "origin" : { "origin_id" : "yon-backend", "domain_name" : "fixture.example.com", "origin_path" : "", "origin_access_control_id" : null, "connection_attempts" : 3, "connection_timeout" : 10, "response_completion_timeout" : null, "custom_header" : { "name" : "X-Forwarded-Proto", "value" : "https" }, "custom_origin_config" : { "http_port" : 8080, "https_port" : 443, "ip_address_type" : "ipv4", "origin_protocol_policy" : "http-only", "origin_ssl_protocols" : ["TLSv1.2"], "origin_read_timeout" : 30, "origin_keepalive_timeout" : 5 } }, "restrictions" : { "geo_restriction" : { "restriction_type" : "none", "locations" : [] } }, "viewer_certificate" : { "cloudfront_default_certificate" : true, "acm_certificate_arn" : null, "iam_certificate_id" : null, "minimum_protocol_version" : "TLSv1", "ssl_support_method" : "vip" } }
}

run "preserve_observed_infrastructure" {
  command = plan
  assert {
    condition     = length(aws_iam_role.existing) == 4 && length(aws_instance.existing) == 4 && length(aws_security_group.service) == 4
    error_message = "Keep all four service roles, instances and groups."
  }
  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.existing) == 10 && aws_vpc_security_group_ingress_rule.existing["main_http"].cidr_ipv4 == "0.0.0.0/0" && aws_vpc_security_group_ingress_rule.existing["main_http"].from_port == 8080
    error_message = "Import must preserve the existing ten inbound rules and HTTP access."
  }
  assert {
    condition     = aws_route_table.main.id == aws_vpc.main.default_route_table_id && alltrue([for rule in aws_vpc_security_group_egress_rule.existing : rule.from_port == null && rule.to_port == null])
    error_message = "Keep route-table identity and all-protocol egress."
  }
  assert {
    condition     = length(aws_iam_role_policy.existing) == 4 && length(aws_iam_instance_profile.existing) == 2 && length(aws_cloudfront_function.existing) == 1
    error_message = "Bootstrap policies must not be imported; preserve application profiles and function."
  }
  assert {
    condition     = aws_s3_bucket.frontend.bucket == "yon-frontend-team03-174170816230-ap-northeast-2-an" && aws_s3_bucket_versioning.frontend.versioning_configuration[0].status == "Disabled"
    error_message = "Use the existing frontend bucket without enabling versioning."
  }
}
