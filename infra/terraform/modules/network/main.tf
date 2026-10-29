resource "aws_vpc" "this" {
  cidr_block       = var.network.vpc.cidr_block
  instance_tenancy = var.network.vpc.instance_tenancy
  tags             = var.network.vpc.tags

  lifecycle {
    prevent_destroy = true
    # DescribeVpcs does not report this flag. Preserve its imported value.
    ignore_changes = [enable_dns_support]
    precondition {
      condition     = var.network.account_id == "174170816230" && var.network.region == "ap-northeast-2"
      error_message = "The network snapshot must belong to the YON Seoul account."
    }
  }
}

resource "aws_subnet" "this" {
  for_each                                       = var.network.subnets
  vpc_id                                         = aws_vpc.this.id
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

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = var.network.internet_gateway.tags

  lifecycle { prevent_destroy = true }
}

# Import the existing table by rtb-*; preserve its existing main association.

resource "aws_route_table" "main" {
  vpc_id = aws_vpc.this.id
  tags   = var.network.main_route_table.tags

  lifecycle { prevent_destroy = true }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = var.network.public_route_table.tags
  # Routes are owned by the standalone aws_route below; omit inline routes.
  lifecycle { prevent_destroy = true }
}

resource "aws_route" "this" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id

  lifecycle { prevent_destroy = true }
}

resource "aws_route_table_association" "this" {
  for_each       = var.network.subnets
  subnet_id      = aws_subnet.this[each.key].id
  route_table_id = aws_route_table.public.id

  lifecycle { prevent_destroy = true }
}
