resource "aws_instance" "existing" {
  for_each                    = var.adoption.instances
  ami                         = each.value.ami
  instance_type               = each.value.instance_type
  subnet_id                   = each.value.subnet_id
  private_ip                  = each.value.private_ip
  key_name                    = each.value.key_name
  vpc_security_group_ids      = each.value.vpc_security_group_ids
  iam_instance_profile        = each.value.iam_instance_profile
  disable_api_termination     = each.value.disable_api_termination
  monitoring                  = each.value.monitoring
  associate_public_ip_address = each.value.associate_public_ip_address
  ebs_optimized               = each.value.ebs_optimized
  tags                        = each.value.tags
  metadata_options {
    http_endpoint               = each.value.metadata_options.http_endpoint
    http_tokens                 = each.value.metadata_options.http_tokens
    http_put_response_hop_limit = each.value.metadata_options.http_put_response_hop_limit
    http_protocol_ipv6          = each.value.metadata_options.http_protocol_ipv6
    instance_metadata_tags      = each.value.metadata_options.instance_metadata_tags
  }
  root_block_device {
    delete_on_termination = each.value.root_block_device.delete_on_termination
    encrypted             = each.value.root_block_device.encrypted
    volume_size           = each.value.root_block_device.volume_size
    volume_type           = each.value.root_block_device.volume_type
    iops                  = each.value.root_block_device.iops
    throughput            = each.value.root_block_device.throughput
    tags                  = each.value.root_block_device.tags
  }
  lifecycle {
    prevent_destroy = true
    # Preserve the imported launch image and user-data; no rebootstrap/restart.
    ignore_changes = [ami, user_data, user_data_base64]
  }
}

resource "aws_key_pair" "team" {
  key_name   = var.adoption.key_pair.key_name
  public_key = var.adoption.key_pair.public_key
  tags       = var.adoption.key_pair.tags
  lifecycle {
    prevent_destroy = true
    # Some imported key pairs do not round-trip public_key. Check fingerprint.
    ignore_changes = [public_key]
  }
}

resource "aws_eip" "main" {
  domain = "vpc"
  tags   = var.adoption.eip.tags
  # Association belongs to the standalone resource below.
  lifecycle { prevent_destroy = true }
}

resource "aws_eip_association" "main" {
  allocation_id        = var.adoption.eip.id
  network_interface_id = var.adoption.eip.network_interface_id
  private_ip_address   = var.adoption.eip.private_ip_address
  lifecycle { prevent_destroy = true }
}

import {
  for_each = var.adoption.instances
  to       = aws_instance.existing[each.key]
  id       = each.value.id
}

import {
  to = aws_key_pair.team
  id = var.adoption.key_pair.key_name
}

import {
  to = aws_eip.main
  id = var.adoption.eip.id
}

import {
  to = aws_eip_association.main
  id = var.adoption.eip.association_id
}
