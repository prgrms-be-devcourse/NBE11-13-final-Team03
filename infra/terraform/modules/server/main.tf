resource "aws_instance" "this" {
  ami                         = var.instance.ami
  instance_type               = var.instance.instance_type
  subnet_id                   = var.instance.subnet_id
  private_ip                  = var.instance.private_ip
  key_name                    = var.instance.key_name
  vpc_security_group_ids      = var.instance.vpc_security_group_ids
  iam_instance_profile        = try(aws_iam_instance_profile.this[0].name, null)
  disable_api_termination     = var.instance.disable_api_termination
  monitoring                  = var.instance.monitoring
  associate_public_ip_address = var.instance.associate_public_ip_address
  ebs_optimized               = var.instance.ebs_optimized
  tags                        = var.instance.tags
  metadata_options {
    http_endpoint               = var.instance.metadata_options.http_endpoint
    http_tokens                 = var.instance.metadata_options.http_tokens
    http_put_response_hop_limit = var.instance.metadata_options.http_put_response_hop_limit
    http_protocol_ipv6          = var.instance.metadata_options.http_protocol_ipv6
    instance_metadata_tags      = var.instance.metadata_options.instance_metadata_tags
  }
  root_block_device {
    delete_on_termination = var.instance.root_block_device.delete_on_termination
    encrypted             = var.instance.root_block_device.encrypted
    volume_size           = var.instance.root_block_device.volume_size
    volume_type           = var.instance.root_block_device.volume_type
    iops                  = var.instance.root_block_device.iops
    throughput            = var.instance.root_block_device.throughput
    tags                  = var.instance.root_block_device.tags
  }
  lifecycle {
    prevent_destroy = true
    # Preserve the imported launch image and user-data; no rebootstrap/restart.
    ignore_changes = [ami, user_data, user_data_base64]
  }
}

resource "aws_security_group" "this" {
  name        = var.security_group.name
  description = var.security_group.description
  vpc_id      = var.vpc_id
  tags        = var.security_group.tags
  # Omit inline ingress/egress: each existing sgr-* is imported separately.
  lifecycle { prevent_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "this" {
  for_each                     = var.ingress
  security_group_id            = aws_security_group.this.id
  ip_protocol                  = each.value.ip_protocol
  from_port                    = each.value.from_port
  to_port                      = each.value.to_port
  cidr_ipv4                    = try(each.value.cidr_ipv4, null)
  referenced_security_group_id = try(var.peer_sg_ids[each.value.referenced_group], null)
  description                  = try(each.value.description, null)
  tags                         = each.value.tags

  lifecycle { prevent_destroy = true }
}

resource "aws_vpc_security_group_egress_rule" "this" {
  security_group_id = aws_security_group.this.id
  ip_protocol       = var.egress.ip_protocol
  cidr_ipv4         = var.egress.cidr_ipv4
  # Protocol -1 must not set from_port/to_port to -1.
  tags = var.egress.tags

  lifecycle { prevent_destroy = true }
}

resource "aws_iam_role" "this" {
  count                = var.instance_role == null ? 0 : 1
  name                 = var.instance_role.name
  path                 = var.instance_role.path
  description          = var.instance_role.description
  max_session_duration = var.instance_role.max_session_duration
  assume_role_policy   = var.instance_role.assume_role_policy
  permissions_boundary = var.instance_role.permissions_boundary
  tags                 = var.instance_role.tags
  # Attachments and inline policies are independently imported below.
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_instance_profile" "this" {
  count = var.profile == null ? 0 : 1
  name  = var.profile.name
  path  = var.profile.path
  role  = aws_iam_role.this[0].name
  tags  = var.profile.tags
  lifecycle { prevent_destroy = true }
}

resource "aws_eip" "this" {
  count  = var.eip == null ? 0 : 1
  domain = "vpc"
  tags   = var.eip.tags
  # Association belongs to the standalone resource below.
  lifecycle { prevent_destroy = true }
}

resource "aws_eip_association" "this" {
  count                = var.eip == null ? 0 : 1
  allocation_id        = var.eip.id
  network_interface_id = var.eip.network_interface_id
  private_ip_address   = var.eip.private_ip_address
  lifecycle { prevent_destroy = true }
}
