locals {
  network = jsondecode(file("${path.module}/../inventory/network-2026-10-28.json"))
}

provider "aws" {
  region              = "ap-northeast-2"
  allowed_account_ids = ["174170816230"]
}

resource "aws_vpc" "main" {
  cidr_block       = local.network.vpc.cidr_block
  instance_tenancy = local.network.vpc.instance_tenancy
  tags             = local.network.vpc.tags

  lifecycle {
    prevent_destroy = true
    # DescribeVpcs does not report this flag. Preserve its imported value.
    ignore_changes = [enable_dns_support]
    precondition {
      condition     = local.network.account_id == "174170816230" && local.network.region == "ap-northeast-2"
      error_message = "The network snapshot must belong to the YON Seoul account."
    }
  }
}

resource "aws_subnet" "public" {
  for_each                                       = local.network.subnets
  vpc_id                                         = aws_vpc.main.id
  cidr_block                                     = each.value.cidr_block
  availability_zone                              = each.value.availability_zone
  map_public_ip_on_launch                        = each.value.map_public_ip_on_launch
  private_dns_hostname_type_on_launch            = each.value.private_dns_hostname_type_on_launch
  assign_ipv6_address_on_creation                = false
  enable_dns64                                   = false
  enable_resource_name_dns_a_record_on_launch    = false
  enable_resource_name_dns_aaaa_record_on_launch = false
  tags                                           = each.value.tags

  lifecycle { prevent_destroy = true }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = local.network.internet_gateway.tags

  lifecycle { prevent_destroy = true }
}

# Import the existing table by rtb-*; preserve its existing main association.
resource "aws_route_table" "main" {
  vpc_id = aws_vpc.main.id
  tags   = local.network.main_route_table.tags

  lifecycle { prevent_destroy = true }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  tags   = local.network.public_route_table.tags
  # Routes are owned by the standalone aws_route below; omit inline routes.
  lifecycle { prevent_destroy = true }
}

resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id

  lifecycle { prevent_destroy = true }
}

resource "aws_route_table_association" "public" {
  for_each       = local.network.subnets
  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public.id

  lifecycle { prevent_destroy = true }
}

resource "aws_security_group" "service" {
  for_each    = local.network.security_groups
  name        = each.value.name
  description = each.value.description
  vpc_id      = aws_vpc.main.id
  tags        = each.value.tags
  # Omit inline ingress/egress: each existing sgr-* is imported separately.
  lifecycle { prevent_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "existing" {
  for_each                     = local.network.ingress_rules
  security_group_id            = aws_security_group.service[each.value.group].id
  ip_protocol                  = each.value.ip_protocol
  from_port                    = each.value.from_port
  to_port                      = each.value.to_port
  cidr_ipv4                    = try(each.value.cidr_ipv4, null)
  referenced_security_group_id = try(aws_security_group.service[each.value.referenced_group].id, null)
  description                  = try(each.value.description, null)
  tags                         = length(each.value.tags) > 0 ? each.value.tags : null

  # Imported rules without tags read back as null, so an empty map would plan a tag update.
  lifecycle { prevent_destroy = true }
}

resource "aws_vpc_security_group_egress_rule" "existing" {
  for_each          = local.network.egress_rules
  security_group_id = aws_security_group.service[each.value.group].id
  ip_protocol       = each.value.ip_protocol
  cidr_ipv4         = each.value.cidr_ipv4
  # Protocol -1 must not set from_port/to_port to -1.
  tags = length(each.value.tags) > 0 ? each.value.tags : null

  lifecycle { prevent_destroy = true }
}
