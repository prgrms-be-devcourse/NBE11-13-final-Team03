# Observed attributes are mocked. This does not contact AWS or verify live drift.
mock_provider "aws" {}

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

run "preserve_observed_network" {
  command = plan
  assert {
    condition     = length(aws_security_group.service) == 4 && length(aws_subnet.public) == 2
    error_message = "Expected four service groups and two public subnets."
  }
  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.existing) == 10 && length(aws_vpc_security_group_egress_rule.existing) == 4
    error_message = "The existing 10 inbound and 4 outbound rules must be preserved."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.existing["main_http"].cidr_ipv4 == "0.0.0.0/0" && aws_vpc_security_group_ingress_rule.existing["main_http"].from_port == 8080 && aws_vpc_security_group_ingress_rule.existing["main_ssh"].cidr_ipv4 == "0.0.0.0/0"
    error_message = "Restricting HTTP/SSH belongs to a later reviewed change."
  }
  assert {
    condition     = aws_route.internet.gateway_id == aws_internet_gateway.main.id && aws_route.internet.route_table_id == aws_route_table.public.id
    error_message = "The public default route must retain its gateway and route table."
  }
  assert {
    condition     = aws_route_table.main.id == aws_vpc.main.default_route_table_id
    error_message = "The VPC main/default route table must retain its identity."
  }
  assert {
    condition     = alltrue([for rule in aws_vpc_security_group_egress_rule.existing : rule.ip_protocol == "-1" && rule.from_port == null && rule.to_port == null && rule.cidr_ipv4 == "0.0.0.0/0"])
    error_message = "All-protocol egress must omit port values."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.existing["data_ssh"].description == "ssh via yon-main" && aws_vpc_security_group_ingress_rule.existing["data_grpc"].referenced_security_group_id == aws_security_group.service["main"].id
    error_message = "Descriptions and cross-group references must be preserved."
  }
}
