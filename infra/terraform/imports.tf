import {
  to = aws_vpc.main
  id = local.network.vpc.id
}

import {
  for_each = local.network.subnets
  to       = aws_subnet.public[each.key]
  id       = each.value.id
}

import {
  to = aws_internet_gateway.main
  id = local.network.internet_gateway.id
}

import {
  to = aws_route_table.main
  id = local.network.main_route_table.id
}

import {
  to = aws_route_table.public
  id = local.network.public_route_table.id
}

import {
  to = aws_route.internet
  id = "${local.network.public_route_table.id}_0.0.0.0/0"
}

import {
  for_each = local.network.subnets
  to       = aws_route_table_association.public[each.key]
  id       = "${each.value.id}/${local.network.public_route_table.id}"
}

import {
  for_each = local.network.security_groups
  to       = aws_security_group.service[each.key]
  id       = each.value.id
}

import {
  for_each = local.network.ingress_rules
  to       = aws_vpc_security_group_ingress_rule.existing[each.key]
  id       = each.value.id
}

import {
  for_each = local.network.egress_rules
  to       = aws_vpc_security_group_egress_rule.existing[each.key]
  id       = each.value.id
}
