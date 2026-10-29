import {
  to = module.network.aws_vpc.this
  id = local.network.vpc.id
}

import {
  for_each = local.network.subnets
  to       = module.network.aws_subnet.this[each.key]
  id       = each.value.id
}

import {
  to = module.network.aws_internet_gateway.this
  id = local.network.internet_gateway.id
}

import {
  to = module.network.aws_route_table.main
  id = local.network.main_route_table.id
}

import {
  to = module.network.aws_route_table.public
  id = local.network.public_route_table.id
}

import {
  to = module.network.aws_route.this
  id = "${local.network.public_route_table.id}_0.0.0.0/0"
}

import {
  for_each = local.network.subnets
  to       = module.network.aws_route_table_association.this[each.key]
  id       = "${each.value.id}/${local.network.public_route_table.id}"
}

import {
  to = module.main.aws_security_group.this
  id = local.network.security_groups.main.id
}

import {
  to = module.data.aws_security_group.this
  id = local.network.security_groups.data.id
}

import {
  to = module.db.aws_security_group.this
  id = local.network.security_groups.db.id
}

import {
  to = module.replica.aws_security_group.this
  id = local.network.security_groups.replica.id
}

import {
  for_each = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "main" }
  to       = module.main.aws_vpc_security_group_ingress_rule.this[each.key]
  id       = each.value.id
}

import {
  for_each = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "data" }
  to       = module.data.aws_vpc_security_group_ingress_rule.this[each.key]
  id       = each.value.id
}

import {
  for_each = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "db" }
  to       = module.db.aws_vpc_security_group_ingress_rule.this[each.key]
  id       = each.value.id
}

import {
  for_each = { for key, rule in local.network.ingress_rules : key => rule if rule.group == "replica" }
  to       = module.replica.aws_vpc_security_group_ingress_rule.this[each.key]
  id       = each.value.id
}

import {
  to = module.main.aws_vpc_security_group_egress_rule.this
  id = local.network.egress_rules.main.id
}

import {
  to = module.data.aws_vpc_security_group_egress_rule.this
  id = local.network.egress_rules.data.id
}

import {
  to = module.db.aws_vpc_security_group_egress_rule.this
  id = local.network.egress_rules.db.id
}

import {
  to = module.replica.aws_vpc_security_group_egress_rule.this
  id = local.network.egress_rules.replica.id
}

import {
  to = module.main.aws_instance.this
  id = var.adoption.instances.main.id
}

import {
  to = module.data.aws_instance.this
  id = var.adoption.instances.data.id
}

import {
  to = module.db.aws_instance.this
  id = var.adoption.instances.db.id
}

import {
  to = module.replica.aws_instance.this
  id = var.adoption.instances.replica.id
}

import {
  to = aws_key_pair.team
  id = var.adoption.key_pair.key_name
}

import {
  to = module.main.aws_eip.this[0]
  id = var.adoption.eip.id
}

import {
  to = module.main.aws_eip_association.this[0]
  id = var.adoption.eip.association_id
}

import {
  to = module.main.aws_iam_role.this[0]
  id = "team03-main-sqs"
}

import {
  to = module.data.aws_iam_role.this[0]
  id = "team03-data-sqs"
}

import {
  to = module.github_oidc.aws_iam_role.this
  id = "team03-frontend-deploy"
}

import {
  to = module.main.aws_iam_instance_profile.this[0]
  id = "team03-main-sqs"
}

import {
  to = module.data.aws_iam_instance_profile.this[0]
  id = "team03-data-sqs"
}

import {
  for_each = var.adoption.policies
  to       = aws_iam_policy.existing[each.key]
  id       = each.key
}

import {
  for_each = var.adoption.inline_policies
  to       = aws_iam_role_policy.existing[each.key]
  id       = "${each.value.role}:${each.value.name}"
}

import {
  for_each = var.adoption.attachments
  to       = aws_iam_role_policy_attachment.existing[each.key]
  id       = "${each.value.role}/${each.value.policy_arn}"
}

import {
  to = module.github_oidc.aws_iam_openid_connect_provider.this
  id = var.adoption.oidc.id
}

import {
  to = module.frontend.aws_s3_bucket.this
  id = var.adoption.bucket.name
}

import {
  for_each = var.adoption.bucket_controls.policy
  to       = module.frontend.aws_s3_bucket_policy.this[each.key]
  id       = var.adoption.bucket.name
}

import {
  for_each = var.adoption.bucket_controls.public_access
  to       = module.frontend.aws_s3_bucket_public_access_block.this[each.key]
  id       = var.adoption.bucket.name
}

import {
  for_each = var.adoption.bucket_controls.ownership
  to       = module.frontend.aws_s3_bucket_ownership_controls.this[each.key]
  id       = var.adoption.bucket.name
}

import {
  to = module.frontend.aws_s3_bucket_versioning.this
  id = var.adoption.bucket.name
}

import {
  for_each = var.adoption.bucket_controls.encryption
  to       = module.frontend.aws_s3_bucket_server_side_encryption_configuration.this[each.key]
  id       = var.adoption.bucket.name
}

import {
  to = module.frontend.aws_cloudfront_origin_access_control.this
  id = var.adoption.oac.id
}

import {
  for_each = var.adoption.functions
  to       = module.frontend.aws_cloudfront_function.this[each.key]
  id       = each.key
}

import {
  to = module.frontend.aws_cloudfront_distribution.this
  id = var.adoption.distribution.id
}
