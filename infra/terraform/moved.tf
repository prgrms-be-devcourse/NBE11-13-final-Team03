# Retain this history for every existing state; do not remove after the first apply.

moved {
  from = aws_cloudfront_distribution.frontend
  to   = module.frontend.aws_cloudfront_distribution.this
}

moved {
  from = aws_cloudfront_function.existing["yon-spa-rewrite"]
  to   = module.frontend.aws_cloudfront_function.this["yon-spa-rewrite"]
}

moved {
  from = aws_cloudfront_origin_access_control.frontend
  to   = module.frontend.aws_cloudfront_origin_access_control.this
}

moved {
  from = aws_eip.main
  to   = module.main.aws_eip.this[0]
}

moved {
  from = aws_eip_association.main
  to   = module.main.aws_eip_association.this[0]
}

moved {
  from = aws_iam_instance_profile.existing["team03-data-sqs"]
  to   = module.data.aws_iam_instance_profile.this[0]
}

moved {
  from = aws_iam_instance_profile.existing["team03-main-sqs"]
  to   = module.main.aws_iam_instance_profile.this[0]
}

moved {
  from = aws_iam_openid_connect_provider.github
  to   = module.github_oidc.aws_iam_openid_connect_provider.this
}

moved {
  from = aws_iam_role.existing["team03-data-sqs"]
  to   = module.data.aws_iam_role.this[0]
}

moved {
  from = aws_iam_role.existing["team03-frontend-deploy"]
  to   = module.github_oidc.aws_iam_role.this
}

moved {
  from = aws_iam_role.existing["team03-main-sqs"]
  to   = module.main.aws_iam_role.this[0]
}

moved {
  from = aws_instance.existing["data"]
  to   = module.data.aws_instance.this
}

moved {
  from = aws_instance.existing["db"]
  to   = module.db.aws_instance.this
}

moved {
  from = aws_instance.existing["main"]
  to   = module.main.aws_instance.this
}

moved {
  from = aws_instance.existing["replica"]
  to   = module.replica.aws_instance.this
}

moved {
  from = aws_internet_gateway.main
  to   = module.network.aws_internet_gateway.this
}

moved {
  from = aws_route.internet
  to   = module.network.aws_route.this
}

moved {
  from = aws_route_table.main
  to   = module.network.aws_route_table.main
}

moved {
  from = aws_route_table.public
  to   = module.network.aws_route_table.public
}

moved {
  from = aws_route_table_association.public["public_a"]
  to   = module.network.aws_route_table_association.this["public_a"]
}

moved {
  from = aws_route_table_association.public["public_b"]
  to   = module.network.aws_route_table_association.this["public_b"]
}

moved {
  from = aws_s3_bucket.frontend
  to   = module.frontend.aws_s3_bucket.this
}

moved {
  from = aws_s3_bucket_ownership_controls.frontend["frontend"]
  to   = module.frontend.aws_s3_bucket_ownership_controls.this["frontend"]
}

moved {
  from = aws_s3_bucket_policy.frontend["frontend"]
  to   = module.frontend.aws_s3_bucket_policy.this["frontend"]
}

moved {
  from = aws_s3_bucket_public_access_block.frontend["frontend"]
  to   = module.frontend.aws_s3_bucket_public_access_block.this["frontend"]
}

moved {
  from = aws_s3_bucket_server_side_encryption_configuration.frontend["frontend"]
  to   = module.frontend.aws_s3_bucket_server_side_encryption_configuration.this["frontend"]
}

moved {
  from = aws_s3_bucket_versioning.frontend
  to   = module.frontend.aws_s3_bucket_versioning.this
}

moved {
  from = aws_security_group.service["data"]
  to   = module.data.aws_security_group.this
}

moved {
  from = aws_security_group.service["db"]
  to   = module.db.aws_security_group.this
}

moved {
  from = aws_security_group.service["main"]
  to   = module.main.aws_security_group.this
}

moved {
  from = aws_security_group.service["replica"]
  to   = module.replica.aws_security_group.this
}

moved {
  from = aws_subnet.public["public_a"]
  to   = module.network.aws_subnet.this["public_a"]
}

moved {
  from = aws_subnet.public["public_b"]
  to   = module.network.aws_subnet.this["public_b"]
}

moved {
  from = aws_vpc.main
  to   = module.network.aws_vpc.this
}

moved {
  from = aws_vpc_security_group_egress_rule.existing["data"]
  to   = module.data.aws_vpc_security_group_egress_rule.this
}

moved {
  from = aws_vpc_security_group_egress_rule.existing["db"]
  to   = module.db.aws_vpc_security_group_egress_rule.this
}

moved {
  from = aws_vpc_security_group_egress_rule.existing["main"]
  to   = module.main.aws_vpc_security_group_egress_rule.this
}

moved {
  from = aws_vpc_security_group_egress_rule.existing["replica"]
  to   = module.replica.aws_vpc_security_group_egress_rule.this
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["data_grpc"]
  to   = module.data.aws_vpc_security_group_ingress_rule.this["data_grpc"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["data_ssh"]
  to   = module.data.aws_vpc_security_group_ingress_rule.this["data_ssh"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["db_data"]
  to   = module.db.aws_vpc_security_group_ingress_rule.this["db_data"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["db_main"]
  to   = module.db.aws_vpc_security_group_ingress_rule.this["db_main"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["db_replica"]
  to   = module.db.aws_vpc_security_group_ingress_rule.this["db_replica"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["db_ssh"]
  to   = module.db.aws_vpc_security_group_ingress_rule.this["db_ssh"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["main_http"]
  to   = module.main.aws_vpc_security_group_ingress_rule.this["main_http"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["main_ssh"]
  to   = module.main.aws_vpc_security_group_ingress_rule.this["main_ssh"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["replica_main"]
  to   = module.replica.aws_vpc_security_group_ingress_rule.this["replica_main"]
}

moved {
  from = aws_vpc_security_group_ingress_rule.existing["replica_ssh"]
  to   = module.replica.aws_vpc_security_group_ingress_rule.this["replica_ssh"]
}
